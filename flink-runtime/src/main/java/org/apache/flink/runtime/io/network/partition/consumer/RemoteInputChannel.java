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
import org.apache.flink.runtime.checkpoint.CheckpointFailureReason;
import org.apache.flink.runtime.checkpoint.channel.ChannelStateWriter;
import org.apache.flink.runtime.event.AbstractEvent;
import org.apache.flink.runtime.event.TaskEvent;
import org.apache.flink.runtime.execution.CancelTaskException;
import org.apache.flink.runtime.io.network.ConnectionID;
import org.apache.flink.runtime.io.network.ConnectionManager;
import org.apache.flink.runtime.io.network.PartitionRequestClient;
import org.apache.flink.runtime.io.network.api.CheckpointBarrier;
import org.apache.flink.runtime.io.network.api.EventAnnouncement;
import org.apache.flink.runtime.io.network.api.serialization.EventSerializer;
import org.apache.flink.runtime.io.network.buffer.Buffer;
import org.apache.flink.runtime.io.network.buffer.Buffer.DataType;
import org.apache.flink.runtime.io.network.buffer.BufferProvider;
import org.apache.flink.runtime.io.network.logger.NetworkActionsLogger;
import org.apache.flink.runtime.io.network.partition.PartitionNotFoundException;
import org.apache.flink.runtime.io.network.partition.PrioritizedDeque;
import org.apache.flink.runtime.io.network.partition.ResultPartitionID;

import org.apache.flink.shaded.guava18.com.google.common.collect.Iterators;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.apache.flink.util.Preconditions.checkNotNull;
import static org.apache.flink.util.Preconditions.checkState;

/**
 * 数据接收端主要是指RemoteInputChannel。 该channel用来接受和处理从其他节点读取到的数据。 和RemoteInputChanel对应的是 LocalInputChannel
 * ，负责读取本地的subpartition，不需要使用接收端缓存。
 *
 * <p>RemoteInputChannel 请求远端的 ResultSubpartition 会创建一个 PartitionRequestClient， 并通过 Netty 发送
 * PartitionRequest 请求， 这时会带上当前 InputChannel 的 id 和初始的 credit 信息：
 *
 * <p>CreditBasedPartitionRequestClientHandler 从网络中读取数据后交给 RemoteInputChannel， RemoteInputChannel
 * 会将接收到的加入队列中， 并根据生产端的堆积申请 floating buffer
 *
 * <p>An input channel, which requests a remote partition queue.
 */
public class RemoteInputChannel extends InputChannel {
    private static final Logger LOG = LoggerFactory.getLogger(RemoteInputChannel.class);

    private static final int NONE = -1;

    /** ID to distinguish this channel from other channels sharing the same TCP connection. */
    private final InputChannelID id = new InputChannelID();

    /** The connection to use to request the remote partition. */
    private final ConnectionID connectionId;

    /** The connection manager to use connect to the remote partition provider. */
    private final ConnectionManager connectionManager;

    /**
     * The received buffers. Received buffers are enqueued by the network I/O thread and the queue
     * is consumed by the receiving task thread.
     */
    private final PrioritizedDeque<SequenceBuffer> receivedBuffers = new PrioritizedDeque<>();

    /**
     * Flag indicating whether this channel has been released. Either called by the receiving task
     * thread or the task manager actor.
     */
    private final AtomicBoolean isReleased = new AtomicBoolean();

    /**
     * RemoteInputChannel 请求远端的 ResultSubpartition， 会创建一个 PartitionRequestClient， 并通过 Netty 发送
     * PartitionRequest 请求， 这时会带上当前 InputChannel 的 id 和初始的 credit 信息
     *
     * <p>Client to establish a (possibly shared) TCP connection and request the partition.
     */
    private volatile PartitionRequestClient partitionRequestClient;

    /** The next expected sequence number for the next buffer. */
    private int expectedSequenceNumber = 0;

    /** 初始化信用值 The initial number of exclusive buffers assigned to this channel. */
    private final int initialCredit;

    /** The number of available buffers that have not been announced to the producer yet. */
    private final AtomicInteger unannouncedCredit = new AtomicInteger(0);

    private final BufferManager bufferManager;

    @GuardedBy("receivedBuffers")
    private int lastBarrierSequenceNumber = NONE;

