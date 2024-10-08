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
import org.apache.flink.runtime.clusterframework.types.ResourceBudgetManager;
import org.apache.flink.runtime.clusterframework.types.ResourceID;
import org.apache.flink.runtime.clusterframework.types.ResourceProfile;
import org.apache.flink.runtime.clusterframework.types.SlotID;
import org.apache.flink.runtime.concurrent.ComponentMainThreadExecutor;
import org.apache.flink.runtime.concurrent.ComponentMainThreadExecutor.DummyComponentMainThreadExecutor;
import org.apache.flink.runtime.concurrent.FutureUtils;
import org.apache.flink.runtime.executiongraph.ExecutionAttemptID;
import org.apache.flink.runtime.memory.MemoryManager;
import org.apache.flink.runtime.taskexecutor.SlotReport;
import org.apache.flink.runtime.taskexecutor.SlotStatus;
import org.apache.flink.util.FlinkException;
import org.apache.flink.util.Preconditions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/** Default implementation of {@link TaskSlotTable}. */
public class TaskSlotTableImpl<T extends TaskSlotPayload> implements TaskSlotTable<T> {

    private static final Logger LOG = LoggerFactory.getLogger(TaskSlotTableImpl.class);

    /**
     * 静态slot分配中的slot数。 如果请求带索引的slot，则所请求的索引必须在[0，numberSlots）范围内。 生成slot
     * report时，即使该slot不存在，我们也应始终生成索引为[0，numberSlots）的广告位。
     *
     * <p>Number of slots in static slot allocation. If slot is requested with an index, the
     * requested index must within the range of [0, numberSlots).
     *
     * <p>When generating slot report, we should always generate slots with index in [0,
     * numberSlots) even the slot does not exist.
     */
    private final int numberSlots;

    /** 用于静态slot分配的slot资源配置文件。 Slot resource profile for static slot allocation. */
    private final ResourceProfile defaultSlotResourceProfile;

    /** Page size for memory manager. */
    private final int memoryPageSize;

    /** Timer service used to time out allocated slots. */
    private final TimerService<AllocationID> timerService;

    /** 缓存 index -> TaskSlot The list of all task slots. */
    private final Map<Integer, TaskSlot<T>> taskSlots;

    /** 缓存 AllocationID -> TaskSlot Mapping from allocation id to task slot. */
    private final Map<AllocationID, TaskSlot<T>> allocatedSlots;

    /**
     * ExecutionAttemptID -> TaskSlotMapping Mapping from execution attempt id to task and task
     * slot.
     */
    private final Map<ExecutionAttemptID, TaskSlotMapping<T>> taskSlotMappings;

    /** Mapping from job id to allocated slots for a job. */
    private final Map<JobID, Set<AllocationID>> slotsPerJob;

    /** Interface for slot actions, such as freeing them or timing them out. */
    @Nullable private SlotActions slotActions;

    /** 状态相关 : CREATED, RUNNING, CLOSING, CLOSED The table state. */
    private volatile State state;

    private final ResourceBudgetManager budgetManager;

    /** The closing future is completed when all slot are freed and state is closed. */
    private final CompletableFuture<Void> closingFuture;

    /** {@link ComponentMainThreadExecutor} to schedule internal calls to the main thread. */
    private ComponentMainThreadExecutor mainThreadExecutor =
            new DummyComponentMainThreadExecutor(
                    "TaskSlotTableImpl is not initialized with proper main thread executor, "
                            + "call to TaskSlotTableImpl#start is required");

    /** {@link Executor} for background actions, e.g. verify all managed memory released. */
    private final Executor memoryVerificationExecutor;

