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

package org.apache.flink.runtime.io.network.partition;

import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.metrics.Counter;
import org.apache.flink.metrics.SimpleCounter;
import org.apache.flink.runtime.executiongraph.IntermediateResultPartition;
import org.apache.flink.runtime.io.network.api.writer.ResultPartitionWriter;
import org.apache.flink.runtime.io.network.buffer.Buffer;
import org.apache.flink.runtime.io.network.buffer.BufferCompressor;
import org.apache.flink.runtime.io.network.buffer.BufferPool;
import org.apache.flink.runtime.io.network.partition.consumer.LocalInputChannel;
import org.apache.flink.runtime.io.network.partition.consumer.RemoteInputChannel;
import org.apache.flink.runtime.jobgraph.DistributionPattern;
import org.apache.flink.runtime.metrics.groups.TaskIOMetricGroup;
import org.apache.flink.runtime.taskexecutor.TaskExecutor;
import org.apache.flink.util.Preconditions;
import org.apache.flink.util.function.SupplierWithException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.apache.flink.util.Preconditions.checkNotNull;
import static org.apache.flink.util.Preconditions.checkState;

/**
 * 一个数据处理流程(Task)对应着一个ResultPartition， ResultPartition的数据需要发送数据到多个下游Channel(对应着下游多个Task)，
 * ResultPartition中的数据专门为下游不同的接收者做了分组，这个分组叫做ResultSubpartition。
 *
 * <p>此类是逻辑{@link IntermediateResultPartition}的运行时部分。实际上，结果分区是{@link Buffer}实例的集合。
 *
 * <p>A result partition for data produced by a single task.
 *
 * <p>This class is the runtime part of a logical {@link IntermediateResultPartition}. Essentially,
 * a result partition is a collection of {@link Buffer} instances. The buffers are organized in one
 * or more {@link ResultSubpartition} instances or in a joint structure which further partition the
 * data depending on the number of consuming tasks and the data {@link DistributionPattern}.
 *
 * <p>Tasks, which consume a result partition have to request one of its subpartitions. The request
 * happens either remotely (see {@link RemoteInputChannel}) or locally (see {@link
 * LocalInputChannel})
 *
 * <h2>Life-cycle</h2>
 *
 * <p>The life-cycle of each result partition has three (possibly overlapping) phases:
 *
 * <ol>
 *   <li><strong>Produce</strong>:
 *   <li><strong>Consume</strong>:
 *   <li><strong>Release</strong>:
 * </ol>
 *
 * <h2>Buffer management</h2>
 *
 * <h2>State management</h2>
 */
public abstract class ResultPartition implements ResultPartitionWriter {

    protected static final Logger LOG = LoggerFactory.getLogger(ResultPartition.class);

    // 任务名称: "Flat Map (1/4)#0 (b2490d6207a4eaa9f285fb307bd31782)"
    private final String owningTaskName;

    // 分区编号 xxx
    private final int partitionIndex;

    // ResultPartitionID
    //    partitionId = {ResultPartitionID@6979}
    // "3abe615ae9be22f64d8e5af582df91ed#0@b2490d6207a4eaa9f285fb307bd31782"
    //          partitionId = {IntermediateResultPartitionID@6988}
    // "3abe615ae9be22f64d8e5af582df91ed#0"
    //          producerId = {ExecutionAttemptID@6468} "b2490d6207a4eaa9f285fb307bd31782"
    protected final ResultPartitionID partitionId;

    /**
     * 分区类型 partitionType = {ResultPartitionType@6929} "PIPELINED_BOUNDED"
     *
     * <p>Type of this partition. Defines the concrete subpartition implementation to use.
     */
    protected final ResultPartitionType partitionType;

    //
    // ResultPartitionManager 管理当前 TaskManager 所有的 ResultPartition
    protected final ResultPartitionManager partitionManager;

    // 子分区的数量
    protected final int numSubpartitions;

    // 128 ??
    private final int numTargetKeyGroups;

    // - Runtime state --------------------------------------------------------

    private final AtomicBoolean isReleased = new AtomicBoolean();

    protected BufferPool bufferPool;

    private boolean isFinished;

    private volatile Throwable cause;

    //   bufferPoolFactory = {ResultPartitionFactory$lambda@6982}
    private final SupplierWithException<BufferPool, IOException> bufferPoolFactory;

    /** Used to compress buffer to reduce IO. */
    @Nullable protected final BufferCompressor bufferCompressor;

    protected Counter numBytesOut = new SimpleCounter();

    protected Counter numBuffersOut = new SimpleCounter();

    public ResultPartition(
            String owningTaskName,
            int partitionIndex,
            ResultPartitionID partitionId,
            ResultPartitionType partitionType,
            int numSubpartitions,
            int numTargetKeyGroups,
            ResultPartitionManager partitionManager,
            @Nullable BufferCompressor bufferCompressor,
            SupplierWithException<BufferPool, IOException> bufferPoolFactory) {

        this.owningTaskName = checkNotNull(owningTaskName);
        Preconditions.checkArgument(0 <= partitionIndex, "The partition index must be positive.");
        this.partitionIndex = partitionIndex;
        this.partitionId = checkNotNull(partitionId);
        this.partitionType = checkNotNull(partitionType);
        this.numSubpartitions = numSubpartitions;
        this.numTargetKeyGroups = numTargetKeyGroups;
        this.partitionManager = checkNotNull(partitionManager);
        this.bufferCompressor = bufferCompressor;
        this.bufferPoolFactory = bufferPoolFactory;
    }