    @GuardedBy("receivedBuffers")
    private long lastBarrierId = NONE;

    private final ChannelStatePersister channelStatePersister;

    public RemoteInputChannel(
            SingleInputGate inputGate,
            int channelIndex,
            ResultPartitionID partitionId,
            ConnectionID connectionId,
            ConnectionManager connectionManager,
            int initialBackOff,
            int maxBackoff,
            int networkBuffersPerChannel,
            Counter numBytesIn,
            Counter numBuffersIn,
            ChannelStateWriter stateWriter) {

        super(
                inputGate,
                channelIndex,
                partitionId,
                initialBackOff,
                maxBackoff,
                numBytesIn,
                numBuffersIn);

        this.initialCredit = networkBuffersPerChannel;
        this.connectionId = checkNotNull(connectionId);
        this.connectionManager = checkNotNull(connectionManager);

        // 构建BufferManager , 每一个 RemoteinputChannel只有一个BufferManager用于内存管理
        // 但是实际上 内存的最终管理者MemorySegmentProvider globalPool 依旧是NetworkBufferPool级别的.
        this.bufferManager = new BufferManager(inputGate.getMemorySegmentProvider(), this, 0);
        this.channelStatePersister = new ChannelStatePersister(stateWriter, getChannelInfo());
    }

    @VisibleForTesting
    void setExpectedSequenceNumber(int expectedSequenceNumber) {
        this.expectedSequenceNumber = expectedSequenceNumber;
    }

    /**
     * Setup includes assigning exclusive buffers to this input channel, and this method should be
     * called only once after this input channel is created.
     */
    @Override
    void setup() throws IOException {
        checkState(
                bufferManager.unsynchronizedGetAvailableExclusiveBuffers() == 0,
                "Bug in input channel setup logic: exclusive buffers have already been set for this input channel.");

        // 根据初始的信用值  申请 MemorySegment .
        bufferManager.requestExclusiveBuffers(initialCredit);
    }

    // ------------------------------------------------------------------------
    // Consume
    // ------------------------------------------------------------------------

    /** Requests a remote subpartition. */
    @VisibleForTesting
    @Override
    public void requestSubpartition(int subpartitionIndex)
            throws IOException, InterruptedException {

        if (partitionRequestClient == null) {
            LOG.debug(
                    "{}: Requesting REMOTE subpartition {} of partition {}. {}",
                    this,
                    subpartitionIndex,
                    partitionId,
                    channelStatePersister);
            // Create a client and request the partition
            try {

                // 构建一个client, 请求partition
                // REMOTE，需要网络通信，使用 Netty 建立网络
                // 通过 ConnectionManager 来建立连接：创建 PartitionRequestClient，通过 PartitionRequestClient
                // 发起请求
                partitionRequestClient =
                        connectionManager.createPartitionRequestClient(connectionId);
            } catch (IOException e) {
                // IOExceptions indicate that we could not open a connection to the remote
                // TaskExecutor
                throw new PartitionConnectionException(partitionId, e);
            }

            // 请求分区，通过 netty 发起请求
            partitionRequestClient.requestSubpartition(partitionId, subpartitionIndex, this, 0);
        }
    }

    /** Retriggers a remote subpartition request. */
    void retriggerSubpartitionRequest(int subpartitionIndex) throws IOException {
        checkPartitionRequestQueueInitialized();

        if (increaseBackoff()) {
            partitionRequestClient.requestSubpartition(
                    partitionId, subpartitionIndex, this, getCurrentBackoff());
        } else {
            failPartitionRequest();
        }
    }

    @Override
    Optional<BufferAndAvailability> getNextBuffer() throws IOException {
        checkPartitionRequestQueueInitialized();

        final SequenceBuffer next;
        final DataType nextDataType;

        // 从receivedBuffers队列中获取buffer
        synchronized (receivedBuffers) {
            next = receivedBuffers.poll();
            nextDataType =
                    receivedBuffers.peek() != null
                            ? receivedBuffers.peek().buffer.getDataType()
                            : DataType.NONE;
        }

        if (next == null) {
            if (isReleased.get()) {
                throw new CancelTaskException(
                        "Queried for a buffer after channel has been released.");
            }
            return Optional.empty();
        }

        numBytesIn.inc(next.buffer.getSize());
        numBuffersIn.inc();
        return Optional.of(
                new BufferAndAvailability(next.buffer, nextDataType, 0, next.sequenceNumber));
    }