    public TaskSlotTableImpl(
            final int numberSlots,
            final ResourceProfile totalAvailableResourceProfile,
            final ResourceProfile defaultSlotResourceProfile,
            final int memoryPageSize,
            final TimerService<AllocationID> timerService,
            final Executor memoryVerificationExecutor) {
        Preconditions.checkArgument(
                0 < numberSlots, "The number of task slots must be greater than 0.");

        this.numberSlots = numberSlots;
        this.defaultSlotResourceProfile = Preconditions.checkNotNull(defaultSlotResourceProfile);
        this.memoryPageSize = memoryPageSize;

        this.taskSlots = new HashMap<>(numberSlots);

        this.timerService = Preconditions.checkNotNull(timerService);

        budgetManager =
                new ResourceBudgetManager(
                        Preconditions.checkNotNull(totalAvailableResourceProfile));

        allocatedSlots = new HashMap<>(numberSlots);

        taskSlotMappings = new HashMap<>(4 * numberSlots);

        slotsPerJob = new HashMap<>(4);

        slotActions = null;
        state = State.CREATED;
        closingFuture = new CompletableFuture<>();

        this.memoryVerificationExecutor = memoryVerificationExecutor;
    }

    @Override
    public void start(
            SlotActions initialSlotActions, ComponentMainThreadExecutor mainThreadExecutor) {
        Preconditions.checkState(
                state == State.CREATED,
                "The %s has to be just created before starting",
                TaskSlotTableImpl.class.getSimpleName());
        this.slotActions = Preconditions.checkNotNull(initialSlotActions);
        this.mainThreadExecutor = Preconditions.checkNotNull(mainThreadExecutor);

        timerService.start(this);
        // 修改状态为 RUNNING
        state = State.RUNNING;
    }

    @Override
    public CompletableFuture<Void> closeAsync() {
        if (state == State.CREATED) {
            state = State.CLOSED;
            closingFuture.complete(null);
        } else if (state == State.RUNNING) {
            state = State.CLOSING;
            final FlinkException cause = new FlinkException("Closing task slot table");
            CompletableFuture<Void> cleanupFuture =
                    FutureUtils.waitForAll(
                                    // 释放slot
                                    new ArrayList<>(allocatedSlots.values())
                                            .stream()
                                                    .map(slot -> freeSlotInternal(slot, cause))
                                                    .collect(Collectors.toList()))
                            .thenRunAsync(
                                    () -> {
                                        state = State.CLOSED;
                                        timerService.stop();
                                    },
                                    mainThreadExecutor);
            FutureUtils.forward(cleanupFuture, closingFuture);
        }
        return closingFuture;
    }

    @VisibleForTesting
    public boolean isClosed() {
        return state == State.CLOSED;
    }

    @Override
    public Set<AllocationID> getAllocationIdsPerJob(JobID jobId) {
        final Set<AllocationID> allocationIds = slotsPerJob.get(jobId);

        if (allocationIds == null) {
            return Collections.emptySet();
        } else {
            return Collections.unmodifiableSet(allocationIds);
        }
    }

    @Override
    public Set<AllocationID> getActiveTaskSlotAllocationIds() {
        return createAllocationIdSet(new TaskSlotIterator(TaskSlotState.ACTIVE));
    }

    @Override
    public Set<AllocationID> getActiveTaskSlotAllocationIdsPerJob(JobID jobId) {
        return createAllocationIdSet(new TaskSlotIterator(jobId, TaskSlotState.ACTIVE));
    }

    private Set<AllocationID> createAllocationIdSet(Iterator<TaskSlot<T>> taskSlotIterator) {
        Set<AllocationID> allocationIds = new HashSet<>();
        while (taskSlotIterator.hasNext()) {
            allocationIds.add(taskSlotIterator.next().getAllocationId());
        }

        return allocationIds;
    }

    // ---------------------------------------------------------------------
    // Slot report methods
    // ---------------------------------------------------------------------

