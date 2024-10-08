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

package org.apache.flink.runtime.io.network.netty;

import org.apache.flink.runtime.event.TaskEvent;
import org.apache.flink.runtime.io.network.ConnectionID;
import org.apache.flink.runtime.io.network.NetworkClientHandler;
import org.apache.flink.runtime.io.network.PartitionRequestClient;
import org.apache.flink.runtime.io.network.netty.exception.LocalTransportException;
import org.apache.flink.runtime.io.network.partition.ResultPartitionID;
import org.apache.flink.runtime.io.network.partition.consumer.RemoteInputChannel;
import org.apache.flink.runtime.util.AtomicDisposableReferenceCounter;

import org.apache.flink.shaded.netty4.io.netty.channel.Channel;
import org.apache.flink.shaded.netty4.io.netty.channel.ChannelFuture;
import org.apache.flink.shaded.netty4.io.netty.channel.ChannelFutureListener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.concurrent.TimeUnit;

import static org.apache.flink.runtime.io.network.netty.NettyMessage.PartitionRequest;
import static org.apache.flink.runtime.io.network.netty.NettyMessage.TaskEventRequest;
import static org.apache.flink.util.Preconditions.checkNotNull;

/**
 * Partition request client for remote partition requests.
 *
 * <p>This client is shared by all remote input channels, which request a partition from the same
 * {@link ConnectionID}.
 */
public class NettyPartitionRequestClient implements PartitionRequestClient {

    private static final Logger LOG = LoggerFactory.getLogger(NettyPartitionRequestClient.class);

    private final Channel tcpChannel;

    private final NetworkClientHandler clientHandler;

    private final ConnectionID connectionId;

    private final PartitionRequestClientFactory clientFactory;

    /** If zero, the underlying TCP channel can be safely closed. */
    private final AtomicDisposableReferenceCounter closeReferenceCounter =
            new AtomicDisposableReferenceCounter();

    NettyPartitionRequestClient(
            Channel tcpChannel,
            NetworkClientHandler clientHandler,
            ConnectionID connectionId,
            PartitionRequestClientFactory clientFactory) {

        this.tcpChannel = checkNotNull(tcpChannel);
        this.clientHandler = checkNotNull(clientHandler);
        this.connectionId = checkNotNull(connectionId);
        this.clientFactory = checkNotNull(clientFactory);
    }

    boolean disposeIfNotUsed() {
        return closeReferenceCounter.disposeIfNotUsed();
    }

    /**
     * Increments the reference counter.
     *
     * <p>Note: the reference counter has to be incremented before returning the instance of this
     * client to ensure correct closing logic.
     */
    boolean incrementReferenceCounter() {
        return closeReferenceCounter.increment();
    }