    // ------------------------------------------------------------------------
    // Task events
    // ------------------------------------------------------------------------

    @Override
    void sendTaskEvent(TaskEvent event) throws IOException {
        checkState(
                !isReleased.get(),
                "Tried to send task event to producer after channel has been released.");
        checkPartitionRequestQueueInitialized();

        partitionRequestClient.sendTaskEvent(partitionId, event, this);
    }

    // ------------------------------------------------------------------------
    // Life cycle
    // ------------------------------------------------------------------------

    @Override
    public boolean isReleased() {
        return isReleased.get();
    }

    /** Releases all exclusive and floating buffers, closes the partition request client. */
    @Override
    void releaseAllResources() throws IOException {
        if (isReleased.compareAndSet(false, true)) {

            final ArrayDeque<Buffer> releasedBuffers;
            synchronized (receivedBuffers) {
                releasedBuffers =
                        receivedBuffers.stream()
                                .map(sb -> sb.buffer)
                                .collect(Collectors.toCollection(ArrayDeque::new));
                receivedBuffers.clear();
            }
            bufferManager.releaseAllBuffers(releasedBuffers);

            // The released flag has to be set before closing the connection to ensure that
            // buffers received concurrently with closing are properly recycled.
            if (partitionRequestClient != null) {
                partitionRequestClient.close(this);
            } else {
                connectionManager.closeOpenChannelConnections(connectionId);
            }
        }
    }

    private void failPartitionRequest() {
        setError(new PartitionNotFoundException(partitionId));
    }

    @Override
    public String toString() {
        return "RemoteInputChannel [" + partitionId + " at " + connectionId + "]";
    }

    // ------------------------------------------------------------------------
    // Credit-based
    // ------------------------------------------------------------------------

    /**
     * Enqueue this input channel in the pipeline for notifying the producer of unannounced credit.
     */
    private void notifyCreditAvailable() throws IOException {
        checkPartitionRequestQueueInitialized();
        // 通知当前 channel 有新的 credit
        partitionRequestClient.notifyCreditAvailable(this);
    }

    @VisibleForTesting
    public int getNumberOfAvailableBuffers() {
        return bufferManager.getNumberOfAvailableBuffers();
    }

    @VisibleForTesting
    public int getNumberOfRequiredBuffers() {
        return bufferManager.unsynchronizedGetNumberOfRequiredBuffers();
    }

    @VisibleForTesting
    public int getSenderBacklog() {
        return getNumberOfRequiredBuffers() - initialCredit;
    }

    @VisibleForTesting
    boolean isWaitingForFloatingBuffers() {
        return bufferManager.unsynchronizedIsWaitingForFloatingBuffers();
    }

    @VisibleForTesting
    public Buffer getNextReceivedBuffer() {
        final SequenceBuffer sequenceBuffer = receivedBuffers.poll();
        return sequenceBuffer != null ? sequenceBuffer.buffer : null;
    }

    @VisibleForTesting
    BufferManager getBufferManager() {
        return bufferManager;
    }

    @VisibleForTesting
    PartitionRequestClient getPartitionRequestClient() {
        return partitionRequestClient;
    }

    /**
     * The unannounced credit is increased by the given amount and might notify increased credit to
     * the producer.
     */
    @Override
    public void notifyBufferAvailable(int numAvailableBuffers) throws IOException {
        if (numAvailableBuffers > 0 && unannouncedCredit.getAndAdd(numAvailableBuffers) == 0) {
            notifyCreditAvailable();
        }
    }

    @Override
    public void resumeConsumption() throws IOException {
        checkState(!isReleased.get(), "Channel released.");
        checkPartitionRequestQueueInitialized();

        // notifies the producer that this channel is ready to
        // unblock from checkpoint and resume data consumption
        partitionRequestClient.resumeConsumption(this);
    }

    // ------------------------------------------------------------------------
    // Network I/O notifications (called by network I/O thread)
    // ------------------------------------------------------------------------

    /**
     * Gets the currently unannounced credit.
     *
     * @return Credit which was not announced to the sender yet.
     */
    public int getUnannouncedCredit() {
        return unannouncedCredit.get();
    }

