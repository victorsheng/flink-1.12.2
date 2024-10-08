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

package org.apache.flink.runtime.taskexecutor;

import org.apache.flink.api.common.time.Time;
import org.apache.flink.runtime.registration.RegisteredRpcConnection;
import org.apache.flink.runtime.registration.RegistrationConnectionListener;
import org.apache.flink.runtime.registration.RegistrationResponse;
import org.apache.flink.runtime.registration.RetryingRegistration;
import org.apache.flink.runtime.registration.RetryingRegistrationConfiguration;
import org.apache.flink.runtime.resourcemanager.ResourceManagerGateway;
import org.apache.flink.runtime.resourcemanager.ResourceManagerId;
import org.apache.flink.runtime.resourcemanager.TaskExecutorRegistration;
import org.apache.flink.runtime.rpc.RpcService;

import org.slf4j.Logger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static org.apache.flink.util.Preconditions.checkNotNull;

/** The connection between a TaskExecutor and the ResourceManager. */
public class TaskExecutorToResourceManagerConnection
        extends RegisteredRpcConnection<
                ResourceManagerId, ResourceManagerGateway, TaskExecutorRegistrationSuccess> {

    private final RpcService rpcService;

    private final RetryingRegistrationConfiguration retryingRegistrationConfiguration;

    private final RegistrationConnectionListener<
                    TaskExecutorToResourceManagerConnection, TaskExecutorRegistrationSuccess>
            registrationListener;

    private final TaskExecutorRegistration taskExecutorRegistration;

    public TaskExecutorToResourceManagerConnection(
            Logger log,
            RpcService rpcService,
            RetryingRegistrationConfiguration retryingRegistrationConfiguration,
            String resourceManagerAddress,
            ResourceManagerId resourceManagerId,
            Executor executor,
            RegistrationConnectionListener<
                            TaskExecutorToResourceManagerConnection,
                            TaskExecutorRegistrationSuccess>
                    registrationListener,
            TaskExecutorRegistration taskExecutorRegistration) {

        super(log, resourceManagerAddress, resourceManagerId, executor);

        this.rpcService = checkNotNull(rpcService);
        this.retryingRegistrationConfiguration = checkNotNull(retryingRegistrationConfiguration);
        this.registrationListener = checkNotNull(registrationListener);
        this.taskExecutorRegistration = checkNotNull(taskExecutorRegistration);
    }

    // TaskExecutor 注册

    @Override
    protected RetryingRegistration<
                    ResourceManagerId, ResourceManagerGateway, TaskExecutorRegistrationSuccess>
            generateRegistration() {
        // 构建生成 TaskExecutorToResourceManagerConnection
        return new TaskExecutorToResourceManagerConnection.ResourceManagerRegistration(
                log,
                rpcService,
                getTargetAddress(),
                getTargetLeaderId(),
                retryingRegistrationConfiguration,
                taskExecutorRegistration);
    }

    // 注册成功
    @Override
    protected void onRegistrationSuccess(TaskExecutorRegistrationSuccess success) {
        // Successful registration at resource manager
        //      akka.tcp://flink@192.168.8.188:62257/user/rpc/resourcemanager_*
        // under registration id
        //      5ad0a12f8bb03f9d016f8d1e18380563.
        log.info(
                "Successful registration at resource manager {} under registration id {}.",
                getTargetAddress(),
                success.getRegistrationId());

        registrationListener.onRegistrationSuccess(this, success);
    }

    @Override
    protected void onRegistrationFailure(Throwable failure) {
        log.info("Failed to register at resource manager {}.", getTargetAddress(), failure);

        registrationListener.onRegistrationFailure(failure);
    }

    // ------------------------------------------------------------------------
    //  Utilities
    // ------------------------------------------------------------------------

    private static class ResourceManagerRegistration
            extends RetryingRegistration<
                    ResourceManagerId, ResourceManagerGateway, TaskExecutorRegistrationSuccess> {

        private final TaskExecutorRegistration taskExecutorRegistration;

        ResourceManagerRegistration(
                Logger log,
                RpcService rpcService,
                String targetAddress,
                ResourceManagerId resourceManagerId,
                RetryingRegistrationConfiguration retryingRegistrationConfiguration,
                TaskExecutorRegistration taskExecutorRegistration) {

            // 调用父类
            super(
                    log,
                    rpcService,
                    "ResourceManager",
                    ResourceManagerGateway.class,
                    targetAddress,
                    resourceManagerId,
                    retryingRegistrationConfiguration);
            this.taskExecutorRegistration = taskExecutorRegistration;
        }

        // 开始注册的时候 RetryingRegistration#register 方法
        // 会 调用 invokeRegistration 方法
        @Override
        protected CompletableFuture<RegistrationResponse> invokeRegistration(
                ResourceManagerGateway resourceManager,
                ResourceManagerId fencingToken,
                long timeoutMillis)
                throws Exception {

            Time timeout = Time.milliseconds(timeoutMillis);

            // ResourceManager#registerTaskExecutor
            // 注册 TaskExecutor
            return resourceManager.registerTaskExecutor(taskExecutorRegistration, timeout);
        }
    }
}
