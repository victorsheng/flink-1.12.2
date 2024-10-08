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

package org.apache.flink.runtime.resourcemanager.slotmanager;

import org.apache.flink.runtime.clusterframework.types.AllocationID;
import org.apache.flink.runtime.clusterframework.types.ResourceProfile;
import org.apache.flink.runtime.clusterframework.types.SlotID;
import org.apache.flink.runtime.instance.InstanceID;
import org.apache.flink.runtime.resourcemanager.ResourceManagerId;
import org.apache.flink.runtime.resourcemanager.SlotRequest;
import org.apache.flink.runtime.resourcemanager.WorkerResourceSpec;
import org.apache.flink.runtime.resourcemanager.exceptions.ResourceManagerException;
import org.apache.flink.runtime.resourcemanager.registration.TaskExecutorConnection;
import org.apache.flink.runtime.slots.ResourceRequirements;
import org.apache.flink.runtime.taskexecutor.SlotReport;

import java.util.Map;
import java.util.concurrent.Executor;

/**
 * slot manager 负责维护所有已注册的任务管理器slot、它们的分配和所有挂起的slot请求的视图。 无论何时注册新slot或释放分配的slot，它都会尝试执行另一个挂起的slot请求。
 * 每当没有足够的可用slot时，slot管理器将通过{@link ResourceActions#allocateResource（WorkerResourceSpec）}通知资源管理器。
 *
 * <p>为了释放资源并避免资源泄漏，空闲任务管理器（其slot当前未使用的任务管理器）和挂起的slot请求分别超时，从而触发其释放和失败。
 *
 * <p>The slot manager is responsible for maintaining a view on all registered task manager slots,
 * their allocation and all pending slot requests.
 *
 * <p>Whenever a new slot is registered or an allocated slot is freed, then it tries to fulfill
 * another pending slot request.
 *
 * <p>Whenever there are not enough slots available the slot manager will notify the resource
 * manager about it via {@link ResourceActions#allocateResource(WorkerResourceSpec)}.
 *
 * <p>In order to free resources and avoid resource leaks, idling task managers (task managers whose
 * slots are currently not used) and pending slot requests time out triggering their release and
 * failure, respectively.
 */
public interface SlotManager extends AutoCloseable {
    int getNumberRegisteredSlots();

    int getNumberRegisteredSlotsOf(InstanceID instanceId);

    int getNumberFreeSlots();

    int getNumberFreeSlotsOf(InstanceID instanceId);

    /**
     * 获取从{@link ResourceActions}请求的尚未完成的workers SlotManager的数量。
     *
     * <p>Get number of workers SlotManager requested from {@link ResourceActions} that are not yet
     * fulfilled.
     *
     * @return a map whose key set is all the unique resource specs of the pending workers, and the
     *     corresponding value is number of pending workers of that resource spec.
     */
    Map<WorkerResourceSpec, Integer> getRequiredResources();

    ResourceProfile getRegisteredResource();

    ResourceProfile getRegisteredResourceOf(InstanceID instanceID);

    ResourceProfile getFreeResource();

    ResourceProfile getFreeResourceOf(InstanceID instanceID);

    int getNumberPendingSlotRequests();

    /**
     * Starts the slot manager with the given leader id and resource manager actions.
     *
     * @param newResourceManagerId to use for communication with the task managers
     * @param newMainThreadExecutor to use to run code in the ResourceManager's main thread
     * @param newResourceActions to use for resource (de-)allocations
     */
    void start(
            ResourceManagerId newResourceManagerId,
            Executor newMainThreadExecutor,
            ResourceActions newResourceActions);

    /** Suspends the component. This clears the internal state of the slot manager. */
    void suspend();

    /**
     * Notifies the slot manager about the resource requirements of a job.
     *
     * @param resourceRequirements resource requirements of a job
     */
    void processResourceRequirements(ResourceRequirements resourceRequirements);

    /**
     * Requests a slot with the respective resource profile.
     *
     * @param slotRequest specifying the requested slot specs
     * @return true if the slot request was registered; false if the request is a duplicate
     * @throws ResourceManagerException if the slot request failed (e.g. not enough resources left)
     */
    default boolean registerSlotRequest(SlotRequest slotRequest) throws ResourceManagerException {
        throw new UnsupportedOperationException();
    }

    /**
     * Cancels and removes a pending slot request with the given allocation id. If there is no such
     * pending request, then nothing is done.
     *
     * @param allocationId identifying the pending slot request
     * @return True if a pending slot request was found; otherwise false
     */
    default boolean unregisterSlotRequest(AllocationID allocationId) {
        throw new UnsupportedOperationException();
    }

    /**
     * Registers a new task manager at the slot manager. This will make the task managers slots
     * known and, thus, available for allocation.
     *
     * @param taskExecutorConnection for the new task manager
     * @param initialSlotReport for the new task manager
     * @return True if the task manager has not been registered before and is registered
     *     successfully; otherwise false
     */
    boolean registerTaskManager(
            TaskExecutorConnection taskExecutorConnection, SlotReport initialSlotReport);

    /**
     * Unregisters the task manager identified by the given instance id and its associated slots
     * from the slot manager.
     *
     * @param instanceId identifying the task manager to unregister
     * @param cause for unregistering the TaskManager
     * @return True if there existed a registered task manager with the given instance id
     */
    boolean unregisterTaskManager(InstanceID instanceId, Exception cause);

    /**
     * Reports the current slot allocations for a task manager identified by the given instance id.
     *
     * @param instanceId identifying the task manager for which to report the slot status
     * @param slotReport containing the status for all of its slots
     * @return true if the slot status has been updated successfully, otherwise false
     */
    boolean reportSlotStatus(InstanceID instanceId, SlotReport slotReport);

    /**
     * Free the given slot from the given allocation. If the slot is still allocated by the given
     * allocation id, then the slot will be marked as free and will be subject to new slot requests.
     *
     * @param slotId identifying the slot to free
     * @param allocationId with which the slot is presumably allocated
     */
    void freeSlot(SlotID slotId, AllocationID allocationId);

    void setFailUnfulfillableRequest(boolean failUnfulfillableRequest);
}
