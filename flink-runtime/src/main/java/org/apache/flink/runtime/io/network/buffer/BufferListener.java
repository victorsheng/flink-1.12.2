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

package org.apache.flink.runtime.io.network.buffer;

/**
 * BufferListener接口有两个方法：notifyBufferAvailable和notifyBufferDestroyed。
 *
 * <p>前者用来告知有新的buffer可用（可用buffer数量从无到有的时候调用），后者用于告知bufferPool已被销毁。
 *
 * <p>Interface of the availability of buffers. Listeners can opt for a one-time only notification
 * or to be notified repeatedly.
 */
public interface BufferListener {

    /** Status of the notification result from the buffer listener. */
    enum NotificationResult {
        BUFFER_NOT_USED(false, false),
        BUFFER_USED_NO_NEED_MORE(true, false),
        BUFFER_USED_NEED_MORE(true, true);

        private final boolean isBufferUsed;
        private final boolean needsMoreBuffers;

        NotificationResult(boolean isBufferUsed, boolean needsMoreBuffers) {
            this.isBufferUsed = isBufferUsed;
            this.needsMoreBuffers = needsMoreBuffers;
        }

        /**
         * Whether the notified buffer is accepted to use by the listener.
         *
         * @return <tt>true</tt> if the notified buffer is accepted.
         */
        boolean isBufferUsed() {
            return isBufferUsed;
        }

        /**
         * Whether the listener still needs more buffers to be notified.
         *
         * @return <tt>true</tt> if the listener is still waiting for more buffers.
         */
        boolean needsMoreBuffers() {
            return needsMoreBuffers;
        }
    }

    /**
     * Notification callback if a buffer is recycled and becomes available in buffer pool.
     *
     * <p>Note: responsibility on recycling the given buffer is transferred to this implementation,
     * including any errors that lead to exceptions being thrown!
     *
     * <p><strong>BEWARE:</strong> since this may be called from outside the thread that relies on
     * the listener's logic, any exception that occurs with this handler should be forwarded to the
     * responsible thread for handling and otherwise ignored in the processing of this method. The
     * buffer pool forwards any {@link Throwable} from here upwards to a potentially unrelated call
     * stack!
     *
     * @param buffer buffer that becomes available in buffer pool.
     * @return NotificationResult if the listener wants to be notified next time.
     */
    NotificationResult notifyBufferAvailable(Buffer buffer);

    /** Notification callback if the buffer provider is destroyed. */
    void notifyBufferDestroyed();
}
