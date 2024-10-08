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
import org.apache.flink.core.memory.MemorySegment;
import org.apache.flink.core.memory.MemorySegmentFactory;
import org.apache.flink.core.memory.MemorySegmentProvider;
import org.apache.flink.runtime.checkpoint.channel.InputChannelInfo;
import org.apache.flink.runtime.clusterframework.types.ResourceID;
import org.apache.flink.runtime.event.AbstractEvent;
import org.apache.flink.runtime.event.TaskEvent;
import org.apache.flink.runtime.execution.CancelTaskException;
import org.apache.flink.runtime.io.network.api.EndOfPartitionEvent;
import org.apache.flink.runtime.io.network.api.serialization.EventSerializer;
import org.apache.flink.runtime.io.network.buffer.Buffer;
import org.apache.flink.runtime.io.network.buffer.BufferDecompressor;
import org.apache.flink.runtime.io.network.buffer.BufferPool;
import org.apache.flink.runtime.io.network.buffer.BufferProvider;
import org.apache.flink.runtime.io.network.partition.PartitionProducerStateProvider;
import org.apache.flink.runtime.io.network.partition.PrioritizedDeque;
import org.apache.flink.runtime.io.network.partition.ResultPartitionID;
import org.apache.flink.runtime.io.network.partition.ResultPartitionType;
import org.apache.flink.runtime.io.network.partition.consumer.InputChannel.BufferAndAvailability;
import org.apache.flink.runtime.jobgraph.DistributionPattern;
import org.apache.flink.runtime.jobgraph.IntermediateDataSetID;
import org.apache.flink.runtime.jobgraph.IntermediateResultPartitionID;
import org.apache.flink.runtime.shuffle.NettyShuffleDescriptor;
import org.apache.flink.util.Preconditions;
import org.apache.flink.util.function.SupplierWithException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Timer;
import java.util.concurrent.CompletableFuture;

import static org.apache.flink.util.Preconditions.checkArgument;
import static org.apache.flink.util.Preconditions.checkNotNull;
import static org.apache.flink.util.Preconditions.checkState;

/**
 * SingleInputGate 的逻辑还比较清晰，它通过内部维护的一个队列形成一个生产者-消费者的模型， 当 InputChannel 中有数据时就加入到队列中，
 * 在需要获取数据时从队列中取出一个 channel， 获取 channel 中的数据。
 *
 * <p>An input gate consumes one or more partitions of a single produced intermediate result.
 *
 * <p>Each intermediate result is partitioned over its producing parallel subtasks; each of these
 * partitions is furthermore partitioned into one or more subpartitions.
 *
 * <p>As an example, consider a map-reduce program, where the map operator produces data and the
 * reduce operator consumes the produced data.
 *
 * <pre>{@code
 * +-----+              +---------------------+              +--------+
 * | Map | = produce => | Intermediate Result | <= consume = | Reduce |
 * +-----+              +---------------------+              +--------+
 * }</pre>
 *
 * <p>When deploying such a program in parallel, the intermediate result will be partitioned over
 * its producing parallel subtasks; each of these partitions is furthermore partitioned into one or
 * more subpartitions.
 *
 * <pre>{@code
 *                            Intermediate result
 *               +-----------------------------------------+
 *               |                      +----------------+ |              +-----------------------+
 * +-------+     | +-------------+  +=> | Subpartition 1 | | <=======+=== | Input Gate | Reduce 1 |
 * | Map 1 | ==> | | Partition 1 | =|   +----------------+ |         |    +-----------------------+
 * +-------+     | +-------------+  +=> | Subpartition 2 | | <==+    |
 *               |                      +----------------+ |    |    | Subpartition request
 *               |                                         |    |    |
 *               |                      +----------------+ |    |    |
 * +-------+     | +-------------+  +=> | Subpartition 1 | | <==+====+
 * | Map 2 | ==> | | Partition 2 | =|   +----------------+ |    |         +-----------------------+
 * +-------+     | +-------------+  +=> | Subpartition 2 | | <==+======== | Input Gate | Reduce 2 |
 *               |                      +----------------+ |              +-----------------------+
 *               +-----------------------------------------+
 * }</pre>
 *
 * <p>In the above example, two map subtasks produce the intermediate result in parallel, resulting
 * in two partitions (Partition 1 and 2). Each of these partitions is further partitioned into two
 * subpartitions -- one for each parallel reduce subtask.
 */
public class SingleInputGate extends IndexedInputGate {

    private static final Logger LOG = LoggerFactory.getLogger(SingleInputGate.class);

    /**
     * Lock object to guard partition requests and runtime channel updates. 锁定对象以保护分区请求和运行时通道更新。
     * Lock object to guard partition requests and runtime channel updates.
     */
    private final Object requestLock = new Object();

