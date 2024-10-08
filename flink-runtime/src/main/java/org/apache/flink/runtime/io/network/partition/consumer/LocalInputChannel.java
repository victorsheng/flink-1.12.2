/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.io.network.partition.consumer;

import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.metrics.Counter;
import org.apache.flink.runtime.checkpoint.CheckpointException;
import org.apache.flink.runtime.checkpoint.channel.ChannelStateWriter;
import org.apache.flink.runtime.event.TaskEvent;
import org.apache.flink.runtime.execution.CancelTaskException;
import org.apache.flink.runtime.io.network.TaskEventPublisher;
import org.apache.flink.runtime.io.network.api.CheckpointBarrier;
import org.apache.flink.runtime.io.network.buffer.Buffer;
import org.apache.flink.runtime.io.network.buffer.FileRegionBuffer;
import org.apache.flink.runtime.io.network.logger.NetworkActionsLogger;
import org.apache.flink.runtime.io.network.partition.BufferAvailabilityListener;
import org.apache.flink.runtime.io.network.partition.PartitionNotFoundException;
import org.apache.flink.runtime.io.network.partition.ResultPartitionID;
import org.apache.flink.runtime.io.network.partition.ResultPartitionManager;
import org.apache.flink.runtime.io.network.partition.ResultSubpartition.BufferAndBacklog;
import org.apache.flink.runtime.io.network.partition.ResultSubpartitionView;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.io.IOException;
import java.util.Collections;
import java.util.Optional;
import java.util.Timer;
import java.util.TimerTask;

import static org.apache.flink.util.Preconditions.checkNotNull;
import static org.apache.flink.util.Preconditions.checkState;

/**
 * 如果一个 InputChannel 和其消费的上游 ResultPartition 所属 Task 都在同一个 TaskManager 中运行， 那么它们之间的数据交换就在同一个 JVM
 * 进程内不同线程之间进行，无需通过网络交换。
 *
 * <p>LocalInputChannel 实现了 InputChannel 接口，同时也实现了 BufferAvailabilityListener 接口。
 *
 * <p>LocalInputChannel 通过 ResultPartitionManager 请求创建和指定 ResultSubparition 关联的
 * ResultSubparitionView， 并以自身作为 ResultSubparitionView 的回调。 这样，一旦 ResultSubparition
 * 有数据产出时，ResultSubparitionView 会得到通知， 同时 LocalInputChannel 的回调函数也会被调用，
 * 这样消费者这一端就可以及时获取到数据的生产情况，从而及时地去消费数据。
 *
 * <p>ResultSubpartition 中的 buffer 可以通过 ResultSubpartitionView 进行消费
 *
 * <p>An input channel, which requests a local subpartition.
 */
public class LocalInputChannel extends InputChannel implements BufferAvailabilityListener {

    private static final Logger LOG = LoggerFactory.getLogger(LocalInputChannel.class);

    // ------------------------------------------------------------------------

    private final Object requestLock = new Object();