    @Override
    public SlotReport createSlotReport(ResourceID resourceId) {
        List<SlotStatus> slotStatuses = new ArrayList<>();

        // 循环每一个slot
        for (int i = 0; i < numberSlots; i++) {
            // 构建SlotID
            SlotID slotId = new SlotID(resourceId, i);
            SlotStatus slotStatus;
            if (taskSlots.containsKey(i)) {
                // 该slot已经分配
                TaskSlot<T> taskSlot = taskSlots.get(i);
                // 构建 SlotStatus
                slotStatus =
                        new SlotStatus(
                                slotId,
                                taskSlot.getResourceProfile(),
                                taskSlot.getJobId(),
                                taskSlot.getAllocationId());
            } else {
                // 该slot尚未分配
                slotStatus = new SlotStatus(slotId, defaultSlotResourceProfile, null, null);
            }

            slotStatuses.add(slotStatus);
        }

        // 循环所有的 allocatedSlots 处理 异常的slot ???
        for (TaskSlot<T> taskSlot : allocatedSlots.values()) {
            // 处理 异常的slot ???
            if (taskSlot.getIndex() < 0) {
                SlotID slotID = SlotID.generateDynamicSlotID(resourceId);
                SlotStatus slotStatus =
                        new SlotStatus(
                                slotID,
                                taskSlot.getResourceProfile(),
                                taskSlot.getJobId(),
                                taskSlot.getAllocationId());
                slotStatuses.add(slotStatus);
            }
        }

        // 构建SlotReport
        final SlotReport slotReport = new SlotReport(slotStatuses);

        return slotReport;
    }

    // ---------------------------------------------------------------------
    // Slot methods
    // ---------------------------------------------------------------------

    @VisibleForTesting
    @Override
    public boolean allocateSlot(
            int index, JobID jobId, AllocationID allocationId, Time slotTimeout) {
        return allocateSlot(index, jobId, allocationId, defaultSlotResourceProfile, slotTimeout);
    }