    /**
     * 所属任务的名称，用于日志记录。 owningTaskName = "Flat Map (2/4)#0 (0ef8b3d70af60be8633af8af4e1c0698)" The
     * name of the owning task, for logging purposes.
     */
    private final String owningTaskName;

    private final int gateIndex;

    /**
     * The ID of the consumed intermediate result. Each input gate consumes partitions of the
     * intermediate result specified by this ID. This ID also identifies the input gate at the
     * consuming task.
     *
     * <p>消费上一算子输出结果子分区的 ID {IntermediateDataSetID@8214} "5eba1007ad48ad2243891e1eff29c32b"
     */
    private final IntermediateDataSetID consumedResultId;

    /**
     * 结果分区的类型 : {ResultPartitionType@7380} "PIPELINED_BOUNDED" The type of the partition the input
     * gate is consuming.
     */
    private final ResultPartitionType consumedPartitionType;

    /**
     * 消费子分区的 index The index of the consumed subpartition of each consumed partition. This index
     * depends on the {@link DistributionPattern} and the subtask indices of the producing and
     * consuming task.
     */
    private final int consumedSubpartitionIndex;

    /**
     * inputchannel的数量 The number of input channels (equivalent to the number of consumed
     * partitions).
     */
    private final int numberOfInputChannels;

    /**
     * InputGate中所有的 Input channels. 结果分区 --> input channels 每个消耗的中间结果分区都有一个输入通道。
     * 我们将其存储在一个映射中，用于单个通道的运行时更新 inputChannels = {HashMap@8215} size = 1
     * {IntermediateResultPartitionID@8237} "5eba1007ad48ad2243891e1eff29c32b#0" ->
     * {LocalRecoveredInputChannel@8238}
     *
     * <p>Input channels. There is a one input channel for each consumed intermediate result
     * partition. We store this in a map for runtime updates of single channels.
     */
    private final Map<IntermediateResultPartitionID, InputChannel> inputChannels;

    /**
     * InputGate中所有的 Input channels. channels = {InputChannel[1]@8216} 0 =
     * {LocalRecoveredInputChannel@8238}
     */
    @GuardedBy("requestLock")
    private final InputChannel[] channels;

    /**
     * InputChannel 构成的队列，这些 InputChannel 中都有有可供消费的数据 inputChannelsWithData =
     * {PrioritizedDeque@8217} "[]" Channels, which notified this input gate about available data.
     */
    private final PrioritizedDeque<InputChannel> inputChannelsWithData = new PrioritizedDeque<>();

    /**
     * 保证inputChannelsWithData队列唯一性的字段。
     *
     * <p>这两个字段应该统一到一个字段上。
     *
     * <p>enqueuedInputChannelsWithData = {BitSet@8218} "{}"
     *
     * <p>Field guaranteeing uniqueness for inputChannelsWithData queue. Both of those fields should
     * be unified onto one.
     */
    @GuardedBy("inputChannelsWithData")
    private final BitSet enqueuedInputChannelsWithData;

    // 无分区事件的通道 ??
    private final BitSet channelsWithEndOfPartitionEvents;

    // 最后优先级序列号
    @GuardedBy("inputChannelsWithData")
    private int[] lastPrioritySequenceNumber;

    /** The partition producer state listener. */
    private final PartitionProducerStateProvider partitionProducerStateProvider;

    /**
     * 内存管理器: LocalBufferPool {LocalBufferPool@8221} "[size: 8, required: 1, requested: 1,
     * available: 1, max: 8, listeners: 0,subpartitions: 0, maxBuffersPerChannel: 2147483647,
     * destroyed: false]"
     *
     * <p>Buffer pool for incoming buffers. Incoming data from remote channels is copied to buffers
     * from this pool.
     */
    private BufferPool bufferPool;

    private boolean hasReceivedAllEndOfPartitionEvents;

    /** 指示是否已请求分区的标志 Flag indicating whether partitions have been requested. */
    private boolean requestedPartitionsFlag;

    /** 阻塞的Evnet */
    private final List<TaskEvent> pendingEvents = new ArrayList<>();

    // 未初始化通道数
    private int numberOfUninitializedChannels;

    /**
     * 重新触发本地分区请求的计时器。仅在实际需要时初始化。 A timer to retrigger local partition requests. Only initialized if
     * actually needed.
     */
    private Timer retriggerLocalRequestTimer;

    // bufferpoolFactory的工厂类
    // {SingleInputGateFactory$lambda@8223}
    private final SupplierWithException<BufferPool, IOException> bufferPoolFactory;

    private final CompletableFuture<Void> closeFuture;

    @Nullable private final BufferDecompressor bufferDecompressor;

    // {NetworkBufferPool@7512}
    private final MemorySegmentProvider memorySegmentProvider;