    /**
     * Requests a remote intermediate result partition queue.
     *
     * <p>The request goes to the remote producer, for which this partition request client instance
     * has been created.
     */
    @Override
    public void requestSubpartition(
            final ResultPartitionID partitionId,
            final int subpartitionIndex,
            final RemoteInputChannel inputChannel,
            int delayMs)
            throws IOException {

        checkNotClosed();

        LOG.debug(
                "Requesting subpartition {} of partition {} with {} ms delay.",
                subpartitionIndex,
                partitionId,
                delayMs);

        // 向 NetworkClientHandler 注册当前 RemoteInputChannel
        // 单个 Task 所有的 RemoteInputChannel 的数据传输都通过这个 PartitionRequestClient 处理

        // clientHandler为CreditBasedPartitionRequestClientHandler
        // 它内部维护了input channel ID和channel的对应关系，是一个map类型变量
        // 在读取消息的时候，需要依赖该map从channel ID获取到channel对象本身

        clientHandler.addInputChannel(inputChannel);

        // 创建PartitionRequest对象
        // PartitionRequest封装了请求的 sub-partition 的信息，
        // 当前 input channel 的 ID，以及初始 credit
        final PartitionRequest request =
                new PartitionRequest(
                        partitionId,
                        subpartitionIndex,
                        inputChannel.getInputChannelId(),
                        inputChannel.getInitialCredit());

        // 发送PartitionRequest请求发送成功之后的回调函数
        final ChannelFutureListener listener =
                new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture future) throws Exception {

                        // 如果请求发送失败，要移除当前的 inputChannel
                        if (!future.isSuccess()) {
                            // map中移除这个channel
                            clientHandler.removeInputChannel(inputChannel);
                            SocketAddress remoteAddr = future.channel().remoteAddress();

                            // 为inputChannel内部的cause变量赋值，设置一个error
                            inputChannel.onError(
                                    new LocalTransportException(
                                            String.format(
                                                    "Sending the partition request to '%s' failed.",
                                                    remoteAddr),
                                            future.channel().localAddress(),
                                            future.cause()));
                        }
                    }
                };

        // 如果不需要延迟发送
        if (delayMs == 0) {
            // 通过 netty 发送请求
            ChannelFuture f = tcpChannel.writeAndFlush(request);
            f.addListener(listener);
        } else {
            // 如果需要延迟发送，调用eventLoop的schedule方法
            final ChannelFuture[] f = new ChannelFuture[1];
            tcpChannel
                    .eventLoop()
                    .schedule(
                            new Runnable() {
                                @Override
                                public void run() {
                                    f[0] = tcpChannel.writeAndFlush(request);
                                    f[0].addListener(listener);
                                }
                            },
                            delayMs,
                            TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Sends a task event backwards to an intermediate result partition producer.
     *
     * <p>Backwards task events flow between readers and writers and therefore will only work when
     * both are running at the same time, which is only guaranteed to be the case when both the
     * respective producer and consumer task run pipelined.
     */
    @Override
    public void sendTaskEvent(
            ResultPartitionID partitionId, TaskEvent event, final RemoteInputChannel inputChannel)
            throws IOException {
        checkNotClosed();

        tcpChannel
                .writeAndFlush(
                        new TaskEventRequest(event, partitionId, inputChannel.getInputChannelId()))
                .addListener(
                        new ChannelFutureListener() {
                            @Override
                            public void operationComplete(ChannelFuture future) throws Exception {
                                if (!future.isSuccess()) {
                                    SocketAddress remoteAddr = future.channel().remoteAddress();
                                    inputChannel.onError(
                                            new LocalTransportException(
                                                    String.format(
                                                            "Sending the task event to '%s' failed.",
                                                            remoteAddr),
                                                    future.channel().localAddress(),
                                                    future.cause()));
                                }
                            }
                        });
    }

    @Override
    public void notifyCreditAvailable(RemoteInputChannel inputChannel) {
        // 交给 NetworkClientHandler 处理
        clientHandler.notifyCreditAvailable(inputChannel);
    }

    @Override
    public void resumeConsumption(RemoteInputChannel inputChannel) {
        clientHandler.resumeConsumption(inputChannel);
    }

    @Override
    public void close(RemoteInputChannel inputChannel) throws IOException {

        clientHandler.removeInputChannel(inputChannel);

        if (closeReferenceCounter.decrement()) {
            // Close the TCP connection. Send a close request msg to ensure
            // that outstanding backwards task events are not discarded.
            tcpChannel
                    .writeAndFlush(new NettyMessage.CloseRequest())
                    .addListener(ChannelFutureListener.CLOSE_ON_FAILURE);

            // Make sure to remove the client from the factory
            clientFactory.destroyPartitionRequestClient(connectionId, this);
        } else {
            clientHandler.cancelRequestFor(inputChannel.getInputChannelId());
        }
    }

    private void checkNotClosed() throws IOException {
        if (closeReferenceCounter.isDisposed()) {
            final SocketAddress localAddr = tcpChannel.localAddress();
            final SocketAddress remoteAddr = tcpChannel.remoteAddress();
            throw new LocalTransportException(
                    String.format("Channel to '%s' closed.", remoteAddr), localAddr);
        }
    }
}
