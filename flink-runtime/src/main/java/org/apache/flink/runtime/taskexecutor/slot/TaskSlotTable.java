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

package org.apache.flink.runtime.taskexecutor.slot;

import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.api.common.JobID;
import org.apache.flink.api.common.time.Time;
import org.apache.flink.runtime.clusterframework.types.AllocationID;
import org.apache.flink.runtime.clusterframework.types.ResourceID;
import org.apache.flink.runtime.clusterframework.types.ResourceProfile;
import org.apache.flink.runtime.concurrent.ComponentMainThreadExecutor;
import org.apache.flink.runtime.executiongraph.ExecutionAttemptID;
import org.apache.flink.runtime.memory.MemoryManager;
import org.apache.flink.runtime.taskexecutor.SlotReport;
import org.apache.flink.util.AutoCloseableAsync;

import javax.annotation.Nullable;

import java.util.Iterator;
import java.util.Set;
import java.util.UUID;

/**
 * 多个{@link TaskSlot}实例的容器。 此外，它还维护多个索引，以便更快地访问任务和分配的slots。 当slots无法分配给job manager时会自动超时... 在使用
 * TaskSlotTable 之前，必须通过 {@link #start} 方法启动它。
 *
 * <p>Container for multiple {@link TaskSlot} instances.
 *
 * <p>Additionally, it maintains multiple indices for faster access to tasks and sets of allocated
 * slots.
 *
 * <p>The task slot table automatically registers timeouts for allocated slots which cannot be
 * assigned to a job manager.
 *
 * <p>Before the task slot table can be used, it must be started via the {@link #start} method.
 */
