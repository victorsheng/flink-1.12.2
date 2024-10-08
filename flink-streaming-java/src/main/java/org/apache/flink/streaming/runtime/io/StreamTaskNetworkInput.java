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

package org.apache.flink.streaming.runtime.io;

import org.apache.flink.annotation.Internal;
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.core.io.InputStatus;
import org.apache.flink.runtime.checkpoint.channel.ChannelStateWriter;
import org.apache.flink.runtime.checkpoint.channel.InputChannelInfo;
import org.apache.flink.runtime.event.AbstractEvent;
import org.apache.flink.runtime.io.disk.iomanager.IOManager;
import org.apache.flink.runtime.io.network.api.EndOfPartitionEvent;
import org.apache.flink.runtime.io.network.api.serialization.RecordDeserializer;
import org.apache.flink.runtime.io.network.api.serialization.RecordDeserializer.DeserializationResult;
import org.apache.flink.runtime.io.network.api.serialization.SpillingAdaptiveSpanningRecordDeserializer;
import org.apache.flink.runtime.io.network.buffer.Buffer;
import org.apache.flink.runtime.io.network.partition.consumer.BufferOrEvent;
import org.apache.flink.runtime.plugable.DeserializationDelegate;
import org.apache.flink.runtime.plugable.NonReusingDeserializationDelegate;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.apache.flink.streaming.runtime.streamrecord.StreamElement;
import org.apache.flink.streaming.runtime.streamrecord.StreamElementSerializer;
import org.apache.flink.streaming.runtime.streamstatus.StatusWatermarkValve;
import org.apache.flink.streaming.runtime.streamstatus.StreamStatus;
import org.apache.flink.util.Preconditions;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;
import static org.apache.flink.util.Preconditions.checkNotNull;
import static org.apache.flink.util.Preconditions.checkState;

/**
 * Implementation of {@link StreamTaskInput} that wraps an input from network taken from {@link
 * CheckpointedInputGate}.
 *
 * <p>This internally uses a {@link StatusWatermarkValve} to keep track of {@link Watermark} and
 * {@link StreamStatus} events, and forwards them to event subscribers once the {@link
 * StatusWatermarkValve} determines the {@link Watermark} from all inputs has advanced, or that a
 * {@link StreamStatus} needs to be propagated downstream to denote a status change.
 *
 * <p>Forwarding elements, watermarks, or status elements must be protected by synchronizing on the
 * given lock object. This ensures that we don't call methods on a {@link StreamInputProcessor}
 * concurrently with the timer callback or other things.
 */
@Internal
public final class StreamTaskNetworkInput<T> implements StreamTaskInput<T> {

    private final CheckpointedInputGate checkpointedInputGate;

    private final DeserializationDelegate<StreamElement> deserializationDelegate;

    private final Map<InputChannelInfo, RecordDeserializer<DeserializationDelegate<StreamElement>>>
            recordDeserializers;
    private final Map<InputChannelInfo, Integer> flattenedChannelIndices;

    /** Valve that controls how watermarks and stream statuses are forwarded. */
    private final StatusWatermarkValve statusWatermarkValve;

    private final int inputIndex;

    private InputChannelInfo lastChannel = null;

    private RecordDeserializer<DeserializationDelegate<StreamElement>> currentRecordDeserializer =
            null;

    public StreamTaskNetworkInput(
            CheckpointedInputGate checkpointedInputGate,
            TypeSerializer<?> inputSerializer,
            IOManager ioManager,
            StatusWatermarkValve statusWatermarkValve,
            int inputIndex) {
        this.checkpointedInputGate = checkpointedInputGate;
        this.deserializationDelegate =
                new NonReusingDeserializationDelegate<>(
                        new StreamElementSerializer<>(inputSerializer));

        // Initialize one deserializer per input channel
        this.recordDeserializers =
                checkpointedInputGate.getChannelInfos().stream()
                        .collect(
                                toMap(
                                        identity(),
                                        unused ->
                                                new SpillingAdaptiveSpanningRecordDeserializer<>(
                                                        ioManager.getSpillingDirectoriesPaths())));

        this.flattenedChannelIndices = new HashMap<>();
        for (InputChannelInfo i : checkpointedInputGate.getChannelInfos()) {
            flattenedChannelIndices.put(i, flattenedChannelIndices.size());
        }

        this.statusWatermarkValve = checkNotNull(statusWatermarkValve);
        this.inputIndex = inputIndex;
    }