    /**
     * 分区管理器,里面存放着所有的分区信息 partitionManager = {ResultPartitionManager@7381} registeredPartitions =
     * {HashMap@7405} size = 8 {ResultPartitionID@7416}
     * "6b3e5e999219f9532114514c4bdbb773#0@51ad11521e991efaad6349cdf2accda7" ->
     * {PipelinedResultPartition@7417} "PipelinedResultPartition
     * 6b3e5e999219f9532114514c4bdbb773#0@51ad11521e991efaad6349cdf2accda7 [PIPELINED_BOUNDED, 1
     * subpartitions, 1 pending consumptions]" {ResultPartitionID@7418}
     * "6b3e5e999219f9532114514c4bdbb773#2@aecbd0682c0973976efe563eca747cc0" ->
     * {PipelinedResultPartition@7419} "PipelinedResultPartition
     * 6b3e5e999219f9532114514c4bdbb773#2@aecbd0682c0973976efe563eca747cc0 [PIPELINED_BOUNDED, 1
     * subpartitions, 1 pending consumptions]" {ResultPartitionID@7420}
     * "e07667949eeb5fe115288459d1d137f1#1@0ef8b3d70af60be8633af8af4e1c0698" ->
     * {PipelinedResultPartition@7421} "PipelinedResultPartition
     * e07667949eeb5fe115288459d1d137f1#1@0ef8b3d70af60be8633af8af4e1c0698 [PIPELINED_BOUNDED, 4
     * subpartitions, 4 pending consumptions]" {ResultPartitionID@7422}
     * "e07667949eeb5fe115288459d1d137f1#2@5e3aaeed65818bcfeb1485d0fd22d1ac" ->
     * {PipelinedResultPartition@7423} "PipelinedResultPartition
     * e07667949eeb5fe115288459d1d137f1#2@5e3aaeed65818bcfeb1485d0fd22d1ac [PIPELINED_BOUNDED, 4
     * subpartitions, 4 pending consumptions]" {ResultPartitionID@7424}
     * "e07667949eeb5fe115288459d1d137f1#3@30e457019371f01a403bd06cf3041eeb" ->
     * {PipelinedResultPartition@7425} "PipelinedResultPartition
     * e07667949eeb5fe115288459d1d137f1#3@30e457019371f01a403bd06cf3041eeb [PIPELINED_BOUNDED, 4
     * subpartitions, 4 pending consumptions]" {ResultPartitionID@7426}
     * "e07667949eeb5fe115288459d1d137f1#0@bfbc34d8314d506a39528d9c86f16859" ->
     * {PipelinedResultPartition@7427} "PipelinedResultPartition
     * e07667949eeb5fe115288459d1d137f1#0@bfbc34d8314d506a39528d9c86f16859 [PIPELINED_BOUNDED, 4
     * subpartitions, 4 pending consumptions]" {ResultPartitionID@7428}
     * "6b3e5e999219f9532114514c4bdbb773#1@bcf3be98463b672ea899cee1290423a2" ->
     * {PipelinedResultPartition@7429} "PipelinedResultPartition
     * 6b3e5e999219f9532114514c4bdbb773#1@bcf3be98463b672ea899cee1290423a2 [PIPELINED_BOUNDED, 1
     * subpartitions, 1 pending consumptions]" {ResultPartitionID@7379}
     * "5eba1007ad48ad2243891e1eff29c32b#0@db0c587a67c31a83cff5fd8be9496e5d" ->
     * {PipelinedResultPartition@7371} "PipelinedResultPartition
     * 5eba1007ad48ad2243891e1eff29c32b#0@db0c587a67c31a83cff5fd8be9496e5d [PIPELINED_BOUNDED, 4
     * subpartitions, 4 pending consumptions]" The local partition manager.
     */
    private final ResultPartitionManager partitionManager;

    /**
     * taskEventPublisher = {TaskEventDispatcher@6289}
     *
     * <p>Task event dispatcher for backwards events.
     */
    private final TaskEventPublisher taskEventPublisher;

    /** The consumed subpartition. */
    @Nullable private volatile ResultSubpartitionView subpartitionView;

    private volatile boolean isReleased;

    private final ChannelStatePersister channelStatePersister;

    public LocalInputChannel(
            SingleInputGate inputGate,
            int channelIndex,
            ResultPartitionID partitionId,
            ResultPartitionManager partitionManager,
            TaskEventPublisher taskEventPublisher,
            Counter numBytesIn,
            Counter numBuffersIn) {

        this(
                inputGate,
                channelIndex,
                partitionId,
                partitionManager,
                taskEventPublisher,
                0,
                0,
                numBytesIn,
                numBuffersIn,
                ChannelStateWriter.NO_OP);
    }

    public LocalInputChannel(
            SingleInputGate inputGate,
            int channelIndex,
            ResultPartitionID partitionId,
            ResultPartitionManager partitionManager,
            TaskEventPublisher taskEventPublisher,
            int initialBackoff,
            int maxBackoff,
            Counter numBytesIn,
            Counter numBuffersIn,
            ChannelStateWriter stateWriter) {

        super(
                inputGate,
                channelIndex,
                partitionId,
                initialBackoff,
                maxBackoff,
                numBytesIn,
                numBuffersIn);

        this.partitionManager = checkNotNull(partitionManager);
        this.taskEventPublisher = checkNotNull(taskEventPublisher);
        this.channelStatePersister = new ChannelStatePersister(stateWriter, getChannelInfo());
    }

