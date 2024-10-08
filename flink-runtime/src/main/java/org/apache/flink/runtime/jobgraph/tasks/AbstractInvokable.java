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

package org.apache.flink.runtime.jobgraph.tasks;

import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.runtime.checkpoint.CheckpointMetaData;
import org.apache.flink.runtime.checkpoint.CheckpointMetricsBuilder;
import org.apache.flink.runtime.checkpoint.CheckpointOptions;
import org.apache.flink.runtime.execution.Environment;
import org.apache.flink.runtime.jobgraph.OperatorID;
import org.apache.flink.runtime.operators.coordination.OperatorEvent;
import org.apache.flink.util.FlinkException;
import org.apache.flink.util.SerializedValue;

import java.io.IOException;
import java.util.concurrent.Future;

import static org.apache.flink.util.Preconditions.checkNotNull;

/**
 * 这是TaskManager可以执行的每个任务的抽象基类。 具体的任务扩展了这个类，例如流式处理和批处理任务。 TaskManager在执行任务时调用{@link#invoke（）}方法。
 * 任务的所有操作都在此方法中发生（设置输入输出流读写器以及任务的核心操作）。 所有扩展的类都必须提供构造函数{@code
 * MyTask(Environment,TaskStateSnapshot)}. 为了方便起见，总是无状态的任务也只能实现构造函数{@code MyTask(Environment)}.
 *
 * <p>开发说明： 虽然构造函数不能在编译时强制执行，但我们还没有冒险引入工厂（毕竟它只是一个内部API，对于java8，可以像工厂lambda一样使用 {@code Class::new} ）。
 *
 * <p>注意: 没有接受初始任务状态快照并将其存储在变量中的构造函数。
 * 这是出于目的，因为抽象调用本身不需要状态快照（只有StreamTask等子类需要状态），我们不希望无限期地存储引用，从而防止垃圾收集器清理初始状态结构。
 *
 * <p>任何支持可恢复状态并参与检查点设置的子类都需要重写 `{@link #triggerCheckpointAsync(CheckpointMetaData,
 * CheckpointOptions, boolean)}, ` `{@link #triggerCheckpointOnBarrier(CheckpointMetaData,
 * CheckpointOptions, CheckpointMetricsBuilder)}`, `{@link #abortCheckpointOnBarrier(long,
 * Throwable)} `and `{@link #notifyCheckpointCompleteAsync(long)}`.
 *
 * <p>This is the abstract base class for every task that can be executed by a TaskManager. Concrete
 * tasks extend this class, for example the streaming and batch tasks.
 *
 * <p>The TaskManager invokes the {@link #invoke()} method when executing a task. All operations of
 * the task happen in this method (setting up input output stream readers and writers as well as the
 * task's core operation).
 *
 * <p>All classes that extend must offer a constructor {@code MyTask(Environment,
 * TaskStateSnapshot)}.
 *
 * <p>Tasks that are always stateless can, for convenience, also only implement the constructor
 * {@code MyTask(Environment)}.
 *
 * <p><i>Developer note: While constructors cannot be enforced at compile time, we did not yet
 * venture on the endeavor of introducing factories (it is only an internal API after all, and with
 * Java 8, one can use {@code Class::new} almost like a factory lambda.</i>
 *
 * <p><b>NOTE:</b> There is no constructor that accepts and initial task state snapshot and stores
 * it in a variable.
 *
 * <p>That is on purpose, because the AbstractInvokable itself does not need the state snapshot
 * (only subclasses such as StreamTask do need the state) and we do not want to store a reference
 * indefinitely, thus preventing cleanup of the initial state structure by the Garbage Collector.
 *
 * <p>Any subclass that supports recoverable state and participates in checkpointing needs to
 * override {@link #triggerCheckpointAsync(CheckpointMetaData, CheckpointOptions, boolean)}, {@link
 * #triggerCheckpointOnBarrier(CheckpointMetaData, CheckpointOptions, CheckpointMetricsBuilder)},
 * {@link #abortCheckpointOnBarrier(long, Throwable)} and {@link
 * #notifyCheckpointCompleteAsync(long)}.
 */
public abstract class AbstractInvokable {

    /** 分配给此可调用对象的环境。 The environment assigned to this invokable. */
    private final Environment environment;

    /** 标记取消是否应中断正在执行的线程。 Flag whether cancellation should interrupt the executing thread. */
    private volatile boolean shouldInterruptOnCancel = true;

    /**
     * Create an Invokable task and set its environment.
     *
     * @param environment The environment assigned to this invokable.
     */
    public AbstractInvokable(Environment environment) {
        this.environment = checkNotNull(environment);
    }