    @VisibleForTesting
    StreamTaskNetworkInput(
            CheckpointedInputGate checkpointedInputGate,
            TypeSerializer<?> inputSerializer,
            StatusWatermarkValve statusWatermarkValve,
            int inputIndex,
            RecordDeserializer<DeserializationDelegate<StreamElement>>[] recordDeserializers) {
        Preconditions.checkArgument(
                checkpointedInputGate.getChannelInfos().stream()
                                .map(InputChannelInfo::getGateIdx)
                                .distinct()
                                .count()
                        <= 1);
        Preconditions.checkArgument(
                checkpointedInputGate.getNumberOfInputChannels() == recordDeserializers.length);

        this.checkpointedInputGate = checkpointedInputGate;
        this.deserializationDelegate =
                new NonReusingDeserializationDelegate<>(
                        new StreamElementSerializer<>(inputSerializer));
        this.recordDeserializers =
                checkpointedInputGate.getChannelInfos().stream()
                        .collect(
                                toMap(
                                        identity(),
                                        info -> recordDeserializers[info.getInputChannelIdx()]));
        this.flattenedChannelIndices = new HashMap<>();
        for (InputChannelInfo i : checkpointedInputGate.getChannelInfos()) {
            flattenedChannelIndices.put(i, flattenedChannelIndices.size());
        }

        this.statusWatermarkValve = statusWatermarkValve;
        this.inputIndex = inputIndex;
    }

    // 该方法负责从InputGate中拉取数据，数据被反序列化器反序列化之后发往operator。
    @Override
    public InputStatus emitNext(DataOutput<T> output) throws Exception {

        while (true) {
            // get the stream element from the deserializer
            if (currentRecordDeserializer != null) {
                DeserializationResult result;
                try {
                    // 从buffer的memorySegment中反序列化数据
                    result = currentRecordDeserializer.getNextRecord(deserializationDelegate);
                } catch (IOException e) {
                    throw new IOException(
                            String.format("Can't get next record for channel %s", lastChannel), e);
                }

                // 关键点
                // 如果buffer中的数据已经被反序列化完毕
                // result.isBufferConsumed()返回true
                // 调用反序列化器中内存块的recycleBuffer方法。

                // 如果buffer已经消费了，可以回收buffer
                if (result.isBufferConsumed()) {
                    // 在这里currentRecordDeserializer.
                    // getCurrentBuffer()是NetworkBuffer类型。
                    currentRecordDeserializer.getCurrentBuffer().recycleBuffer();
                    currentRecordDeserializer = null;
                }

                // 如果已经读取到完整记录
                if (result.isFullRecord()) {
                    // 处理数据
                    processElement(deserializationDelegate.getInstance(), output);
                    return InputStatus.MORE_AVAILABLE;
                }
            }

            // 从CheckpointInputGate读取数据
            Optional<BufferOrEvent> bufferOrEvent = checkpointedInputGate.pollNext();
            if (bufferOrEvent.isPresent()) {

                // return to the mailbox after receiving a checkpoint barrier to avoid processing of
                // data after the barrier before checkpoint is performed for unaligned checkpoint
                // mode

                if (bufferOrEvent.get().isBuffer()) {
                    // 如果是buffer的话
                    processBuffer(bufferOrEvent.get());
                } else {
                    // 如果接收到的是even
                    processEvent(bufferOrEvent.get());
                    return InputStatus.MORE_AVAILABLE;
                }
            } else {
                // 如果checkpointedInputGate 输入流结束，返回END_OF_INPUT
                if (checkpointedInputGate.isFinished()) {
                    checkState(
                            checkpointedInputGate.getAvailableFuture().isDone(),
                            "Finished BarrierHandler should be available");
                    return InputStatus.END_OF_INPUT;
                }
                return InputStatus.NOTHING_AVAILABLE;
            }
        }
    }