    @Override
    public boolean allocateSlot(
            int index,
            JobID jobId,
            AllocationID allocationId,
            ResourceProfile resourceProfile,
            Time slotTimeout) {
        checkRunning();

        Preconditions.checkArgument(index < numberSlots);

        // 获取TaskSlot
        TaskSlot<T> taskSlot = allocatedSlots.get(allocationId);

        if (taskSlot != null) {
            LOG.info("Allocation ID {} is already allocated in {}.", allocationId, taskSlot);
            return false;
        }

        // 如果taskSlots 已经包含index
        if (taskSlots.containsKey(index)) {

            TaskSlot<T> duplicatedTaskSlot = taskSlots.get(index);
            LOG.info(
                    "Slot with index {} already exist, with resource profile {}, job id {} and allocation id {}.",
                    index,
                    duplicatedTaskSlot.getResourceProfile(),
                    duplicatedTaskSlot.getJobId(),
                    duplicatedTaskSlot.getAllocationId());

            return duplicatedTaskSlot.getJobId().equals(jobId)
                    && duplicatedTaskSlot.getAllocationId().equals(allocationId);
        } else if (allocatedSlots.containsKey(allocationId)) {
            return true;
        }

        // 获取 resourceProfile

        //    resourceProfile = {ResourceProfile@6124} "ResourceProfile{cpuCores=1.0000000000000000,
        // taskHeapMemory=96.000mb (100663293 bytes), taskOffHeapMemory=0 bytes,
        // managedMemory=128.000mb (134217730 bytes), networkMemory=32.000mb (33554432 bytes)}"
        //        cpuCores = {CPUResource@6139} "Resource(CPU: 1.0000000000000000)"
        //        taskHeapMemory = {MemorySize@6140} "100663293 bytes"
        //        taskOffHeapMemory = {MemorySize@6141} "0 bytes"
        //        managedMemory = {MemorySize@6142} "134217730 bytes"
        //        networkMemory = {MemorySize@6143} "32 mb"
        //        extendedResources = {HashMap@6144}  size = 0
        resourceProfile = index >= 0 ? defaultSlotResourceProfile : resourceProfile;

        // 存储resourceProfile
        if (!budgetManager.reserve(resourceProfile)) {
            LOG.info(
                    "Cannot allocate the requested resources. Trying to allocate {}, "
                            + "while the currently remaining available resources are {}, total is {}.",
                    resourceProfile,
                    budgetManager.getAvailableBudget(),
                    budgetManager.getTotalBudget());
            return false;
        }

        // 构建 taskSlot

        //  taskSlot = {TaskSlot@6191} "TaskSlot(index:0, state:ALLOCATED, resource profile:
        // ResourceProfile{cpuCores=1.0000000000000000, taskHeapMemory=96.000mb (100663293 bytes),
        // taskOffHeapMemory=0 bytes, managedMemory=128.000mb (134217730 bytes),
        // networkMemory=32.000mb (33554432 bytes)}, allocationId: a9ce7abc6f1d6f264dbdce5564efcb76,
        // jobId: 05fdf1bc744b274be1525c918c1ad378)"
        //    index = 0
        //    resourceProfile = {ResourceProfile@6124} "ResourceProfile{cpuCores=1.0000000000000000,
        // taskHeapMemory=96.000mb (100663293 bytes), taskOffHeapMemory=0 bytes,
        // managedMemory=128.000mb (134217730 bytes), networkMemory=32.000mb (33554432 bytes)}"
        //    tasks = {HashMap@6197}  size = 0
        //    memoryManager = {MemoryManager@6198}
        //    state = {TaskSlotState@6199} "ALLOCATED"
        //    jobId = {JobID@6056} "05fdf1bc744b274be1525c918c1ad378"
        //    allocationId = {AllocationID@6057} "a9ce7abc6f1d6f264dbdce5564efcb76"
        //    closingFuture = {CompletableFuture@6200}
        // "java.util.concurrent.CompletableFuture@670d0482[Not completed]"
        //    asyncExecutor = {ThreadPoolExecutor@6076}
        // "java.util.concurrent.ThreadPoolExecutor@da5c1a9[Running, pool size = 0, active threads =
        // 0, queued tasks = 0, completed tasks = 0]"
        taskSlot =
                new TaskSlot<>(
                        index,
                        resourceProfile,
                        memoryPageSize,
                        jobId,
                        allocationId,
                        memoryVerificationExecutor);

        if (index >= 0) {
            // 加入缓存...
            taskSlots.put(index, taskSlot);
        }

        // 更新 allocatedSlots
        // update the allocation id to task slot map
        allocatedSlots.put(allocationId, taskSlot);

        // 注册超时时间
        // register a timeout for this slot since it's in state allocated
        timerService.registerTimeout(allocationId, slotTimeout.getSize(), slotTimeout.getUnit());

        //  更新 slotsPerJob 的slot 集合
        // add this slot to the set of job slots
        Set<AllocationID> slots = slotsPerJob.get(jobId);

        if (slots == null) {
            slots = new HashSet<>(4);
            slotsPerJob.put(jobId, slots);
        }

        slots.add(allocationId);

        return true;
    }

    @Override
    public boolean markSlotActive(AllocationID allocationId) throws SlotNotFoundException {
        checkRunning();

        TaskSlot<T> taskSlot = getTaskSlot(allocationId);

        if (taskSlot != null) {
            return markExistingSlotActive(taskSlot);
        } else {
            throw new SlotNotFoundException(allocationId);
        }
    }

    private boolean markExistingSlotActive(TaskSlot<T> taskSlot) {
        if (taskSlot.markActive()) {
            // unregister a potential timeout
            // Activate slot 3755cb8f9962a9a7738db04f2a02084c.
            LOG.info("Activate slot {}.", taskSlot.getAllocationId());

            timerService.unregisterTimeout(taskSlot.getAllocationId());

            return true;
        } else {
            return false;
        }
    }

