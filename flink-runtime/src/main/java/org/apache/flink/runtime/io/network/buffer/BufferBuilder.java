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

import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.core.memory.MemorySegment;

import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;

import java.nio.ByteBuffer;

import static org.apache.flink.util.Preconditions.checkNotNull;
import static org.apache.flink.util.Preconditions.checkState;

/**
 * 不是线程安全类，用于填充{@link MemorySegment}的内容。 要访问写入的数据，请使用{@link BufferConsumer}，它允许从写入的数据构建{@link
 * Buffer}实例。
 *
 * <p>Not thread safe class for filling in the content of the {@link MemorySegment}.
 *
 * <p>To access written data please use {@link BufferConsumer} which allows to build {@link Buffer}
 * instances from the written data.
 */
@NotThreadSafe
public class BufferBuilder {
    private final MemorySegment memorySegment;

    private final BufferRecycler recycler;

    private final SettablePositionMarker positionMarker = new SettablePositionMarker();

    private boolean bufferConsumerCreated = false;

    public BufferBuilder(MemorySegment memorySegment, BufferRecycler recycler) {
        this.memorySegment = checkNotNull(memorySegment);
        this.recycler = checkNotNull(recycler);
    }

    /**
     * 此方法始终从当前writer offset 开始创建{@link BufferConsumer}。
     *
     * <p>在创建{@link BufferConsumer}之前写入{@link BufferBuilder}的数据对于该{@link BufferConsumer}将不可见。
     *
     * <p>This method always creates a {@link BufferConsumer} starting from the current writer
     * offset. Data written to {@link BufferBuilder} before creation of {@link BufferConsumer} won't
     * be visible for that {@link BufferConsumer}.
     *
     * @return created matching instance of {@link BufferConsumer} to this {@link BufferBuilder}.
     */
    public BufferConsumer createBufferConsumer() {
        return createBufferConsumer(positionMarker.cachedPosition);
    }

    /**
     * This method always creates a {@link BufferConsumer} starting from position 0 of {@link
     * MemorySegment}.
     *
     * @return created matching instance of {@link BufferConsumer} to this {@link BufferBuilder}.
     */
    public BufferConsumer createBufferConsumerFromBeginning() {
        return createBufferConsumer(0);
    }

    // 从指定的offset位置, 构建BufferConsumer .
    private BufferConsumer createBufferConsumer(int currentReaderPosition) {
        checkState(
                !bufferConsumerCreated, "Two BufferConsumer shouldn't exist for one BufferBuilder");
        // 设置bufferConsumerCreated 为true
        bufferConsumerCreated = true;
        // 构建BufferConsumer
        return new BufferConsumer(memorySegment, recycler, positionMarker, currentReaderPosition);
    }

    /** Same as {@link #append(ByteBuffer)} but additionally {@link #commit()} the appending. */
    public int appendAndCommit(ByteBuffer source) {
        int writtenBytes = append(source);
        commit();
        return writtenBytes;
    }

    /**
     * Append as many data as possible from {@code source}. Not everything might be copied if there
     * is not enough space in the underlying {@link MemorySegment}
     *
     * @return number of copied bytes
     */
    public int append(ByteBuffer source) {
        checkState(!isFinished());

        int needed = source.remaining();
        int available = getMaxCapacity() - positionMarker.getCached();
        int toCopy = Math.min(needed, available);

        memorySegment.put(positionMarker.getCached(), source, toCopy);
        positionMarker.move(toCopy);
        return toCopy;
    }

    /**
     * Make the change visible to the readers. This is costly operation (volatile access) thus in
     * case of bulk writes it's better to commit them all together instead one by one.
     */
    public void commit() {
        positionMarker.commit();
    }

    /**
     * Mark this {@link BufferBuilder} and associated {@link BufferConsumer} as finished - no new
     * data writes will be allowed.
     *
     * <p>This method should be idempotent to handle failures and task interruptions. Check
     * FLINK-8948 for more details.
     *
     * @return number of written bytes.
     */
    public int finish() {
        // 获取当前写入的位置
        int writtenBytes = positionMarker.markFinished();
        // 执行commit操作, 移动positionMarker 指针...
        commit();

        return writtenBytes;
    }

    public boolean isFinished() {
        return positionMarker.isFinished();
    }

    public boolean isFull() {
        checkState(positionMarker.getCached() <= getMaxCapacity());
        return positionMarker.getCached() == getMaxCapacity();
    }

    public int getWritableBytes() {
        checkState(positionMarker.getCached() <= getMaxCapacity());
        return getMaxCapacity() - positionMarker.getCached();
    }

    public int getCommittedBytes() {
        return positionMarker.getCached();
    }

    public int getMaxCapacity() {
        return memorySegment.size();
    }

    @VisibleForTesting
    public BufferRecycler getRecycler() {
        return recycler;
    }

    public void recycle() {
        recycler.recycle(memorySegment);
    }

    @VisibleForTesting
    public MemorySegment getMemorySegment() {
        return memorySegment;
    }

    /**
     * Holds a reference to the current writer position. Negative values indicate that writer
     * ({@link BufferBuilder} has finished. Value {@code Integer.MIN_VALUE} represents finished
     * empty buffer.
     */
    @ThreadSafe
    interface PositionMarker {
        int FINISHED_EMPTY = Integer.MIN_VALUE;

        int get();

        static boolean isFinished(int position) {
            return position < 0;
        }

        static int getAbsolute(int position) {
            if (position == FINISHED_EMPTY) {
                return 0;
            }
            return Math.abs(position);
        }
    }

    /**
     * Cached writing implementation of {@link PositionMarker}.
     *
     * <p>Writer ({@link BufferBuilder}) and reader ({@link BufferConsumer}) caches must be
     * implemented independently of one another - so that the cached values can not accidentally
     * leak from one to another.
     *
     * <p>Remember to commit the {@link SettablePositionMarker} to make the changes visible.
     */
    static class SettablePositionMarker implements PositionMarker {
        private volatile int position = 0;

        /**
         * Locally cached value of volatile {@code position} to avoid unnecessary volatile accesses.
         */
        private int cachedPosition = 0;

        @Override
        public int get() {
            return position;
        }

        public boolean isFinished() {
            return PositionMarker.isFinished(cachedPosition);
        }

        public int getCached() {
            return PositionMarker.getAbsolute(cachedPosition);
        }

        /**
         * Marks this position as finished and returns the current position.
         *
         * @return current position as of {@link #getCached()}
         */
        public int markFinished() {
            int currentPosition = getCached();
            int newValue = -currentPosition;
            if (newValue == 0) {
                newValue = FINISHED_EMPTY;
            }
            set(newValue);
            return currentPosition;
        }

        public void move(int offset) {
            set(cachedPosition + offset);
        }

        public void set(int value) {
            cachedPosition = value;
        }

        public void commit() {
            position = cachedPosition;
        }
    }
}