    /**
     * Registers a buffer pool with this result partition.
     *
     * <p>There is one pool for each result partition, which is shared by all its sub partitions.
     *
     * <p>The pool is registered with the partition *after* it as been constructed in order to
     * conform to the life-cycle of task registrations in the {@link TaskExecutor}.
     */
    @Override
    public void setup() throws IOException {
        checkState(
                this.bufferPool == null,
                "Bug in result partition setup logic: Already registered buffer pool.");
        // bufferPool = {LocalBufferPool@6981} "[
        //          size: 16,
        //          required: 5,
        //          requested: 1,
        //          available: 1,
        //          max: 16,
        //          listeners: 0,
        //          subpartitions: 4,
        //          maxBuffersPerChannel: 10,
        //          destroyed: false
        //     ]"
        this.bufferPool = checkNotNull(bufferPoolFactory.get());

        // 将这个分区注册到partitionManager
        partitionManager.registerResultPartition(this);
    }

    public String getOwningTaskName() {
        return owningTaskName;
    }

    @Override
    public ResultPartitionID getPartitionId() {
        return partitionId;
    }

    public int getPartitionIndex() {
        return partitionIndex;
    }

    @Override
    public int getNumberOfSubpartitions() {
        return numSubpartitions;
    }

    public BufferPool getBufferPool() {
        return bufferPool;
    }

    /** Returns the total number of queued buffers of all subpartitions. */
    public abstract int getNumberOfQueuedBuffers();

    /** Returns the number of queued buffers of the given target subpartition. */
    public abstract int getNumberOfQueuedBuffers(int targetSubpartition);

    /**
     * Returns the type of this result partition.
     *
     * @return result partition type
     */
    public ResultPartitionType getPartitionType() {
        return partitionType;
    }

    // ------------------------------------------------------------------------

    /**
     * Finishes the result partition.
     *
     * <p>After this operation, it is not possible to add further data to the result partition.
     *
     * <p>For BLOCKING results, this will trigger the deployment of consuming tasks.
     */
    @Override
    public void finish() throws IOException {
        checkInProduceState();

        isFinished = true;
    }

    @Override
    public boolean isFinished() {
        return isFinished;
    }

    public void release() {
        release(null);
    }

    @Override
    public void release(Throwable cause) {
        if (isReleased.compareAndSet(false, true)) {
            LOG.debug("{}: Releasing {}.", owningTaskName, this);

            // Set the error cause
            if (cause != null) {
                this.cause = cause;
            }

            releaseInternal();
        }
    }

    /** Releases all produced data including both those stored in memory and persisted on disk. */
    protected abstract void releaseInternal();

    @Override
    public void close() {
        if (bufferPool != null) {
            bufferPool.lazyDestroy();
        }
    }

    @Override
    public void fail(@Nullable Throwable throwable) {
        partitionManager.releasePartition(partitionId, throwable);
    }

    public Throwable getFailureCause() {
        return cause;
    }

    @Override
    public int getNumTargetKeyGroups() {
        return numTargetKeyGroups;
    }

    @Override
    public void setMetricGroup(TaskIOMetricGroup metrics) {
        numBytesOut = metrics.getNumBytesOutCounter();
        numBuffersOut = metrics.getNumBuffersOutCounter();
    }

    /**
     * Whether this partition is released.
     *
     * <p>A partition is released when each subpartition is either consumed and communication is
     * closed by consumer or failed. A partition is also released if task is cancelled.
     */
    @Override
    public boolean isReleased() {
        return isReleased.get();
    }

    @Override
    public CompletableFuture<?> getAvailableFuture() {
        return bufferPool.getAvailableFuture();
    }

    @Override
    public String toString() {
        return "ResultPartition "
                + partitionId.toString()
                + " ["
                + partitionType
                + ", "
                + numSubpartitions
                + " subpartitions]";
    }

    // ------------------------------------------------------------------------

    /** Notification when a subpartition is released. */
    void onConsumedSubpartition(int subpartitionIndex) {

        if (isReleased.get()) {
            return;
        }

        LOG.debug(
                "{}: Received release notification for subpartition {}.", this, subpartitionIndex);
    }

    // ------------------------------------------------------------------------

    protected void checkInProduceState() throws IllegalStateException {
        checkState(!isFinished, "Partition already finished.");
    }

    @VisibleForTesting
    public ResultPartitionManager getPartitionManager() {
        return partitionManager;
    }

    /**
     * Whether the buffer can be compressed or not. Note that event is not compressed because it is
     * usually small and the size can become even larger after compression.
     */
    protected boolean canBeCompressed(Buffer buffer) {
        return bufferCompressor != null && buffer.isBuffer() && buffer.readableBytes() > 0;
    }
}