    @Override
    public boolean markSlotInactive(AllocationID allocationId, Time slotTimeout)
            throws SlotNotFoundException {
        checkStarted();

        TaskSlot<T> taskSlot = getTaskSlot(allocationId);

        if (taskSlot != null) {
            if (taskSlot.markInactive()) {
                // register a timeout to free the slot
                timerService.registerTimeout(
                        allocationId, slotTimeout.getSize(), slotTimeout.getUnit());

                return true;
            } else {
                return false;
            }
        } else {
            throw new SlotNotFoundException(allocationId);
        }
    }

    @Override
    public int freeSlot(AllocationID allocationId, Throwable cause) throws SlotNotFoundException {
        checkStarted();

        TaskSlot<T> taskSlot = getTaskSlot(allocationId);

        if (taskSlot != null) {
            return freeSlotInternal(taskSlot, cause).isDone() ? taskSlot.getIndex() : -1;
        } else {
            throw new SlotNotFoundException(allocationId);
        }
    }

    private CompletableFuture<Void> freeSlotInternal(TaskSlot<T> taskSlot, Throwable cause) {
        AllocationID allocationId = taskSlot.getAllocationId();

        if (LOG.isDebugEnabled()) {
            LOG.debug("Free slot {}.", taskSlot, cause);
        } else {
            LOG.info("Free slot {}.", taskSlot);
        }

        if (taskSlot.isEmpty()) {
            // remove the allocation id to task slot mapping
            allocatedSlots.remove(allocationId);

            // unregister a potential timeout
            timerService.unregisterTimeout(allocationId);

            JobID jobId = taskSlot.getJobId();
            Set<AllocationID> slots = slotsPerJob.get(jobId);

            if (slots == null) {
                throw new IllegalStateException(
                        "There are no more slots allocated for the job "
                                + jobId
                                + ". This indicates a programming bug.");
            }

            slots.remove(allocationId);

            if (slots.isEmpty()) {
                slotsPerJob.remove(jobId);
            }

            taskSlots.remove(taskSlot.getIndex());
            budgetManager.release(taskSlot.getResourceProfile());
        }
        return taskSlot.closeAsync(cause);
    }

    @Override
    public boolean isValidTimeout(AllocationID allocationId, UUID ticket) {
        checkStarted();

        return state == State.RUNNING && timerService.isValid(allocationId, ticket);
    }

    @Override
    public boolean isAllocated(int index, JobID jobId, AllocationID allocationId) {
        TaskSlot<T> taskSlot = taskSlots.get(index);
        if (taskSlot != null) {
            return taskSlot.isAllocated(jobId, allocationId);
        } else if (index < 0) {
            return allocatedSlots.containsKey(allocationId);
        } else {
            return false;
        }
    }

    @Override
    public boolean tryMarkSlotActive(JobID jobId, AllocationID allocationId) {
        TaskSlot<T> taskSlot = getTaskSlot(allocationId);

        if (taskSlot != null && taskSlot.isAllocated(jobId, allocationId)) {
            return markExistingSlotActive(taskSlot);
        } else {
            return false;
        }
    }

    @Override
    public boolean isSlotFree(int index) {
        return !taskSlots.containsKey(index);
    }

    @Override
    public boolean hasAllocatedSlots(JobID jobId) {
        return getAllocatedSlots(jobId).hasNext();
    }

    @Override
    public Iterator<TaskSlot<T>> getAllocatedSlots(JobID jobId) {
        return new TaskSlotIterator(jobId, TaskSlotState.ALLOCATED);
    }

    @Override
    @Nullable
    public JobID getOwningJob(AllocationID allocationId) {
        final TaskSlot<T> taskSlot = getTaskSlot(allocationId);

        if (taskSlot != null) {
            return taskSlot.getJobId();
        } else {
            return null;
        }
    }

    // ---------------------------------------------------------------------
    // Task methods
    // ---------------------------------------------------------------------