    /**
     * {HybridMemorySegment@8225}
     *
     * <p>The segment to read data from file region of bounded blocking partition by local input
     * channel.
     */
    private final MemorySegment unpooledSegment;

    public SingleInputGate(
            String owningTaskName,
            int gateIndex,
            IntermediateDataSetID consumedResultId,
            final ResultPartitionType consumedPartitionType,
            int consumedSubpartitionIndex,
            int numberOfInputChannels,
            PartitionProducerStateProvider partitionProducerStateProvider,
            SupplierWithException<BufferPool, IOException> bufferPoolFactory,
            @Nullable BufferDecompressor bufferDecompressor,
            MemorySegmentProvider memorySegmentProvider,
            int segmentSize) {

        this.owningTaskName = checkNotNull(owningTaskName);
        Preconditions.checkArgument(0 <= gateIndex, "The gate index must be positive.");
        this.gateIndex = gateIndex;

        this.consumedResultId = checkNotNull(consumedResultId);
        this.consumedPartitionType = checkNotNull(consumedPartitionType);
        this.bufferPoolFactory = checkNotNull(bufferPoolFactory);

        checkArgument(consumedSubpartitionIndex >= 0);
        this.consumedSubpartitionIndex = consumedSubpartitionIndex;

        checkArgument(numberOfInputChannels > 0);
        this.numberOfInputChannels = numberOfInputChannels;

        this.inputChannels = new HashMap<>(numberOfInputChannels);
        this.channels = new InputChannel[numberOfInputChannels];
        this.channelsWithEndOfPartitionEvents = new BitSet(numberOfInputChannels);
        this.enqueuedInputChannelsWithData = new BitSet(numberOfInputChannels);
        this.lastPrioritySequenceNumber = new int[numberOfInputChannels];
        Arrays.fill(lastPrioritySequenceNumber, Integer.MIN_VALUE);

        this.partitionProducerStateProvider = checkNotNull(partitionProducerStateProvider);

        this.bufferDecompressor = bufferDecompressor;
        this.memorySegmentProvider = checkNotNull(memorySegmentProvider);

        this.closeFuture = new CompletableFuture<>();

        this.unpooledSegment = MemorySegmentFactory.allocateUnpooledSegment(segmentSize);
    }

    protected PrioritizedDeque<InputChannel> getInputChannelsWithData() {
        return inputChannelsWithData;
    }

    // 在InputGate的setup阶段为所有的input channel分配专属内存。查看SingleInputGate的setup方法
    @Override
    public void setup() throws IOException {
        checkState(
                this.bufferPool == null,
                "Bug in input gate setup logic: Already registered buffer pool.");

        // 为所有的InputChannel分配专用buffer，剩下的作为浮动buffer
        setupChannels();

        // 设置bufferPool，用于分配浮动buffer
        BufferPool bufferPool = bufferPoolFactory.get();

        // 请求各个input channel需要读取的subpartition
        setBufferPool(bufferPool);
    }

    @Override
    public CompletableFuture<Void> getStateConsumedFuture() {
        synchronized (requestLock) {
            List<CompletableFuture<?>> futures = new ArrayList<>(inputChannels.size());
            for (InputChannel inputChannel : inputChannels.values()) {
                if (inputChannel instanceof RecoveredInputChannel) {
                    futures.add(((RecoveredInputChannel) inputChannel).getStateConsumedFuture());
                }
            }
            return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
        }
    }

    // 请求分区
    @Override
    public void requestPartitions() {
        synchronized (requestLock) {

            // 只能请求一次partition，第一次调用该方法后此flag会被设置为true
            if (!requestedPartitionsFlag) {
                if (closeFuture.isDone()) {
                    throw new IllegalStateException("Already released.");
                }

                // 健全性检查
                // Sanity checks
                if (numberOfInputChannels != inputChannels.size()) {
                    throw new IllegalStateException(
                            String.format(
                                    "Bug in input gate setup logic: mismatch between "
                                            + "number of total input channels [%s] and the currently set number of input "
                                            + "channels [%s].",
                                    inputChannels.size(), numberOfInputChannels));
                }

                // 转换恢复的输入通道
                // 可以理解为更新 inputchannel
                convertRecoveredInputChannels();

                // 请求分区数据
                internalRequestPartitions();
            }

            // 方法调用完毕设置flag为true，防止重复调用
            requestedPartitionsFlag = true;
        }
    }