    // ------------------------------------------------------------------------
    //  Core methods
    // ------------------------------------------------------------------------

    /**
     * 开始执行
     *
     * <p>必须被具体的任务实现所覆盖。当任务的实际执行开始时，task manager 将调用此方法。
     *
     * <p>当方法返回时，应该清理所有资源。
     *
     * <p>确保在必要时用<code>try-finally</code> 保护代码。
     *
     * @throws Exception : 任务可以将其异常转发给TaskManager，以便在故障/恢复期间进行处理。
     *     <p>Starts the execution.
     *     <p>Must be overwritten by the concrete task implementation. This method is called by the
     *     task manager when the actual execution of the task starts.
     *     <p>All resources should be cleaned up when the method returns. Make sure to guard the
     *     code with <code>try-finally</code> blocks where necessary.
     * @throws Exception Tasks may forward their exceptions for the TaskManager to handle through
     *     failure/recovery.
     */
    public abstract void invoke() throws Exception;

    /**
     * This method is called when a task is canceled either as a result of a user abort or an
     * execution failure. It can be overwritten to respond to shut down the user code properly.
     *
     * @throws Exception thrown if any exception occurs during the execution of the user code
     */
    public void cancel() throws Exception {
        // The default implementation does nothing.
    }

    /**
     * 设置执行{@link #invoke()}方法的线程是否应在取消过程中中断。 此方法为 initial interrupt 和 repeated interrupt 设置标志。
     *
     * <p>Sets whether the thread that executes the {@link #invoke()} method should be interrupted
     * during cancellation.
     *
     * <p>This method sets the flag for both the initial interrupt, as well as for the repeated
     * interrupt.
     *
     * <p>Setting the interruption to false at some point during the cancellation procedure is a way
     * to stop further interrupts from happening.
     */
    public void setShouldInterruptOnCancel(boolean shouldInterruptOnCancel) {
        this.shouldInterruptOnCancel = shouldInterruptOnCancel;
    }

    /**
     * Checks whether the task should be interrupted during cancellation. This method is check both
     * for the initial interrupt, as well as for the repeated interrupt. Setting the interruption to
     * false via {@link #setShouldInterruptOnCancel(boolean)} is a way to stop further interrupts
     * from happening.
     */
    public boolean shouldInterruptOnCancel() {
        return shouldInterruptOnCancel;
    }

    // ------------------------------------------------------------------------
    //  Access to Environment and Configuration
    // ------------------------------------------------------------------------

    /**
     * Returns the environment of this task.
     *
     * @return The environment of this task.
     */
    public final Environment getEnvironment() {
        return this.environment;
    }

    /**
     * Returns the user code class loader of this invokable.
     *
     * @return user code class loader of this invokable.
     */
    public final ClassLoader getUserCodeClassLoader() {
        return getEnvironment().getUserCodeClassLoader().asClassLoader();
    }

    /**
     * Returns the current number of subtasks the respective task is split into.
     *
     * @return the current number of subtasks the respective task is split into
     */
    public int getCurrentNumberOfSubtasks() {
        return this.environment.getTaskInfo().getNumberOfParallelSubtasks();
    }

    /**
     * Returns the index of this subtask in the subtask group.
     *
     * @return the index of this subtask in the subtask group
     */
    public int getIndexInSubtaskGroup() {
        return this.environment.getTaskInfo().getIndexOfThisSubtask();
    }

    /**
     * Returns the task configuration object which was attached to the original {@link
     * org.apache.flink.runtime.jobgraph.JobVertex}.
     *
     * @return the task configuration object which was attached to the original {@link
     *     org.apache.flink.runtime.jobgraph.JobVertex}
     */
    public final Configuration getTaskConfiguration() {
        return this.environment.getTaskConfiguration();
    }

    /**
     * Returns the job configuration object which was attached to the original {@link
     * org.apache.flink.runtime.jobgraph.JobGraph}.
     *
     * @return the job configuration object which was attached to the original {@link
     *     org.apache.flink.runtime.jobgraph.JobGraph}
     */
    public Configuration getJobConfiguration() {
        return this.environment.getJobConfiguration();
    }

    /** Returns the global ExecutionConfig. */
    public ExecutionConfig getExecutionConfig() {
        return this.environment.getExecutionConfig();
    }

    // ------------------------------------------------------------------------
    //  Checkpointing Methods
    // ------------------------------------------------------------------------