    @Override
    public boolean addTask(T task) throws SlotNotFoundException, SlotNotActiveException {
        checkRunning();
        Preconditions.checkNotNull(task);

        TaskSlot<T> taskSlot = getTaskSlot(task.getAllocationId());

        if (taskSlot != null) {
            if (taskSlot.isActive(task.getJobID(), task.getAllocationId())) {
                // 根据任务的  task.getExecutionId()
                // 加入到slot的任务缓存  Map<ExecutionAttemptID, T> tasks  中.

                if (taskSlot.add(task)) {
                    taskSlotMappings.put(
                            task.getExecutionId(), new TaskSlotMapping<>(task, taskSlot));

                    return true;
                } else {
                    return false;
                }
            } else {
                throw new SlotNotActiveException(task.getJobID(), task.getAllocationId());
            }
        } else {
            throw new SlotNotFoundException(task.getAllocationId());
        }
    }

    @Override
    public T removeTask(ExecutionAttemptID executionAttemptID) {
        checkStarted();

        TaskSlotMapping<T> taskSlotMapping = taskSlotMappings.remove(executionAttemptID);

        if (taskSlotMapping != null) {
            T task = taskSlotMapping.getTask();
            TaskSlot<T> taskSlot = taskSlotMapping.getTaskSlot();

            taskSlot.remove(task.getExecutionId());

            if (taskSlot.isReleasing() && taskSlot.isEmpty()) {
                slotActions.freeSlot(taskSlot.getAllocationId());
            }

            return task;
        } else {
            return null;
        }
    }

    @Override
    public T getTask(ExecutionAttemptID executionAttemptID) {
        TaskSlotMapping<T> taskSlotMapping = taskSlotMappings.get(executionAttemptID);

        if (taskSlotMapping != null) {
            return taskSlotMapping.getTask();
        } else {
            return null;
        }
    }

    @Override
    public Iterator<T> getTasks(JobID jobId) {
        return new PayloadIterator(jobId);
    }

    @Override
    public AllocationID getCurrentAllocation(int index) {
        TaskSlot<T> taskSlot = taskSlots.get(index);
        if (taskSlot == null) {
            return null;
        }
        return taskSlot.getAllocationId();
    }

    @Override
    public MemoryManager getTaskMemoryManager(AllocationID allocationID)
            throws SlotNotFoundException {
        TaskSlot<T> taskSlot = getTaskSlot(allocationID);
        if (taskSlot != null) {
            return taskSlot.getMemoryManager();
        } else {
            throw new SlotNotFoundException(allocationID);
        }
    }

    // ---------------------------------------------------------------------
    // TimeoutListener methods
    // ---------------------------------------------------------------------

    @Override
    public void notifyTimeout(AllocationID key, UUID ticket) {
        checkStarted();

        if (slotActions != null) {
            slotActions.timeoutSlot(key, ticket);
        }
    }

    // ---------------------------------------------------------------------
    // Internal methods
    // ---------------------------------------------------------------------

    @Nullable
    private TaskSlot<T> getTaskSlot(AllocationID allocationId) {
        Preconditions.checkNotNull(allocationId);

        return allocatedSlots.get(allocationId);
    }

    private void checkRunning() {
        Preconditions.checkState(
                state == State.RUNNING,
                "The %s has to be running.",
                TaskSlotTableImpl.class.getSimpleName());
    }

    private void checkStarted() {
        Preconditions.checkState(
                state != State.CREATED,
                "The %s has to be started (not created).",
                TaskSlotTableImpl.class.getSimpleName());
    }

    // ---------------------------------------------------------------------
    // Static utility classes
    // ---------------------------------------------------------------------

    /** Mapping class between a {@link TaskSlotPayload} and a {@link TaskSlot}. */
    private static final class TaskSlotMapping<T extends TaskSlotPayload> {
        private final T task;
        private final TaskSlot<T> taskSlot;