    // 转换恢复的输入通道
    @VisibleForTesting
    void convertRecoveredInputChannels() {

        // 循环所有的inputChannels，请求他们对应的subPartition
        LOG.info("Converting recovered input channels ({} channels)", getNumberOfInputChannels());
        for (Map.Entry<IntermediateResultPartitionID, InputChannel> entry :
                inputChannels.entrySet()) {

            // inputChannel = {LocalRecoveredInputChannel@7178}
            //    partitionManager = {ResultPartitionManager@7167}
            //    taskEventPublisher = {TaskEventDispatcher@7168}
            //    receivedBuffers = {ArrayDeque@7186}  size = 0
            //    stateConsumedFuture = {CompletableFuture@7187}
            // "java.util.concurrent.CompletableFuture@fa3bb19[Completed normally]"
            //    bufferManager = {BufferManager@7188}
            //    isReleased = false
            //    channelStateWriter = {ChannelStateWriter$NoOpChannelStateWriter@7170}
            //    sequenceNumber = -2147483648
            //    networkBuffersPerChannel = 2
            //    exclusiveBuffersAssigned = false
            //    channelInfo = {InputChannelInfo@7189} "InputChannelInfo{gateIdx=0,
            // inputChannelIdx=1}"
            //    partitionId = {ResultPartitionID@7190}
            // "fed335673801e7c5b4560d86af77b7fe#1@114c195b8f111702ea7728fd7b5846dc"
            //    inputGate = {SingleInputGate@7176}
            // "SingleInputGate{owningTaskName='Window(TumblingProcessingTimeWindows(5000),
            // ProcessingTimeTrigger, ReduceFunction$1, PassThroughWindowFunction) (3/4)#0
            // (38152ea7733e5a835ec4db6cb078a1ad)', gateIndex=0}"
            //    cause = {AtomicReference@7191} "null"
            //    initialBackoff = 100
            //    maxBackoff = 10000
            //    numBytesIn = {InputChannelMetrics$MultiCounterWrapper@7192}
            //    numBuffersIn = {InputChannelMetrics$MultiCounterWrapper@7193}
            //    currentBackoff = 0

            InputChannel inputChannel = entry.getValue();
            if (inputChannel instanceof RecoveredInputChannel) {
                try {

                    //    inputChannel = {LocalRecoveredInputChannel@7178}
                    //    realInputChannel = {LocalInputChannel@7179} "LocalInputChannel
                    // [fed335673801e7c5b4560d86af77b7fe#1@114c195b8f111702ea7728fd7b5846dc]"

                    InputChannel realInputChannel =
                            ((RecoveredInputChannel) inputChannel).toInputChannel();
                    inputChannel.releaseAllResources();
                    entry.setValue(realInputChannel);
                    channels[inputChannel.getChannelIndex()] = realInputChannel;
                } catch (Throwable t) {
                    inputChannel.setError(t);
                    return;
                }
            }
        }
    }

    // 请求数据 ???
    private void internalRequestPartitions() {
        for (InputChannel inputChannel : inputChannels.values()) {
            try {
                // 每一个channel都请求对应的子分区
                inputChannel.requestSubpartition(consumedSubpartitionIndex);
            } catch (Throwable t) {
                inputChannel.setError(t);
                return;
            }
        }
    }

    @Override
    public void finishReadRecoveredState() throws IOException {
        for (final InputChannel channel : channels) {
            if (channel instanceof RecoveredInputChannel) {
                ((RecoveredInputChannel) channel).finishReadRecoveredState();
            }
        }
    }

    // ------------------------------------------------------------------------
    // Properties
    // ------------------------------------------------------------------------

    @Override
    public int getNumberOfInputChannels() {
        return numberOfInputChannels;
    }

    @Override
    public int getGateIndex() {
        return gateIndex;
    }

    /**
     * Returns the type of this input channel's consumed result partition.
     *
     * @return consumed result partition type
     */
    public ResultPartitionType getConsumedPartitionType() {
        return consumedPartitionType;
    }

    BufferProvider getBufferProvider() {
        return bufferPool;
    }

    public BufferPool getBufferPool() {
        return bufferPool;
    }

    MemorySegmentProvider getMemorySegmentProvider() {
        return memorySegmentProvider;
    }

    public String getOwningTaskName() {
        return owningTaskName;
    }

    public int getNumberOfQueuedBuffers() {
        // re-try 3 times, if fails, return 0 for "unknown"
        for (int retry = 0; retry < 3; retry++) {
            try {
                int totalBuffers = 0;

                for (InputChannel channel : inputChannels.values()) {
                    totalBuffers += channel.unsynchronizedGetNumberOfQueuedBuffers();
                }

                return totalBuffers;
            } catch (Exception ignored) {
            }
        }

        return 0;
    }

    public CompletableFuture<Void> getCloseFuture() {
        return closeFuture;
    }

    @Override
    public InputChannel getChannel(int channelIndex) {
        return channels[channelIndex];
    }

    // ------------------------------------------------------------------------
    // Setup/Life-cycle
    // ------------------------------------------------------------------------