    // ------------------------------------------------------------------------
    // Consume
    // ------------------------------------------------------------------------

    public void checkpointStarted(CheckpointBarrier barrier) throws CheckpointException {
        channelStatePersister.startPersisting(barrier.getId(), Collections.emptyList());
    }

    public void checkpointStopped(long checkpointId) {
        channelStatePersister.stopPersisting(checkpointId);
    }

    // 请求消费对应的子分区
    @Override
    protected void requestSubpartition(int subpartitionIndex) throws IOException {

        boolean retriggerRequest = false;
        boolean notifyDataAvailable = false;

        // The lock is required to request only once in the presence of retriggered requests.
        synchronized (requestLock) {
            checkState(!isReleased, "LocalInputChannel has been released already");

            if (subpartitionView == null) {
                LOG.debug(
                        "{}: Requesting LOCAL subpartition {} of partition {}. {}",
                        this,
                        subpartitionIndex,
                        partitionId,
                        channelStatePersister);

                try {
                    // Local，无需网络通信，通过 ResultPartitionManager 创建一个 ResultSubpartitionView
                    // LocalInputChannel 实现了 BufferAvailabilityListener
                    // 在有数据时会得到通知，notifyDataAvailable 会被调用，
                    // 进而将当前 channel 加到 InputGate 的可用 Channel 队列中
                    ResultSubpartitionView subpartitionView =
                            partitionManager.createSubpartitionView(
                                    partitionId, subpartitionIndex, this);

                    if (subpartitionView == null) {
                        throw new IOException("Error requesting subpartition.");
                    }

                    // make the subpartition view visible
                    this.subpartitionView = subpartitionView;

                    // check if the channel was released in the meantime
                    if (isReleased) {
                        subpartitionView.releaseAllResources();
                        this.subpartitionView = null;
                    } else {
                        notifyDataAvailable = true;
                    }
                } catch (PartitionNotFoundException notFound) {
                    if (increaseBackoff()) {
                        retriggerRequest = true;
                    } else {
                        throw notFound;
                    }
                }
            }
        }

        if (notifyDataAvailable) {
            // 通知有数据到达...
            notifyDataAvailable();
        }

        // Do this outside of the lock scope as this might lead to a
        // deadlock with a concurrent release of the channel via the
        // input gate.
        if (retriggerRequest) {
            inputGate.retriggerPartitionRequest(partitionId.getPartitionId());
        }
    }

    /** Retriggers a subpartition request. */
    void retriggerSubpartitionRequest(Timer timer, final int subpartitionIndex) {
        synchronized (requestLock) {
            checkState(subpartitionView == null, "already requested partition");

            timer.schedule(
                    new TimerTask() {
                        @Override
                        public void run() {
                            try {
                                requestSubpartition(subpartitionIndex);
                            } catch (Throwable t) {
                                setError(t);
                            }
                        }
                    },
                    getCurrentBackoff());
        }
    }

