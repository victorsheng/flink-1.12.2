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

package org.apache.flink.streaming.api.functions.source;

import org.apache.flink.annotation.PublicEvolving;
import org.apache.flink.util.IOUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;

import static org.apache.flink.util.NetUtils.isValidClientPort;
import static org.apache.flink.util.Preconditions.checkArgument;
import static org.apache.flink.util.Preconditions.checkNotNull;

/**
 * A source function that reads strings from a socket. The source will read bytes from the socket
 * stream and convert them to characters, each byte individually. When the delimiter character is
 * received, the function will output the current string, and begin a new string.
 *
 * <p>The function strips trailing <i>carriage return</i> characters (\r) when the delimiter is the
 * newline character (\n).
 *
 * <p>The function can be set to reconnect to the server socket in case that the stream is closed on
 * the server side.
 */
@PublicEvolving
public class SocketTextStreamFunction implements SourceFunction<String> {

    private static final long serialVersionUID = 1L;

    private static final Logger LOG = LoggerFactory.getLogger(SocketTextStreamFunction.class);

    /** Default delay between successive connection attempts. */
    private static final int DEFAULT_CONNECTION_RETRY_SLEEP = 500;

    /** Default connection timeout when connecting to the server socket (infinite). */
    private static final int CONNECTION_TIMEOUT_TIME = 0;

    // 主机地址
    private final String hostname;
    // 端口
    private final int port;
    // 分隔符
    private final String delimiter;
    // 最大重试次数
    private final long maxNumRetries;
    // 重试间隔
    private final long delayBetweenRetries;

    // 构建的socket
    private transient Socket currentSocket;

    // 标识符,是否正在运行
    private volatile boolean isRunning = true;

    public SocketTextStreamFunction(
            String hostname, int port, String delimiter, long maxNumRetries) {
        this(hostname, port, delimiter, maxNumRetries, DEFAULT_CONNECTION_RETRY_SLEEP);
    }

    public SocketTextStreamFunction(
            String hostname,
            int port,
            String delimiter,
            long maxNumRetries,
            long delayBetweenRetries) {
        checkArgument(isValidClientPort(port), "port is out of range");
        checkArgument(
                maxNumRetries >= -1,
                "maxNumRetries must be zero or larger (num retries), or -1 (infinite retries)");
        checkArgument(delayBetweenRetries >= 0, "delayBetweenRetries must be zero or positive");

        this.hostname = checkNotNull(hostname, "hostname must not be null");
        this.port = port;
        this.delimiter = delimiter;
        this.maxNumRetries = maxNumRetries;
        this.delayBetweenRetries = delayBetweenRetries;
    }

    //
    //    this.ctx = {StreamSourceContexts$ManualWatermarkContext@7657}
    //        output = {CountingOutput@7212}
    //        reuse = {StreamRecord@7658} "Record @ (undef) : null"
    //        timeService = {ProcessingTimeServiceImpl@7217}
    //        checkpointLock = {Object@7183}
    //        streamStatusMaintainer = {OperatorChain@7182}
    //        idleTimeout = -1
    //        nextCheck = null
    //        failOnNextCheck = false
    @Override
    public void run(SourceContext<String> ctx) throws Exception {

        // 构建buffer 缓存...
        final StringBuilder buffer = new StringBuilder();

        // 重试次数
        long attempt = 0;

        // 是否运行
        while (isRunning) {

            // 构建socket
            try (Socket socket = new Socket()) {

                // 设置当前currentSocket
                currentSocket = socket;

                LOG.info("Connecting to server socket " + hostname + ':' + port);
                // 设置连接信息和超时时间
                socket.connect(new InetSocketAddress(hostname, port), CONNECTION_TIMEOUT_TIME);

                // 读取socket数据...
                try (BufferedReader reader =
                        new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

                    char[] cbuf = new char[8192];
                    int bytesRead;
                    while (isRunning && (bytesRead = reader.read(cbuf)) != -1) {
                        buffer.append(cbuf, 0, bytesRead);
                        int delimPos;

                        // 读取数据...
                        while (buffer.length() >= delimiter.length()
                                && (delimPos = buffer.indexOf(delimiter)) != -1) {

                            // 获取数据记录...
                            String record = buffer.substring(0, delimPos);
                            // truncate trailing carriage return
                            if (delimiter.equals("\n") && record.endsWith("\r")) {
                                record = record.substring(0, record.length() - 1);
                            }
                            // 处理数据
                            // StreamSourceContexts#collect
                            ctx.collect(record);
                            // 清理掉buffer中的缓存数据...
                            buffer.delete(0, delimPos + delimiter.length());
                        }
                    }
                }
            }

            // 如果退出当前循环则重试操作...
            // if we dropped out of this loop due to an EOF, sleep and retry
            if (isRunning) {
                // 重试次数相关处理.
                attempt++;
                if (maxNumRetries == -1 || attempt < maxNumRetries) {
                    LOG.warn(
                            "Lost connection to server socket. Retrying in "
                                    + delayBetweenRetries
                                    + " msecs...");
                    Thread.sleep(delayBetweenRetries);
                } else {
                    // this should probably be here, but some examples expect simple exists of the
                    // stream source
                    // throw new EOFException("Reached end of stream and reconnects are not
                    // enabled.");
                    break;
                }
            }
        }

        // 最后操作...
        // collect trailing data
        if (buffer.length() > 0) {
            ctx.collect(buffer.toString());
        }
    }

    @Override
    public void cancel() {
        isRunning = false;

        // we need to close the socket as well, because the Thread.interrupt() function will
        // not wake the thread in the socketStream.read() method when blocked.
        Socket theSocket = this.currentSocket;
        if (theSocket != null) {
            IOUtils.closeSocket(theSocket);
        }
    }
}