    public void setBufferPool(BufferPool bufferPool) {
        checkState(
                this.bufferPool == null,
                "Bug in input gate setup logic: buffer pool has"
                        + "already been set for this input gate.");

        this.bufferPool = checkNotNull(bufferPool);
    }

    /** Assign the exclusive buffers to all remote input channels directly for credit-based mode. */
    @VisibleForTesting
    public void setupChannels() throws IOException {
        synchronized (requestLock) {
            for (InputChannel inputChannel : inputChannels.values()) {
                // 分别调用SingleInputGate中每个InputChannel的setup方法。
                inputChannel.setup();
            }
        }
    }

    public void setInputChannels(InputChannel... channels) {
        if (channels.length != numberOfInputChannels) {
            throw new IllegalArgumentException(
                    "Expected "
                            + numberOfInputChannels
                            + " channels, "
                            + "but got "
                            + channels.length);
        }
        synchronized (requestLock) {
            System.arraycopy(channels, 0, this.channels, 0, numberOfInputChannels);
            for (InputChannel inputChannel : channels) {
                IntermediateResultPartitionID partitionId =
                        inputChannel.getPartitionId().getPartitionId();
                if (inputChannels.put(partitionId, inputChannel) == null
                        && inputChannel instanceof UnknownInputChannel) {

                    numberOfUninitializedChannels++;
                }
            }
        }
    }

    public void updateInputChannel(
            ResourceID localLocation, NettyShuffleDescriptor shuffleDescriptor)
            throws IOException, InterruptedException {
        synchronized (requestLock) {
            if (closeFuture.isDone()) {
                // There was a race with a task failure/cancel
                return;
            }

            IntermediateResultPartitionID partitionId =
                    shuffleDescriptor.getResultPartitionID().getPartitionId();

            InputChannel current = inputChannels.get(partitionId);

            // 该InputChannel尚未明确...
            if (current instanceof UnknownInputChannel) {
                UnknownInputChannel unknownChannel = (UnknownInputChannel) current;
                boolean isLocal = shuffleDescriptor.isLocalTo(localLocation);
                InputChannel newChannel;
                if (isLocal) {
                    // LocalInputChannel
                    newChannel = unknownChannel.toLocalInputChannel();
                } else {
                    // RemoteInputChannel
                    RemoteInputChannel remoteInputChannel =
                            unknownChannel.toRemoteInputChannel(
                                    shuffleDescriptor.getConnectionId());
                    remoteInputChannel.setup();
                    newChannel = remoteInputChannel;
                }
                LOG.debug("{}: Updated unknown input channel to {}.", owningTaskName, newChannel);

                inputChannels.put(partitionId, newChannel);
                channels[current.getChannelIndex()] = newChannel;

                if (requestedPartitionsFlag) {
                    newChannel.requestSubpartition(consumedSubpartitionIndex);
                }

                for (TaskEvent event : pendingEvents) {
                    newChannel.sendTaskEvent(event);
                }

                if (--numberOfUninitializedChannels == 0) {
                    pendingEvents.clear();
                }
            }
        }
    }

    /** Retriggers a partition request. */
    public void retriggerPartitionRequest(IntermediateResultPartitionID partitionId)
            throws IOException {
        synchronized (requestLock) {
            if (!closeFuture.isDone()) {
                final InputChannel ch = inputChannels.get(partitionId);

                checkNotNull(ch, "Unknown input channel with ID " + partitionId);

                LOG.debug(
                        "{}: Retriggering partition request {}:{}.",
                        owningTaskName,
                        ch.partitionId,
                        consumedSubpartitionIndex);

                if (ch.getClass() == RemoteInputChannel.class) {

                    // RemoteInputChannel
                    final RemoteInputChannel rch = (RemoteInputChannel) ch;
                    rch.retriggerSubpartitionRequest(consumedSubpartitionIndex);
                } else if (ch.getClass() == LocalInputChannel.class) {

                    // RemoteInputChannel
                    final LocalInputChannel ich = (LocalInputChannel) ch;

                    if (retriggerLocalRequestTimer == null) {
                        retriggerLocalRequestTimer = new Timer(true);
                    }

                    ich.retriggerSubpartitionRequest(
                            retriggerLocalRequestTimer, consumedSubpartitionIndex);
                } else {
                    throw new IllegalStateException(
                            "Unexpected type of channel to retrigger partition: " + ch.getClass());
                }
            }
        }
    }

    @VisibleForTesting
    Timer getRetriggerLocalRequestTimer() {
        return retriggerLocalRequestTimer;
    }

    MemorySegment getUnpooledSegment() {
        return unpooledSegment;
    }