    /**
     * //读取数据，借助 ResultSubparitionView 消费 ResultSubparition 中的数据
     *
     * @return
     * @throws IOException
     */
    @Override
    Optional<BufferAndAvailability> getNextBuffer() throws IOException {
        checkError();

        // 获取requestSubpartition方法得到的subpartitionView
        ResultSubpartitionView subpartitionView = this.subpartitionView;

        // 如果没有获取到subpartitionView，需要再次检查subpartitionView
        // 如果此时另一线程正在调用requestSubpartition方法，checkAndWaitForSubpartitionView方法会被阻塞
        // 等待requestSubpartition执行完毕
        if (subpartitionView == null) {
            // There is a possible race condition between writing a EndOfPartitionEvent (1) and
            // flushing (3) the Local
            // channel on the sender side, and reading EndOfPartitionEvent (2) and processing flush
            // notification (4). When
            // they happen in that order (1 - 2 - 3 - 4), flush notification can re-enqueue
            // LocalInputChannel after (or
            // during) it was released during reading the EndOfPartitionEvent (2).
            if (isReleased) {
                return Optional.empty();
            }

            // this can happen if the request for the partition was triggered asynchronously
            // by the time trigger
            // would be good to avoid that, by guaranteeing that the requestPartition() and
            // getNextBuffer() always come from the same thread
            // we could do that by letting the timer insert a special "requesting channel" into the
            // input gate's queue
            subpartitionView = checkAndWaitForSubpartitionView();
        }

        // 通过 ResultSubparitionView 获取
        BufferAndBacklog next = subpartitionView.getNextBuffer();

        if (next == null) {
            if (subpartitionView.isReleased()) {
                throw new CancelTaskException(
                        "Consumed partition " + subpartitionView + " has been released.");
            } else {
                return Optional.empty();
            }
        }

        Buffer buffer = next.buffer();

        if (buffer instanceof FileRegionBuffer) {
            buffer = ((FileRegionBuffer) buffer).readInto(inputGate.getUnpooledSegment());
        }

        // 更新已读取字节数
        numBytesIn.inc(buffer.getSize());

        // 更新以读取缓存数
        numBuffersIn.inc();
        if (buffer.getDataType().hasPriority()) {
            channelStatePersister.checkForBarrier(buffer);
        } else {
            channelStatePersister.maybePersist(buffer);
        }
        NetworkActionsLogger.traceInput(
                "LocalInputChannel#getNextBuffer",
                buffer,
                inputGate.getOwningTaskName(),
                channelInfo,
                channelStatePersister,
                next.getSequenceNumber());
        return Optional.of(
                new BufferAndAvailability(
                        buffer,
                        next.getNextDataType(),
                        next.buffersInBacklog(),
                        next.getSequenceNumber()));
    }

    // 回调，在 ResultSubparition 通知 ResultSubparitionView 有数据可供消费，
    @Override
    public void notifyDataAvailable() {
        // LocalInputChannel 通知 InputGate
        notifyChannelNonEmpty();
    }

    private ResultSubpartitionView checkAndWaitForSubpartitionView() {
        // synchronizing on the request lock means this blocks until the asynchronous request
        // for the partition view has been completed
        // by then the subpartition view is visible or the channel is released
        synchronized (requestLock) {
            checkState(!isReleased, "released");
            checkState(
                    subpartitionView != null,
                    "Queried for a buffer before requesting the subpartition.");
            return subpartitionView;
        }
    }

    @Override
    public void resumeConsumption() {
        checkState(!isReleased, "Channel released.");

        subpartitionView.resumeConsumption();

        if (subpartitionView.isAvailable(Integer.MAX_VALUE)) {
            notifyChannelNonEmpty();
        }
    }

    // ------------------------------------------------------------------------
    // Task events
    // ------------------------------------------------------------------------

    @Override
    void sendTaskEvent(TaskEvent event) throws IOException {
        checkError();
        checkState(
                subpartitionView != null,
                "Tried to send task event to producer before requesting the subpartition.");

        // 事件分发
        if (!taskEventPublisher.publish(partitionId, event)) {
            throw new IOException(
                    "Error while publishing event "
                            + event
                            + " to producer. The producer could not be found.");
        }
    }

    // ------------------------------------------------------------------------
    // Life cycle
    // ------------------------------------------------------------------------

    @Override
    boolean isReleased() {
        return isReleased;
    }

    /** Releases the partition reader. */
    @Override
    void releaseAllResources() throws IOException {
        if (!isReleased) {
            isReleased = true;

            ResultSubpartitionView view = subpartitionView;
            if (view != null) {
                view.releaseAllResources();
                subpartitionView = null;
            }
        }
    }

    @Override
    public int unsynchronizedGetNumberOfQueuedBuffers() {
        ResultSubpartitionView view = subpartitionView;

        if (view != null) {
            return view.unsynchronizedGetNumberOfQueuedBuffers();
        }

        return 0;
    }

    @Override
    public String toString() {
        return "LocalInputChannel [" + partitionId + "]";
    }

    // ------------------------------------------------------------------------
    // Getter
    // ------------------------------------------------------------------------

    @VisibleForTesting
    ResultSubpartitionView getSubpartitionView() {
        return subpartitionView;
    }
}
