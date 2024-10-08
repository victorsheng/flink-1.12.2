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
import org.apache.flink.runtime.checkpoint.CheckpointException;
import org.apache.flink.runtime.checkpoint.CheckpointFailureReason;
import org.apache.flink.runtime.checkpoint.channel.InputChannelInfo;
import org.apache.flink.runtime.io.network.api.CancelCheckpointMarker;
import org.apache.flink.runtime.io.network.api.CheckpointBarrier;
import org.apache.flink.runtime.jobgraph.tasks.AbstractInvokable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayDeque;

/**
 * The {@link CheckpointBarrierTracker} keeps track of what checkpoint barriers have been received
 * from which input channels. Once it has observed all checkpoint barriers for a checkpoint ID, it
 * notifies its listener of a completed checkpoint.
 *
 * <p>Unlike the {@link CheckpointBarrierAligner}, the BarrierTracker does not block the input
 * channels that have sent barriers, so it cannot be used to gain "exactly-once" processing
 * guarantees. It can, however, be used to gain "at least once" processing guarantees.
 *
 * <p>NOTE: This implementation strictly assumes that newer checkpoints have higher checkpoint IDs.
 */
@Internal
public class CheckpointBarrierTracker extends CheckpointBarrierHandler {

    private static final Logger LOG = LoggerFactory.getLogger(CheckpointBarrierTracker.class);

    /**
     * The tracker tracks a maximum number of checkpoints, for which some, but not all barriers have
     * yet arrived.
     */
    private static final int MAX_CHECKPOINTS_TO_TRACK = 50;

    // ------------------------------------------------------------------------

    /**
     * The number of channels. Once that many barriers have been received for a checkpoint, the
     * checkpoint is considered complete.
     */
    private final int totalNumberOfInputChannels;

    /**
     * All checkpoints for which some (but not all) barriers have been received, and that are not
     * yet known to be subsumed by newer checkpoints.
     */
    private final ArrayDeque<CheckpointBarrierCount> pendingCheckpoints;

    /** The highest checkpoint ID encountered so far. */
    private long latestPendingCheckpointID = -1;

    public CheckpointBarrierTracker(
            int totalNumberOfInputChannels, AbstractInvokable toNotifyOnCheckpoint) {
        super(toNotifyOnCheckpoint);
        this.totalNumberOfInputChannels = totalNumberOfInputChannels;
        this.pendingCheckpoints = new ArrayDeque<>();
    }