    @Override
    public void close() throws IOException {
        boolean released = false;
        synchronized (requestLock) {
            if (!closeFuture.isDone()) {
                try {
                    LOG.debug("{}: Releasing {}.", owningTaskName, this);

                    if (retriggerLocalRequestTimer != null) {
                        retriggerLocalRequestTimer.cancel();
                    }

                    for (InputChannel inputChannel : inputChannels.values()) {
                        try {
                            // 释放资源
                            inputChannel.releaseAllResources();
                        } catch (IOException e) {
                            LOG.warn(
                                    "{}: Error during release of channel resources: {}.",
                                    owningTaskName,
                                    e.getMessage(),
                                    e);
                        }
                    }

                    // The buffer pool can actually be destroyed immediately after the
                    // reader received all of the data from the input channels.
                    if (bufferPool != null) {
                        // 小伙 bufferPool
                        bufferPool.lazyDestroy();
                    }
                } finally {
                    released = true;
                    closeFuture.complete(null);
                }
            }
        }

        if (released) {
            synchronized (inputChannelsWithData) {
                // 通知所有
                inputChannelsWithData.notifyAll();
            }
        }
    }

    @Override
    public boolean isFinished() {
        return hasReceivedAllEndOfPartitionEvents;
    }

    @Override
    public String toString() {
        return "SingleInputGate{"
                + "owningTaskName='"
                + owningTaskName
                + '\''
                + ", gateIndex="
                + gateIndex
                + '}';
    }

    // ------------------------------------------------------------------------
    // Consume
    // ------------------------------------------------------------------------

    @Override
    public Optional<BufferOrEvent> getNext() throws IOException, InterruptedException {
        return getNextBufferOrEvent(true);
    }

    @Override
    public Optional<BufferOrEvent> pollNext() throws IOException, InterruptedException {
        return getNextBufferOrEvent(false);
    }

    /**
     * Task 通过循环调用 InputGate.getNextBufferOrEvent 方法获取输入数据， 并将获取的数据交给它所封装的算子进行处理， 这构成了一个 Task
     * 的基本运行逻辑。
     *
     * @param blocking
     * @return
     * @throws IOException
     * @throws InterruptedException
     */
    private Optional<BufferOrEvent> getNextBufferOrEvent(boolean blocking)
            throws IOException, InterruptedException {
        // 如果接收到所有分区终止的事件，则返回空
        if (hasReceivedAllEndOfPartitionEvents) {
            return Optional.empty();
        }

        // 如果input gate被关闭
        if (closeFuture.isDone()) {
            throw new CancelTaskException("Input gate is already closed.");
        }

        // 以{ blocking : 阻塞/非阻塞 }方式读取数据
        Optional<InputWithData<InputChannel, BufferAndAvailability>> next =
                waitAndGetNextData(blocking);
        if (!next.isPresent()) {
            return Optional.empty();
        }

        // 获取到数据
        InputWithData<InputChannel, BufferAndAvailability> inputWithData = next.get();

        // 根据Buffer 判断数据是事件还是数据..
        return Optional.of(
                transformToBufferOrEvent(
                        inputWithData.data.buffer(),
                        inputWithData.moreAvailable,
                        inputWithData.input,
                        inputWithData.morePriorityEvents));
    }

    private Optional<InputWithData<InputChannel, BufferAndAvailability>> waitAndGetNextData(
            boolean blocking) throws IOException, InterruptedException {
        while (true) {
            synchronized (inputChannelsWithData) {
                Optional<InputChannel> inputChannelOpt = getChannel(blocking);
                if (!inputChannelOpt.isPresent()) {
                    return Optional.empty();
                }

                // 获取channel，根据blocking参数决定是否是阻塞方式
                final InputChannel inputChannel = inputChannelOpt.get();
                Optional<BufferAndAvailability> bufferAndAvailabilityOpt =
                        inputChannel.getNextBuffer();

                if (!bufferAndAvailabilityOpt.isPresent()) {
                    checkUnavailability();
                    continue;
                }

                final BufferAndAvailability bufferAndAvailability = bufferAndAvailabilityOpt.get();
                if (bufferAndAvailability.moreAvailable()) {
                    // 将输入通道排在末尾以避免饥饿
                    // enqueue the inputChannel at the end to avoid starvation
                    queueChannelUnsafe(inputChannel, bufferAndAvailability.morePriorityEvents());
                }

                final boolean morePriorityEvents =
                        inputChannelsWithData.getNumPriorityElements() > 0;
                if (bufferAndAvailability.hasPriority()) {
                    lastPrioritySequenceNumber[inputChannel.getChannelIndex()] =
                            bufferAndAvailability.getSequenceNumber();
                    if (!morePriorityEvents) {
                        priorityAvailabilityHelper.resetUnavailable();
                    }
                }

                // 如果inputChannelsWithData为空，设置为不可用状态
                checkUnavailability();

                // 返回包装后的结果
                return Optional.of(
                        new InputWithData<>(
                                inputChannel,
                                bufferAndAvailability,
                                !inputChannelsWithData.isEmpty(),
                                morePriorityEvents));
            }
        }
    }