    /**
     * Gets the unannounced credit and resets it to <tt>0</tt> atomically.
     *
     * @return Credit which was not announced to the sender yet.
     */
    public int getAndResetUnannouncedCredit() {
        return unannouncedCredit.getAndSet(0);
    }

    /**
     * Gets the current number of received buffers which have not been processed yet.
     *
     * @return Buffers queued for processing.
     */
    public int getNumberOfQueuedBuffers() {
        synchronized (receivedBuffers) {
            return receivedBuffers.size();
        }
    }

    @Override
    public int unsynchronizedGetNumberOfQueuedBuffers() {
        return Math.max(0, receivedBuffers.size());
    }

    public int unsynchronizedGetExclusiveBuffersUsed() {
        return Math.max(
                0, initialCredit - bufferManager.unsynchronizedGetAvailableExclusiveBuffers());
    }

    public int unsynchronizedGetFloatingBuffersAvailable() {
        return Math.max(0, bufferManager.unsynchronizedGetFloatingBuffersAvailable());
    }

    public InputChannelID getInputChannelId() {
        return id;
    }

    public int getInitialCredit() {
        return initialCredit;
    }

    public BufferProvider getBufferProvider() throws IOException {
        if (isReleased.get()) {
            return null;
        }

        return inputGate.getBufferProvider();
    }

    /**
     * 该方法从bufferQueue中拿到一个buffer并返回。
     *
     * <p>Requests buffer from input channel directly for receiving network data. It should always
     * return an available buffer in credit-based mode unless the channel has been released.
     *
     * @return The available buffer.
     */
    @Nullable
    public Buffer requestBuffer() {
        return bufferManager.requestBuffer();
    }

    /**
     * backlog 是发送端的堆积 的 buffer 数量， 如果 bufferQueue 中 buffer 的数量不足，就去须从 LocalBufferPool 中请求 floating
     * buffer 在请求了新的 buffer 后，通知生产者有 credit 可用
     *
     * <p>根据backlog（积压的数量）提前分配内存， 如果backlog加上初始的credit大于可用buffer数，需要分配浮动buffer。
     *
     * <p>Receives the backlog from the producer's buffer response. If the number of available
     * buffers is less than backlog + initialCredit, it will request floating buffers from the
     * buffer manager, and then notify unannounced credits to the producer.
     *
     * @param backlog The number of unsent buffers in the producer's sub partition.
     */
    void onSenderBacklog(int backlog) throws IOException {

        // 请求浮动内存
        int numRequestedBuffers = bufferManager.requestFloatingBuffers(backlog + initialCredit);

        if (numRequestedBuffers > 0 && unannouncedCredit.getAndAdd(numRequestedBuffers) == 0) {

            notifyCreditAvailable();
        }
    }

    /**
     * 接收到远程 ResultSubpartition 发送的 Buffer
     *
     * @param buffer
     * @param sequenceNumber
     * @param backlog
     * @throws IOException
     */
    public void onBuffer(Buffer buffer, int sequenceNumber, int backlog) throws IOException {

        // 是否需要回收此buffer
        boolean recycleBuffer = true;

        try {

            // 检查sequenceNumber
            // 序号需要匹配
            if (expectedSequenceNumber != sequenceNumber) {
                onError(new BufferReorderingException(expectedSequenceNumber, sequenceNumber));
                return;
            }

            final boolean wasEmpty;
            boolean firstPriorityEvent = false;

            synchronized (receivedBuffers) {
                NetworkActionsLogger.traceInput(
                        "RemoteInputChannel#onBuffer",
                        buffer,
                        inputGate.getOwningTaskName(),
                        channelInfo,
                        channelStatePersister,
                        sequenceNumber);
                // Similar to notifyBufferAvailable(), make sure that we never add a buffer
                // after releaseAllResources() released all buffers from receivedBuffers
                // (see above for details).
                if (isReleased.get()) {
                    return;
                }

                // 判断添加buffer之前的队列是否为空
                wasEmpty = receivedBuffers.isEmpty();

                SequenceBuffer sequenceBuffer = new SequenceBuffer(buffer, sequenceNumber);
                DataType dataType = buffer.getDataType();
                if (dataType.hasPriority()) {
                    firstPriorityEvent = addPriorityBuffer(sequenceBuffer);
                } else {
                    //  把已经填充了数据的内存块放入receivedBuffers队列
                    //  后续的operator可以通过 StreamTaskNetworkInput 的实现类 读取InputChannel中缓存的数据。
                    // [比如 StreamOneInputProcessor]
                    receivedBuffers.add(sequenceBuffer);
                    channelStatePersister.maybePersist(buffer);
                    if (dataType.requiresAnnouncement()) {
                        // !!!! todo 没想明白呢 .. barrier ???
                        firstPriorityEvent = addPriorityBuffer(announce(sequenceBuffer));
                    }
                }

                // 增加SequenceNumber
                ++expectedSequenceNumber;
            }
            // 已接收到数据，缓存不需要回收
            recycleBuffer = false;

            if (firstPriorityEvent) {

                notifyPriorityEvent(sequenceNumber);
            }
            // 如果添加buffer之前的队列为空，需要通知对应的inputGate，现在已经有数据了（不为空
            if (wasEmpty) {
                // 通知 InputGate，当前 channel 有新数据
                notifyChannelNonEmpty();
            }

            if (backlog >= 0) {
                // 根据客户端的积压申请float buffer
                onSenderBacklog(backlog);
            }
        } finally {
            // 回收buffer
            if (recycleBuffer) {
                buffer.recycleBuffer();
            }
        }
    }