        private TaskSlotMapping(T task, TaskSlot<T> taskSlot) {
            this.task = Preconditions.checkNotNull(task);
            this.taskSlot = Preconditions.checkNotNull(taskSlot);
        }

        public T getTask() {
            return task;
        }

        public TaskSlot<T> getTaskSlot() {
            return taskSlot;
        }
    }

    /**
     * 足给定状态条件并属于给定作业的{@link TaskSlot}上的迭代器。
     *
     * <p>Iterator over {@link TaskSlot} which fulfill a given state condition and belong to the
     * given job.
     */
    private final class TaskSlotIterator implements Iterator<TaskSlot<T>> {
        private final Iterator<AllocationID> allSlots;
        private final TaskSlotState state;

        private TaskSlot<T> currentSlot;

        private TaskSlotIterator(TaskSlotState state) {
            this(
                    slotsPerJob.values().stream()
                            .flatMap(Collection::stream)
                            .collect(Collectors.toSet())
                            .iterator(),
                    state);
        }

        private TaskSlotIterator(JobID jobId, TaskSlotState state) {
            this(
                    slotsPerJob.get(jobId) == null
                            ? Collections.emptyIterator()
                            : slotsPerJob.get(jobId).iterator(),
                    state);
        }

        private TaskSlotIterator(Iterator<AllocationID> allocationIDIterator, TaskSlotState state) {
            this.allSlots = Preconditions.checkNotNull(allocationIDIterator);
            this.state = Preconditions.checkNotNull(state);
            this.currentSlot = null;
        }

        @Override
        public boolean hasNext() {
            while (currentSlot == null && allSlots.hasNext()) {
                AllocationID tempSlot = allSlots.next();

                TaskSlot<T> taskSlot = getTaskSlot(tempSlot);

                if (taskSlot != null && taskSlot.getState() == state) {
                    currentSlot = taskSlot;
                }
            }

            return currentSlot != null;
        }

        @Override
        public TaskSlot<T> next() {
            if (currentSlot != null) {
                TaskSlot<T> result = currentSlot;

                currentSlot = null;

                return result;
            } else {
                while (true) {
                    AllocationID tempSlot;

                    try {
                        tempSlot = allSlots.next();
                    } catch (NoSuchElementException e) {
                        throw new NoSuchElementException("No more task slots.");
                    }

                    TaskSlot<T> taskSlot = getTaskSlot(tempSlot);

                    if (taskSlot != null && taskSlot.getState() == state) {
                        return taskSlot;
                    }
                }
            }
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException("Cannot remove task slots via this iterator.");
        }
    }

    /** Iterator over all {@link TaskSlotPayload} for a given job. */
    private final class PayloadIterator implements Iterator<T> {
        private final Iterator<TaskSlot<T>> taskSlotIterator;

        private Iterator<T> currentTasks;

        private PayloadIterator(JobID jobId) {
            this.taskSlotIterator = new TaskSlotIterator(jobId, TaskSlotState.ACTIVE);

            this.currentTasks = null;
        }

        @Override
        public boolean hasNext() {
            while ((currentTasks == null || !currentTasks.hasNext())
                    && taskSlotIterator.hasNext()) {
                TaskSlot<T> taskSlot = taskSlotIterator.next();

                currentTasks = taskSlot.getTasks();
            }

            return (currentTasks != null && currentTasks.hasNext());
        }

        @Override
        public T next() {
            while ((currentTasks == null || !currentTasks.hasNext())) {
                TaskSlot<T> taskSlot;

                try {
                    taskSlot = taskSlotIterator.next();
                } catch (NoSuchElementException e) {
                    throw new NoSuchElementException("No more tasks.");
                }

                currentTasks = taskSlot.getTasks();
            }

            return currentTasks.next();
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException("Cannot remove tasks via this iterator.");
        }
    }

    private enum State {
        CREATED,
        RUNNING,
        CLOSING,
        CLOSED
    }
}