    /**
     * triggerCheckpoint 是触发 checkpoint 的源头，会向下游注入 CheckpointBarrier
     *
     * <p>由检查点协调器异步调用以触发检查点。
     *
     * <p>对于通过注入 initial barriers （source tasks）来启动检查点的任务，将调用此方法。
     *
     * <p>相反，下游操作符上的检查点（接收检查点屏障的结果）
     * 调用{@link#triggerCheckpointOnBarrier（CheckpointMetaData，CheckpointOptions，CheckpointMetricsBuilder）}
     * 方法。
     *
     * <p>This method is called to trigger a checkpoint, asynchronously by the checkpoint
     * coordinator.
     *
     * <p>This method is called for tasks that start the checkpoints by injecting the initial
     * barriers, i.e., the source tasks.
     *
     * <p>In contrast, checkpoints on downstream operators, which are the result of receiving
     * checkpoint barriers, invoke the {@link #triggerCheckpointOnBarrier(CheckpointMetaData,
     * CheckpointOptions, CheckpointMetricsBuilder)} method.
     *
     * @param checkpointMetaData Meta data for about this checkpoint
     * @param checkpointOptions Options for performing this checkpoint
     * @return future with value of {@code false} if the checkpoint was not carried out, {@code
     *     true} otherwise
     */
    public Future<Boolean> triggerCheckpointAsync(
            CheckpointMetaData checkpointMetaData, CheckpointOptions checkpointOptions) {
        throw new UnsupportedOperationException(
                String.format(
                        "triggerCheckpointAsync not supported by %s", this.getClass().getName()));
    }

    /**
     * 在所有 input streams 上接收到检查点屏障而触发检查点时，将调用此方法。
     *
     * <p>This method is called when a checkpoint is triggered as a result of receiving checkpoint
     * barriers on all input streams.
     *
     * @param checkpointMetaData Meta data for about this checkpoint
     * @param checkpointOptions Options for performing this checkpoint
     * @param checkpointMetrics Metrics about this checkpoint
     * @throws Exception Exceptions thrown as the result of triggering a checkpoint are forwarded.
     */
    public void triggerCheckpointOnBarrier(
            CheckpointMetaData checkpointMetaData,
            CheckpointOptions checkpointOptions,
            CheckpointMetricsBuilder checkpointMetrics)
            throws IOException {
        throw new UnsupportedOperationException(
                String.format(
                        "triggerCheckpointOnBarrier not supported by %s",
                        this.getClass().getName()));
    }

    /**
     * 在接收一些checkpoint barriers 的结果时, 放弃checkpoint ... Aborts a checkpoint as the result of
     * receiving possibly some checkpoint barriers, but at least one {@link
     * org.apache.flink.runtime.io.network.api.CancelCheckpointMarker}.
     *
     * <p>This requires implementing tasks to forward a {@link
     * org.apache.flink.runtime.io.network.api.CancelCheckpointMarker} to their outputs.
     *
     * @param checkpointId The ID of the checkpoint to be aborted.
     * @param cause The reason why the checkpoint was aborted during alignment
     */
    public void abortCheckpointOnBarrier(long checkpointId, Throwable cause) throws IOException {
        throw new UnsupportedOperationException(
                String.format(
                        "abortCheckpointOnBarrier not supported by %s", this.getClass().getName()));
    }

    /**
     * Invoked when a checkpoint has been completed, i.e., when the checkpoint coordinator has
     * received the notification from all participating tasks.
     *
     * @param checkpointId The ID of the checkpoint that is complete.
     * @return future that completes when the notification has been processed by the task.
     */
    public Future<Void> notifyCheckpointCompleteAsync(long checkpointId) {
        throw new UnsupportedOperationException(
                String.format(
                        "notifyCheckpointCompleteAsync not supported by %s",
                        this.getClass().getName()));
    }

    /**
     * Invoked when a checkpoint has been aborted, i.e., when the checkpoint coordinator has
     * received a decline message from one task and try to abort the targeted checkpoint by
     * notification.
     *
     * @param checkpointId The ID of the checkpoint that is aborted.
     * @return future that completes when the notification has been processed by the task.
     */
    public Future<Void> notifyCheckpointAbortAsync(long checkpointId) {
        throw new UnsupportedOperationException(
                String.format(
                        "notifyCheckpointAbortAsync not supported by %s",
                        this.getClass().getName()));
    }

    public void dispatchOperatorEvent(OperatorID operator, SerializedValue<OperatorEvent> event)
            throws FlinkException {
        throw new UnsupportedOperationException(
                "dispatchOperatorEvent not supported by " + getClass().getName());
    }
}