public interface TaskSlotTable<T extends TaskSlotPayload>
        extends TimeoutListener<AllocationID>, AutoCloseableAsync {
    /**
     * 根据给定的 slot actions 启动 task slot table Start the task slot table with the given slot actions.
     *
     * @param initialSlotActions to use for slot actions
     * @param mainThreadExecutor {@link ComponentMainThreadExecutor} to schedule internal calls to
     *     the main thread
     */
    void start(SlotActions initialSlotActions, ComponentMainThreadExecutor mainThreadExecutor);

    /**
     * 返回job所有的AllocationID Returns the all {@link AllocationID} for the given job.
     *
     * @param jobId for which to return the set of {@link AllocationID}.
     * @return Set of {@link AllocationID} for the given job
     */
    Set<AllocationID> getAllocationIdsPerJob(JobID jobId);

    /**
     * 返回TaskSlotTable中 所有active task的 AllocationID
     *
     * <p>Returns the {@link AllocationID} of any active task listed in this {@code TaskSlotTable}.
     *
     * @return The {@code AllocationID} of any active task.
     */
    Set<AllocationID> getActiveTaskSlotAllocationIds();

    /**
     * 返回 jobId 中 acitve TaskSlot 中所有的 AllocationID Returns the {@link AllocationID} of active
     * {@link TaskSlot}s attached to the job with the given {@link JobID}.
     *
     * @param jobId The {@code JobID} of the job for which the {@code AllocationID}s of the attached
     *     active {@link TaskSlot}s shall be returned.
     * @return A set of {@code AllocationID}s that belong to active {@code TaskSlot}s having the
     *     passed {@code JobID}.
     */
    Set<AllocationID> getActiveTaskSlotAllocationIdsPerJob(JobID jobId);

    /**
     * 返回 SlotReport
     *
     * @param resourceId
     * @return
     */
    SlotReport createSlotReport(ResourceID resourceId);

    /**
     * 为给定job 和allocation id 分配具有给定索引的slot。
     *
     * <p>如果给定负指数，则生成一个新的自增指数。
     *
     * <p>如果可以分配slot，则返回true。否则返回false。
     *
     * <p>Allocate the slot with the given index for the given job and allocation id.
     *
     * <p>If negative index is given, a new auto increasing index will be generated. Returns true if
     * the slot could be allocated. Otherwise it returns false.
     *
     * @param index of the task slot to allocate, use negative value for dynamic slot allocation
     * @param jobId to allocate the task slot for
     * @param allocationId identifying the allocation
     * @param slotTimeout until the slot times out
     * @return True if the task slot could be allocated; otherwise false
     */
    @VisibleForTesting
    boolean allocateSlot(int index, JobID jobId, AllocationID allocationId, Time slotTimeout);

    /**
     * Allocate the slot with the given index for the given job and allocation id. If negative index
     * is given, a new auto increasing index will be generated. Returns true if the slot could be
     * allocated. Otherwise it returns false.
     *
     * @param index of the task slot to allocate, use negative value for dynamic slot allocation
     * @param jobId to allocate the task slot for
     * @param allocationId identifying the allocation
     * @param resourceProfile of the requested slot, used only for dynamic slot allocation and will
     *     be ignored otherwise
     * @param slotTimeout until the slot times out
     * @return True if the task slot could be allocated; otherwise false
     */
    boolean allocateSlot(
            int index,
            JobID jobId,
            AllocationID allocationId,
            ResourceProfile resourceProfile,
            Time slotTimeout);

    /**
     * 将给定分配id下的slot标记为活动。 如果找不到slot，则抛出{@link SlotNotFoundException}。
     *
     * <p>Marks the slot under the given allocation id as active. If the slot could not be found,
     * then a {@link SlotNotFoundException} is thrown.
     *
     * @param allocationId to identify the task slot to mark as active
     * @throws SlotNotFoundException if the slot could not be found for the given allocation id
     * @return True if the slot could be marked active; otherwise false
     */
    boolean markSlotActive(AllocationID allocationId) throws SlotNotFoundException;

    /**
     * 将给定分配id下的slot标记为非活动。
     *
     * <p>如果找不到slot，则抛出{@link SlotNotFoundException}。
     *
     * <p>Marks the slot under the given allocation id as inactive. If the slot could not be found,
     * then a {@link SlotNotFoundException} is thrown.
     *
     * @param allocationId to identify the task slot to mark as inactive
     * @param slotTimeout until the slot times out
     * @throws SlotNotFoundException if the slot could not be found for the given allocation id
     * @return True if the slot could be marked inactive
     */
    boolean markSlotInactive(AllocationID allocationId, Time slotTimeout)
            throws SlotNotFoundException;

    /**
     * 试着释放这个slot。 如果slot为空，它将任务slot的状态设置为free并返回其索引 如果slot不是空的，那么它会将任务slot的状态设置为releasing，fail all
     * tasks并返回-1。
     *
     * <p>Try to free the slot. If the slot is empty it will set the state of the task slot to free
     * and return its index.
     *
     * <p>If the slot is not empty, then it will set the state of the task slot to releasing, fail
     * all tasks and return -1.
     *
     * @param allocationId identifying the task slot to be freed
     * @throws SlotNotFoundException if there is not task slot for the given allocation id
     * @return Index of the freed slot if the slot could be freed; otherwise -1
     */
    default int freeSlot(AllocationID allocationId) throws SlotNotFoundException {
        return freeSlot(allocationId, new Exception("The task slot of this task is being freed."));
    }

    /**
     * Tries to free the slot. If the slot is empty it will set the state of the task slot to free
     * and return its index. If the slot is not empty, then it will set the state of the task slot
     * to releasing, fail all tasks and return -1.
     *
     * @param allocationId identifying the task slot to be freed
     * @param cause to fail the tasks with if slot is not empty
     * @throws SlotNotFoundException if there is not task slot for the given allocation id
     * @return Index of the freed slot if the slot could be freed; otherwise -1
     */
    int freeSlot(AllocationID allocationId, Throwable cause) throws SlotNotFoundException;

    /**
     * 根据allocation id. 检查ticket 是否超时
     *
     * <p>Check whether the timeout with ticket is valid for the given allocation id.
     *
     * @param allocationId to check against
     * @param ticket of the timeout
     * @return True if the timeout is valid; otherwise false
     */
    boolean isValidTimeout(AllocationID allocationId, UUID ticket);

    /**
     * 根据给定的 index , job和 allocation id .检车slot状态是否为 ALLOCATED Check whether the slot for the given
     * index is allocated for the given job and allocation id.
     *
     * @param index of the task slot
     * @param jobId for which the task slot should be allocated
     * @param allocationId which should match the task slot's allocation id
     * @return True if the given task slot is allocated for the given job and allocation id
     */
    boolean isAllocated(int index, JobID jobId, AllocationID allocationId);

    /**
     * 根据JobID 和 AllocationID 标记匹配的slot状态为ACTIVE(如果该slot的状态为allocated) Try to mark the specified
     * slot as active if it has been allocated by the given job.
     *
     * @param jobId of the allocated slot
     * @param allocationId identifying the allocation
     * @return True if the task slot could be marked active.
     */
    boolean tryMarkSlotActive(JobID jobId, AllocationID allocationId);

    /**
     * 检测 slot状态是否为free Check whether the task slot with the given index is free.
     *
     * @param index of the task slot
     * @return True if the task slot is free; otherwise false
     */
    boolean isSlotFree(int index);

    /**
     * 检查作业是否已分配（非活动）slot。 Check whether the job has allocated (not active) slots.
     *
     * @param jobId for which to check for allocated slots
     * @return True if there are allocated slots for the given job id.
     */
    boolean hasAllocatedSlots(JobID jobId);

    /**
     * 根据job id 返回 所有的TaskSlot Return an iterator of allocated slots for the given job id.
     *
     * @param jobId for which to return the allocated slots
     * @return Iterator of allocated slots.
     */
    Iterator<TaskSlot<T>> getAllocatedSlots(JobID jobId);

    /**
     * 根据AllocationID 返回所属的 JobID Returns the owning job of the {@link TaskSlot} identified by the
     * given {@link AllocationID}.
     *
     * @param allocationId identifying the slot for which to retrieve the owning job
     * @return Owning job of the specified {@link TaskSlot} or null if there is no slot for the
     *     given allocation id or if the slot has no owning job assigned
     */
    @Nullable
    JobID getOwningJob(AllocationID allocationId);

    /**
     * 将给定任务添加到由任务的 allocation id 标识的slot中。 Add the given task to the slot identified by the task's
     * allocation id.
     *
     * @param task to add to the task slot with the respective allocation id
     * @throws SlotNotFoundException if there was no slot for the given allocation id
     * @throws SlotNotActiveException if there was no slot active for task's job and allocation id
     * @return True if the task could be added to the task slot; otherwise false
     */
    boolean addTask(T task) throws SlotNotFoundException, SlotNotActiveException;

    /**
     * 从task slot中删除具有给定执行attempt id 的任务。
     *
     * <p>如果拥有的task slot处于释放状态并且在删除任务后为空，则通过slot actions 释放slot。
     *
     * <p>Remove the task with the given execution attempt id from its task slot.
     *
     * <p>If the owning task slot is in state releasing and empty after removing the task, the slot
     * is freed via the slot actions.
     *
     * @param executionAttemptID identifying the task to remove
     * @return The removed task if there is any for the given execution attempt id; otherwise null
     */
    T removeTask(ExecutionAttemptID executionAttemptID);

    /**
     * 根据ExecutionAttemptID 获取task Get the task for the given execution attempt id. If none could be
     * found, then return null.
     *
     * @param executionAttemptID identifying the requested task
     * @return The task for the given execution attempt id if it exist; otherwise null
     */
    T getTask(ExecutionAttemptID executionAttemptID);

    /**
     * 根据job id 获取所有的 tasks Return an iterator over all tasks for a given job.
     *
     * @param jobId identifying the job of the requested tasks
     * @return Iterator over all task for a given job
     */
    Iterator<T> getTasks(JobID jobId);

    /**
     * 根据index获取改slot所分配的AllocationID Get the current allocation for the task slot with the given
     * index.
     *
     * @param index identifying the slot for which the allocation id shall be retrieved
     * @return Allocation id of the specified slot if allocated; otherwise null
     */
    AllocationID getCurrentAllocation(int index);

    /**
     * 根据AllocationID 获取 MemoryManager Get the memory manager of the slot allocated for the task.
     *
     * @param allocationID allocation id of the slot allocated for the task
     * @return the memory manager of the slot allocated for the task
     */
    MemoryManager getTaskMemoryManager(AllocationID allocationID) throws SlotNotFoundException;
}