    public void processBarrier(CheckpointBarrier receivedBarrier, InputChannelInfo channelInfo)
            throws IOException {
        final long barrierId = receivedBarrier.getId();

        // fast path for single channel trackers
        if (totalNumberOfInputChannels == 1) {
            markAlignmentStartAndEnd(receivedBarrier.getTimestamp());
            notifyCheckpoint(receivedBarrier);
            return;
        }

        // general path for multiple input channels
        if (LOG.isDebugEnabled()) {
            LOG.debug("Received barrier for checkpoint {} from channel {}", barrierId, channelInfo);
        }

        // find the checkpoint barrier in the queue of pending barriers
        CheckpointBarrierCount barrierCount = null;
        int pos = 0;

        for (CheckpointBarrierCount next : pendingCheckpoints) {
            if (next.checkpointId == barrierId) {
                barrierCount = next;
                break;
            }
            pos++;
        }

        if (barrierCount != null) {
            // add one to the count to that barrier and check for completion
            int numBarriersNew = barrierCount.incrementBarrierCount();
            if (numBarriersNew == totalNumberOfInputChannels) {
                // checkpoint can be triggered (or is aborted and all barriers have been seen)
                // first, remove this checkpoint and all all prior pending
                // checkpoints (which are now subsumed)

                // 在当前 barrierId 前面的所有未完成的 checkpoint 都可以丢弃了
                for (int i = 0; i <= pos; i++) {
                    pendingCheckpoints.pollFirst();
                }

                // notify the listener
                if (!barrierCount.isAborted()) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Received all barriers for checkpoint {}", barrierId);
                    }
                    markAlignmentEnd();
                    // 通知进行 checkpoint
                    notifyCheckpoint(receivedBarrier);
                }
            }
        } else {
            // first barrier for that checkpoint ID
            // add it only if it is newer than the latest checkpoint.
            // if it is not newer than the latest checkpoint ID, then there cannot be a
            // successful checkpoint for that ID anyways
            if (barrierId > latestPendingCheckpointID) {
                markAlignmentStart(receivedBarrier.getTimestamp());
                latestPendingCheckpointID = barrierId;
                pendingCheckpoints.addLast(new CheckpointBarrierCount(barrierId));

                // make sure we do not track too many checkpoints
                if (pendingCheckpoints.size() > MAX_CHECKPOINTS_TO_TRACK) {
                    pendingCheckpoints.pollFirst();
                }
            }
        }
    }

    @Override
    public void processBarrierAnnouncement(
            CheckpointBarrier announcedBarrier, int sequenceNumber, InputChannelInfo channelInfo)
            throws IOException {
        // Ignore
    }

    @Override
    public void processCancellationBarrier(CancelCheckpointMarker cancelBarrier)
            throws IOException {
        final long checkpointId = cancelBarrier.getCheckpointId();

        if (LOG.isDebugEnabled()) {
            LOG.debug("Received cancellation barrier for checkpoint {}", checkpointId);
        }

        // fast path for single channel trackers
        if (totalNumberOfInputChannels == 1) {
            notifyAbortOnCancellationBarrier(checkpointId);
            return;
        }

        // -- general path for multiple input channels --

        // find the checkpoint barrier in the queue of pending barriers
        // while doing this we "abort" all checkpoints before that one
        CheckpointBarrierCount cbc;
        while ((cbc = pendingCheckpoints.peekFirst()) != null
                && cbc.checkpointId() < checkpointId) {
            pendingCheckpoints.removeFirst();

            if (cbc.markAborted()) {
                // abort the subsumed checkpoints if not already done
                notifyAbortOnCancellationBarrier(cbc.checkpointId());
            }
        }

        if (cbc != null && cbc.checkpointId() == checkpointId) {
            // make sure the checkpoint is remembered as aborted
            if (cbc.markAborted()) {
                // this was the first time the checkpoint was aborted - notify
                notifyAbortOnCancellationBarrier(checkpointId);
            }

            // we still count the barriers to be able to remove the entry once all barriers have
            // been seen
            if (cbc.incrementBarrierCount() == totalNumberOfInputChannels) {
                // we can remove this entry
                pendingCheckpoints.removeFirst();
            }
        } else if (checkpointId > latestPendingCheckpointID) {
            notifyAbortOnCancellationBarrier(checkpointId);

            latestPendingCheckpointID = checkpointId;

            CheckpointBarrierCount abortedMarker = new CheckpointBarrierCount(checkpointId);
            abortedMarker.markAborted();
            pendingCheckpoints.addFirst(abortedMarker);

            // we have removed all other pending checkpoint barrier counts --> no need to check that
            // we don't exceed the maximum checkpoints to track
        } else {
            // trailing cancellation barrier which was already cancelled
        }
    }

    @Override
    public void processEndOfPartition() throws IOException {
        while (!pendingCheckpoints.isEmpty()) {
            CheckpointBarrierCount barrierCount = pendingCheckpoints.removeFirst();
            if (barrierCount.markAborted()) {
                notifyAbort(
                        barrierCount.checkpointId(),
                        new CheckpointException(
                                CheckpointFailureReason.CHECKPOINT_DECLINED_INPUT_END_OF_STREAM));
            }
        }
    }

    public long getLatestCheckpointId() {
        return pendingCheckpoints.isEmpty() ? -1 : pendingCheckpoints.peekLast().checkpointId();
    }

    public boolean isCheckpointPending() {
        return !pendingCheckpoints.isEmpty();
    }

    /** Simple class for a checkpoint ID with a barrier counter. */
    private static final class CheckpointBarrierCount {

        private final long checkpointId;

        private int barrierCount;

        private boolean aborted;

        CheckpointBarrierCount(long checkpointId) {
            this.checkpointId = checkpointId;
            this.barrierCount = 1;
        }

        public long checkpointId() {
            return checkpointId;
        }

        public int incrementBarrierCount() {
            return ++barrierCount;
        }

        public boolean isAborted() {
            return aborted;
        }

        public boolean markAborted() {
            boolean firstAbort = !this.aborted;
            this.aborted = true;
            return firstAbort;
        }

        @Override
        public String toString() {
            return isAborted()
                    ? String.format("checkpointID=%d - ABORTED", checkpointId)
                    : String.format("checkpointID=%d, count=%d", checkpointId, barrierCount);
        }
    }
}