    // 处理任务...
    private void processElement(StreamElement recordOrMark, DataOutput<T> output) throws Exception {

        if (recordOrMark.isRecord()) {
            //  [ 重点 ]  如果是数据
            // OneInputStreamTask $ StreamTaskNetworkOutput#emitRecord
            output.emitRecord(recordOrMark.asRecord());

        } else if (recordOrMark.isWatermark()) {
            // 如果是 Watermark ...
            statusWatermarkValve.inputWatermark(
                    recordOrMark.asWatermark(), flattenedChannelIndices.get(lastChannel), output);
        } else if (recordOrMark.isLatencyMarker()) {
            // 如果是 迟到的数据
            output.emitLatencyMarker(recordOrMark.asLatencyMarker());
        } else if (recordOrMark.isStreamStatus()) {
            // 如果是 StreamStatus
            statusWatermarkValve.inputStreamStatus(
                    recordOrMark.asStreamStatus(),
                    flattenedChannelIndices.get(lastChannel),
                    output);
        } else {
            throw new UnsupportedOperationException("Unknown type of StreamElement");
        }
    }

    private void processEvent(BufferOrEvent bufferOrEvent) {
        // Event received
        final AbstractEvent event = bufferOrEvent.getEvent();
        // TODO: with checkpointedInputGate.isFinished() we might not need to support any events on
        // this level.
        if (event.getClass() == EndOfPartitionEvent.class) {
            // release the record deserializer immediately,
            // which is very valuable in case of bounded stream

            //  清除channel对应的反序列化器
            // 并将recordDeserializers[channelIndex] 引用置空
            releaseDeserializer(bufferOrEvent.getChannelInfo());
        }
    }

    private void processBuffer(BufferOrEvent bufferOrEvent) throws IOException {

        // 读取buffer对应的channel id
        lastChannel = bufferOrEvent.getChannelInfo();
        checkState(lastChannel != null);
        // 获取channel对应的record反序列化器
        currentRecordDeserializer = recordDeserializers.get(lastChannel);
        checkState(
                currentRecordDeserializer != null,
                "currentRecordDeserializer has already been released");

        // 此处是关键，设置反序列化器要读取的buffer为inputGate获取到的buffer
        currentRecordDeserializer.setNextBuffer(bufferOrEvent.getBuffer());
    }

    @Override
    public int getInputIndex() {
        return inputIndex;
    }

    @Override
    public CompletableFuture<?> getAvailableFuture() {
        if (currentRecordDeserializer != null) {
            return AVAILABLE;
        }
        return checkpointedInputGate.getAvailableFuture();
    }

    @Override
    public CompletableFuture<Void> prepareSnapshot(
            ChannelStateWriter channelStateWriter, long checkpointId) throws IOException {
        for (Map.Entry<InputChannelInfo, RecordDeserializer<DeserializationDelegate<StreamElement>>>
                e : recordDeserializers.entrySet()) {

            channelStateWriter.addInputData(
                    checkpointId,
                    e.getKey(),
                    ChannelStateWriter.SEQUENCE_NUMBER_UNKNOWN,
                    e.getValue().getUnconsumedBuffer());
        }
        return checkpointedInputGate.getAllBarriersReceivedFuture(checkpointId);
    }

    @Override
    public void close() throws IOException {
        // release the deserializers . this part should not ever fail
        for (InputChannelInfo channelInfo : new HashSet<>(recordDeserializers.keySet())) {
            releaseDeserializer(channelInfo);
        }

        // cleanup the resources of the checkpointed input gate
        checkpointedInputGate.close();
    }

    private void releaseDeserializer(InputChannelInfo channelInfo) {
        RecordDeserializer<?> deserializer = recordDeserializers.get(channelInfo);
        if (deserializer != null) {
            // recycle buffers and clear the deserializer.
            Buffer buffer = deserializer.getCurrentBuffer();
            if (buffer != null && !buffer.isRecycled()) {
                buffer.recycleBuffer();
            }
            deserializer.clear();

            recordDeserializers.remove(channelInfo);
        }
    }
}
