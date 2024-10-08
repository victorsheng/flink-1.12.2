/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.io.network.partition.consumer;

import org.apache.flink.runtime.checkpoint.CheckpointException;
import org.apache.flink.runtime.checkpoint.CheckpointFailureReason;
import org.apache.flink.runtime.checkpoint.channel.ChannelStateWriter;
import org.apache.flink.runtime.checkpoint.channel.InputChannelInfo;
import org.apache.flink.runtime.event.AbstractEvent;
import org.apache.flink.runtime.io.network.api.CheckpointBarrier;
import org.apache.flink.runtime.io.network.api.serialization.EventSerializer;
import org.apache.flink.runtime.io.network.buffer.Buffer;
import org.apache.flink.util.CloseableIterator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.apache.flink.util.Preconditions.checkNotNull;

/** Helper class for persisting channel state via {@link ChannelStateWriter}. */
@NotThreadSafe
public final class ChannelStatePersister {
    private static final Logger LOG = LoggerFactory.getLogger(ChannelStatePersister.class);

    private final InputChannelInfo channelInfo;

    private enum CheckpointStatus {
        // 完成
        COMPLETED,
        // 挂起
        BARRIER_PENDING,
        // 已接收
        BARRIER_RECEIVED
    }

    private CheckpointStatus checkpointStatus = CheckpointStatus.COMPLETED;

    private long lastSeenBarrier = -1L;

    /**
     * Writer must be initialized before usage. {@link #startPersisting(long, List)} enforces this
     * invariant.
     */
    private final ChannelStateWriter channelStateWriter;

    ChannelStatePersister(ChannelStateWriter channelStateWriter, InputChannelInfo channelInfo) {
        this.channelStateWriter = checkNotNull(channelStateWriter);
        this.channelInfo = checkNotNull(channelInfo);
    }

    protected void startPersisting(long barrierId, List<Buffer> knownBuffers)
            throws CheckpointException {
        logEvent("startPersisting", barrierId);

        // 判断 检查点的状态必须为已接收完毕,并且最后一个 lastSeenBarrier 要大于当前输入的barrierId
        if (checkpointStatus == CheckpointStatus.BARRIER_RECEIVED && lastSeenBarrier > barrierId) {
            throw new CheckpointException(
                    String.format(
                            "Barrier for newer checkpoint %d has already been received compared to the requested checkpoint %d",
                            lastSeenBarrier, barrierId),
                    CheckpointFailureReason
                            .CHECKPOINT_SUBSUMED); // currently, at most one active unaligned
        }

        if (lastSeenBarrier < barrierId) {
            // 不管当前的检查点状态如何，如果我们收到关于最近的检查点的通知，那么我们到目前为止已经看到了，总是标记这个最近的屏障是挂起的。
            //
            // BARRIER_RECEIVED
            // status可以发生，如果我们看到一个旧的BARRIER，该BARRIER可能还没有被任务处理，但是任务现在通知我们检查点已经为新的检查点启动。
            //
            // 我们应该把我们所知道的都说出来，并表明我们正在等待新的障碍的到来

            // Regardless of the current checkpointStatus, if we are notified about a more recent
            // checkpoint then we have seen so far, always mark that this more recent barrier is
            // pending.
            // BARRIER_RECEIVED status can happen if we have seen an older barrier, that probably
            // has not yet been processed by the task, but task is now notifying us that checkpoint
            // has started for even newer checkpoint. We should spill the knownBuffers and mark that
            // we are waiting for that newer barrier to arrive
            checkpointStatus = CheckpointStatus.BARRIER_PENDING;
            lastSeenBarrier = barrierId;
        }
        if (knownBuffers.size() > 0) {
            channelStateWriter.addInputData(
                    barrierId,
                    channelInfo,
                    ChannelStateWriter.SEQUENCE_NUMBER_UNKNOWN,
                    CloseableIterator.fromList(knownBuffers, Buffer::recycleBuffer));
        }
    }

    protected void stopPersisting(long id) {
        logEvent("stopPersisting", id);
        if (id >= lastSeenBarrier) {
            checkpointStatus = CheckpointStatus.COMPLETED;
            lastSeenBarrier = id;
        }
    }

    protected void maybePersist(Buffer buffer) {
        if (checkpointStatus == CheckpointStatus.BARRIER_PENDING && buffer.isBuffer()) {
            channelStateWriter.addInputData(
                    lastSeenBarrier,
                    channelInfo,
                    ChannelStateWriter.SEQUENCE_NUMBER_UNKNOWN,
                    CloseableIterator.ofElement(buffer.retainBuffer(), Buffer::recycleBuffer));
        }
    }

    protected Optional<Long> checkForBarrier(Buffer buffer) throws IOException {
        final AbstractEvent priorityEvent = parsePriorityEvent(buffer);
        if (priorityEvent instanceof CheckpointBarrier) {
            final long barrierId = ((CheckpointBarrier) priorityEvent).getId();
            long expectedBarrierId =
                    checkpointStatus == CheckpointStatus.COMPLETED
                            ? lastSeenBarrier + 1
                            : lastSeenBarrier;
            if (barrierId >= expectedBarrierId) {
                logEvent("found barrier", barrierId);
                checkpointStatus = CheckpointStatus.BARRIER_RECEIVED;
                lastSeenBarrier = barrierId;
                return Optional.of(lastSeenBarrier);
            } else {
                logEvent("ignoring barrier", barrierId);
            }
        }
        return Optional.empty();
    }

    private void logEvent(String event, long barrierId) {
        if (LOG.isDebugEnabled()) {
            LOG.debug(
                    "{} {}, lastSeenBarrier = {} ({}) @ {}",
                    event,
                    barrierId,
                    lastSeenBarrier,
                    checkpointStatus,
                    channelInfo);
        }
    }

    /**
     * Parses the buffer as an event and returns the {@link CheckpointBarrier} if the event is
     * indeed a barrier or returns null in all other cases.
     */
    @Nullable
    protected AbstractEvent parsePriorityEvent(Buffer buffer) throws IOException {
        if (buffer.isBuffer() || !buffer.getDataType().hasPriority()) {
            return null;
        }

        AbstractEvent event = EventSerializer.fromBuffer(buffer, getClass().getClassLoader());
        // reset the buffer because it would be deserialized again in SingleInputGate while getting
        // next buffer.
        // we can further improve to avoid double deserialization in the future.
        buffer.setReaderIndex(0);
        return event;
    }

    protected boolean hasBarrierReceived() {
        return checkpointStatus == CheckpointStatus.BARRIER_RECEIVED;
    }

    @Override
    public String toString() {
        return "ChannelStatePersister(lastSeenBarrier="
                + lastSeenBarrier
                + " ("
                + checkpointStatus
                + ")}";
    }
}