    private void checkUnavailability() {
        assert Thread.holdsLock(inputChannelsWithData);

        if (inputChannelsWithData.isEmpty()) {
            availabilityHelper.resetUnavailable();
        }
    }

    private BufferOrEvent transformToBufferOrEvent(
            Buffer buffer,
            boolean moreAvailable,
            InputChannel currentChannel,
            boolean morePriorityEvents)
            throws IOException, InterruptedException {
        // 根据Buffer 判断数据是事件还是数据..
        if (buffer.isBuffer()) {
            return transformBuffer(buffer, moreAvailable, currentChannel, morePriorityEvents);
        } else {
            return transformEvent(buffer, moreAvailable, currentChannel, morePriorityEvents);
        }
    }

    private BufferOrEvent transformBuffer(
            Buffer buffer,
            boolean moreAvailable,
            InputChannel currentChannel,
            boolean morePriorityEvents) {
        return new BufferOrEvent(
                decompressBufferIfNeeded(buffer),
                currentChannel.getChannelInfo(),
                moreAvailable,
                morePriorityEvents);
    }

    private BufferOrEvent transformEvent(
            Buffer buffer,
            boolean moreAvailable,
            InputChannel currentChannel,
            boolean morePriorityEvents)
            throws IOException, InterruptedException {
        final AbstractEvent event;
        try {
            event = EventSerializer.fromBuffer(buffer, getClass().getClassLoader());
        } finally {
            buffer.recycleBuffer();
        }

        // 如果是 EndOfPartitionEvent 事件，那么如果所有的 InputChannel 都接收到这个事件了
        // 将 hasReceivedAllEndOfPartitionEvents 标记为 true，此后不再能获取到数据
        if (event.getClass() == EndOfPartitionEvent.class) {
            channelsWithEndOfPartitionEvents.set(currentChannel.getChannelIndex());

            if (channelsWithEndOfPartitionEvents.cardinality() == numberOfInputChannels) {

                // 由于以下双方的竞争条件：
                //      1.在此方法中释放inputChannelsWithData锁并到达此位置
                //      2.空数据通知，对通道重新排队，我们可以将moreAvailable标志设置为true，而不需要更多数据。

                // Because of race condition between:
                // 1. releasing inputChannelsWithData lock in this method and reaching this place
                // 2. empty data notification that re-enqueues a channel
                // we can end up with moreAvailable flag set to true, while we expect no more data.
                checkState(!moreAvailable || !pollNext().isPresent());
                moreAvailable = false;
                hasReceivedAllEndOfPartitionEvents = true;
                markAvailable();
            }

            currentChannel.releaseAllResources();
        }

        return new BufferOrEvent(
                event,
                buffer.getDataType().hasPriority(),
                currentChannel.getChannelInfo(),
                moreAvailable,
                buffer.getSize(),
                morePriorityEvents);
    }

    private Buffer decompressBufferIfNeeded(Buffer buffer) {
        if (buffer.isCompressed()) {
            try {
                checkNotNull(bufferDecompressor, "Buffer decompressor not set.");
                return bufferDecompressor.decompressToIntermediateBuffer(buffer);
            } finally {
                buffer.recycleBuffer();
            }
        }
        return buffer;
    }

    private void markAvailable() {
        CompletableFuture<?> toNotify;
        synchronized (inputChannelsWithData) {
            toNotify = availabilityHelper.getUnavailableToResetAvailable();
        }
        toNotify.complete(null);
    }

    @Override
    public void sendTaskEvent(TaskEvent event) throws IOException {
        synchronized (requestLock) {
            // 循环所有的InputChannel 调用其sendTaskEvent
            for (InputChannel inputChannel : inputChannels.values()) {
                inputChannel.sendTaskEvent(event);
            }

            // 如果有尚未初始化完成的队列, 将Event加入队列
            if (numberOfUninitializedChannels > 0) {
                pendingEvents.add(event);
            }
        }
    }

    @Override
    public void resumeConsumption(InputChannelInfo channelInfo) throws IOException {
        checkState(!isFinished(), "InputGate already finished.");
        // BEWARE: consumption resumption only happens for streaming jobs in which all slots
        // are allocated together so there should be no UnknownInputChannel. As a result, it
        // is safe to not synchronize the requestLock here. We will refactor the code to not
        // rely on this assumption in the future.
        channels[channelInfo.getInputChannelIdx()].resumeConsumption();
    }

    // ------------------------------------------------------------------------
    // Channel notifications
    // ------------------------------------------------------------------------

    // //当一个 InputChannel 有数据时的回调
    void notifyChannelNonEmpty(InputChannel channel) {
        queueChannel(checkNotNull(channel), null);
    }