    /** @return {@code true} if this was first priority buffer added. */
    private boolean addPriorityBuffer(SequenceBuffer sequenceBuffer) throws IOException {
        receivedBuffers.addPriorityElement(sequenceBuffer);
        channelStatePersister
                .checkForBarrier(sequenceBuffer.buffer)
                .filter(id -> id > lastBarrierId)
                .ifPresent(
                        id -> {
                            lastBarrierId = id;
                            lastBarrierSequenceNumber = sequenceBuffer.sequenceNumber;
                        });
        return receivedBuffers.getNumPriorityElements() == 1;
    }

    private SequenceBuffer announce(SequenceBuffer sequenceBuffer) throws IOException {
        checkState(
                !sequenceBuffer.buffer.isBuffer(),
                "Only a CheckpointBarrier can be announced but found %s",
                sequenceBuffer.buffer);
        AbstractEvent event =
                EventSerializer.fromBuffer(sequenceBuffer.buffer, getClass().getClassLoader());
        checkState(
                event instanceof CheckpointBarrier,
                "Only a CheckpointBarrier can be announced but found %s",
                sequenceBuffer.buffer);
        CheckpointBarrier barrier = (CheckpointBarrier) event;
        return new SequenceBuffer(
                EventSerializer.toBuffer(
                        new EventAnnouncement(barrier, sequenceBuffer.sequenceNumber), true),
                sequenceBuffer.sequenceNumber);
    }

    /**
     * Spills all queued buffers on checkpoint start. If barrier has already been received (and
     * reordered), spill only the overtaken buffers.
     */
    public void checkpointStarted(CheckpointBarrier barrier) throws CheckpointException {
        synchronized (receivedBuffers) {
            if (barrier.getId() < lastBarrierId) {
                throw new CheckpointException(
                        String.format(
                                "Sequence number for checkpoint %d is not known (it was likely been overwritten by a newer checkpoint %d)",
                                barrier.getId(), lastBarrierId),
                        CheckpointFailureReason
                                .CHECKPOINT_SUBSUMED); // currently, at most one active unaligned
                // checkpoint is possible
            } else if (barrier.getId() > lastBarrierId) {
                // This channel has received some obsolete barrier, older compared to the
                // checkpointId
                // which we are processing right now, and we should ignore that obsoleted checkpoint
                // barrier sequence number.
                resetLastBarrier();
            }

            channelStatePersister.startPersisting(
                    barrier.getId(), getInflightBuffersUnsafe(barrier.getId()));
        }
    }

    public void checkpointStopped(long checkpointId) {
        synchronized (receivedBuffers) {
            channelStatePersister.stopPersisting(checkpointId);
            if (lastBarrierId == checkpointId) {
                resetLastBarrier();
            }
        }
    }

    @VisibleForTesting
    List<Buffer> getInflightBuffers(long checkpointId) {
        synchronized (receivedBuffers) {
            return getInflightBuffersUnsafe(checkpointId);
        }
    }