    /**
     * Notifies that the respective channel has a priority event at the head for the given buffer
     * number.
     *
     * <p>The buffer number limits the notification to the respective buffer and voids the whole
     * notification in case that the buffer has been polled in the meantime. That is, if task thread
     * polls the enqueued priority buffer before this notification occurs (notification is not
     * performed under lock), this buffer number allows {@link #queueChannel(InputChannel, Integer)}
     * to avoid spurious priority wake-ups.
     */
    void notifyPriorityEvent(InputChannel inputChannel, int prioritySequenceNumber) {
        queueChannel(checkNotNull(inputChannel), prioritySequenceNumber);
    }

    void triggerPartitionStateCheck(ResultPartitionID partitionId) {
        partitionProducerStateProvider.requestPartitionProducerState(
                consumedResultId,
                partitionId,
                ((PartitionProducerStateProvider.ResponseHandle responseHandle) -> {
                    boolean isProducingState =
                            new RemoteChannelStateChecker(partitionId, owningTaskName)
                                    .isProducerReadyOrAbortConsumption(responseHandle);
                    if (isProducingState) {
                        try {
                            retriggerPartitionRequest(partitionId.getPartitionId());
                        } catch (IOException t) {
                            responseHandle.failConsumption(t);
                        }
                    }
                }));
    }

    // 将新的channel加入队列
    private void queueChannel(InputChannel channel, @Nullable Integer prioritySequenceNumber) {

        try (GateNotificationHelper notification =
                new GateNotificationHelper(this, inputChannelsWithData)) {

            synchronized (inputChannelsWithData) {
                boolean priority = prioritySequenceNumber != null;

                if (priority
                        && isOutdated(
                                prioritySequenceNumber,
                                lastPrioritySequenceNumber[channel.getChannelIndex()])) {
                    // priority event at the given offset already polled (notification is not atomic
                    // in respect to
                    // buffer enqueuing), so just ignore the notification
                    return;
                }

                if (channel.isReleased()) {
                    // when channel is closed, EndOfPartitionEvent is send and a final notification
                    // if EndOfPartitionEvent causes a release, we must ignore the notification
                    return;
                }

                if (!queueChannelUnsafe(channel, priority)) {
                    return;
                }

                if (priority && inputChannelsWithData.getNumPriorityElements() == 1) {
                    notification.notifyPriority();
                }
                if (inputChannelsWithData.size() == 1) {
                    notification.notifyDataAvailable();
                }
            }
        }
    }

    private boolean isOutdated(int sequenceNumber, int lastSequenceNumber) {
        if ((lastSequenceNumber < 0) != (sequenceNumber < 0)
                && Math.max(lastSequenceNumber, sequenceNumber) > Integer.MAX_VALUE / 2) {
            // probably overflow of one of the two numbers, the negative one is greater then
            return lastSequenceNumber < 0;
        }
        return lastSequenceNumber >= sequenceNumber;
    }

    /**
     * 如果尚未排队，则对通道进行排队，可能会提高优先级。 Queues the channel if not already enqueued, potentially raising the
     * priority.
     *
     * @return true iff it has been enqueued/prioritized = some change to {@link
     *     #inputChannelsWithData} happened
     */
    private boolean queueChannelUnsafe(InputChannel channel, boolean priority) {
        assert Thread.holdsLock(inputChannelsWithData);

        // 判断这个channel是否已经在队列中
        final boolean alreadyEnqueued =
                enqueuedInputChannelsWithData.get(channel.getChannelIndex());
        if (alreadyEnqueued
                && (!priority || inputChannelsWithData.containsPriorityElement(channel))) {
            // already notified / prioritized (double notification), ignore
            return false;
        }

        // 加入队列
        inputChannelsWithData.add(channel, priority, alreadyEnqueued);

        if (!alreadyEnqueued) {
            enqueuedInputChannelsWithData.set(channel.getChannelIndex());
        }
        return true;
    }

    private Optional<InputChannel> getChannel(boolean blocking) throws InterruptedException {
        assert Thread.holdsLock(inputChannelsWithData);

        while (inputChannelsWithData.isEmpty()) {
            if (closeFuture.isDone()) {
                throw new IllegalStateException("Released");
            }

            if (blocking) {
                inputChannelsWithData.wait();
            } else {
                availabilityHelper.resetUnavailable();
                return Optional.empty();
            }
        }

        InputChannel inputChannel = inputChannelsWithData.poll();
        enqueuedInputChannelsWithData.clear(inputChannel.getChannelIndex());

        return Optional.of(inputChannel);
    }

    // ------------------------------------------------------------------------

    public Map<IntermediateResultPartitionID, InputChannel> getInputChannels() {
        return inputChannels;
    }
}