    /**
     * Returns a list of buffers, checking the first n non-priority buffers, and skipping all
     * events.
     */
    private List<Buffer> getInflightBuffersUnsafe(long checkpointId) {
        assert Thread.holdsLock(receivedBuffers);

        checkState(checkpointId == lastBarrierId || lastBarrierId == NONE);

        final List<Buffer> inflightBuffers = new ArrayList<>();
        Iterator<SequenceBuffer> iterator = receivedBuffers.iterator();
        // skip all priority events (only buffers are stored anyways)
        Iterators.advance(iterator, receivedBuffers.getNumPriorityElements());

        while (iterator.hasNext()) {
            SequenceBuffer sequenceBuffer = iterator.next();
            if (sequenceBuffer.buffer.isBuffer()) {
                if (shouldBeSpilled(sequenceBuffer.sequenceNumber)) {
                    inflightBuffers.add(sequenceBuffer.buffer.retainBuffer());
                } else {
                    break;
                }
            }
        }

        return inflightBuffers;
    }

    private void resetLastBarrier() {
        lastBarrierId = NONE;
        lastBarrierSequenceNumber = NONE;
    }

    /**
     * @return if given {@param sequenceNumber} should be spilled given {@link
     *     #lastBarrierSequenceNumber}. We might not have yet received {@link CheckpointBarrier} and
     *     we might need to spill everything. If we have already received it, there is a bit nasty
     *     corner case of {@link SequenceBuffer#sequenceNumber} overflowing that needs to be handled
     *     as well.
     */
    private boolean shouldBeSpilled(int sequenceNumber) {
        if (lastBarrierSequenceNumber == NONE) {
            return true;
        }
        checkState(
                receivedBuffers.size() < Integer.MAX_VALUE / 2,
                "Too many buffers for sequenceNumber overflow detection code to work correctly");

        boolean possibleOverflowAfterOvertaking = Integer.MAX_VALUE / 2 < lastBarrierSequenceNumber;
        boolean possibleOverflowBeforeOvertaking =
                lastBarrierSequenceNumber < -Integer.MAX_VALUE / 2;

        if (possibleOverflowAfterOvertaking) {
            return sequenceNumber < lastBarrierSequenceNumber && sequenceNumber > 0;
        } else if (possibleOverflowBeforeOvertaking) {
            return sequenceNumber < lastBarrierSequenceNumber || sequenceNumber > 0;
        } else {
            return sequenceNumber < lastBarrierSequenceNumber;
        }
    }

    public void onEmptyBuffer(int sequenceNumber, int backlog) throws IOException {
        boolean success = false;

        synchronized (receivedBuffers) {
            if (!isReleased.get()) {
                if (expectedSequenceNumber == sequenceNumber) {
                    expectedSequenceNumber++;
                    success = true;
                } else {
                    onError(new BufferReorderingException(expectedSequenceNumber, sequenceNumber));
                }
            }
        }

        if (success && backlog >= 0) {
            onSenderBacklog(backlog);
        }
    }

    public void onFailedPartitionRequest() {
        inputGate.triggerPartitionStateCheck(partitionId);
    }

    public void onError(Throwable cause) {
        setError(cause);
    }

    private void checkPartitionRequestQueueInitialized() throws IOException {
        checkError();
        checkState(
                partitionRequestClient != null,
                "Bug: partitionRequestClient is not initialized before processing data and no error is detected.");
    }

    private static class BufferReorderingException extends IOException {

        private static final long serialVersionUID = -888282210356266816L;

        private final int expectedSequenceNumber;

        private final int actualSequenceNumber;

        BufferReorderingException(int expectedSequenceNumber, int actualSequenceNumber) {
            this.expectedSequenceNumber = expectedSequenceNumber;
            this.actualSequenceNumber = actualSequenceNumber;
        }

        @Override
        public String getMessage() {
            return String.format(
                    "Buffer re-ordering: expected buffer with sequence number %d, but received %d.",
                    expectedSequenceNumber, actualSequenceNumber);
        }
    }

    private static final class SequenceBuffer {
        final Buffer buffer;
        final int sequenceNumber;

        private SequenceBuffer(Buffer buffer, int sequenceNumber) {
            this.buffer = buffer;
            this.sequenceNumber = sequenceNumber;
        }
    }
}
