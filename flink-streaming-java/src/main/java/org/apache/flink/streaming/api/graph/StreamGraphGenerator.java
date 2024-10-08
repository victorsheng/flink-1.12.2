/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.api.graph;

import org.apache.flink.annotation.Internal;
import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.api.common.RuntimeExecutionMode;
import org.apache.flink.api.common.cache.DistributedCache;
import org.apache.flink.api.connector.source.Boundedness;
import org.apache.flink.api.dag.Transformation;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.ExecutionOptions;
import org.apache.flink.configuration.ReadableConfig;
import org.apache.flink.runtime.jobgraph.SavepointRestoreSettings;
import org.apache.flink.runtime.jobgraph.ScheduleMode;
import org.apache.flink.runtime.state.KeyGroupRangeAssignment;
import org.apache.flink.runtime.state.StateBackend;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.environment.CheckpointConfig;
import org.apache.flink.streaming.api.operators.sorted.state.BatchExecutionInternalTimeServiceManager;
import org.apache.flink.streaming.api.operators.sorted.state.BatchExecutionStateBackend;
import org.apache.flink.streaming.api.transformations.BroadcastStateTransformation;
import org.apache.flink.streaming.api.transformations.CoFeedbackTransformation;
import org.apache.flink.streaming.api.transformations.FeedbackTransformation;
import org.apache.flink.streaming.api.transformations.KeyedBroadcastStateTransformation;
import org.apache.flink.streaming.api.transformations.KeyedMultipleInputTransformation;
import org.apache.flink.streaming.api.transformations.LegacySinkTransformation;
import org.apache.flink.streaming.api.transformations.LegacySourceTransformation;
import org.apache.flink.streaming.api.transformations.MultipleInputTransformation;
import org.apache.flink.streaming.api.transformations.OneInputTransformation;
import org.apache.flink.streaming.api.transformations.PartitionTransformation;
import org.apache.flink.streaming.api.transformations.PhysicalTransformation;
import org.apache.flink.streaming.api.transformations.ReduceTransformation;
import org.apache.flink.streaming.api.transformations.SideOutputTransformation;
import org.apache.flink.streaming.api.transformations.SinkTransformation;
import org.apache.flink.streaming.api.transformations.SourceTransformation;
import org.apache.flink.streaming.api.transformations.TimestampsAndWatermarksTransformation;
import org.apache.flink.streaming.api.transformations.TwoInputTransformation;
import org.apache.flink.streaming.api.transformations.UnionTransformation;
import org.apache.flink.streaming.api.transformations.WithBoundedness;
import org.apache.flink.streaming.runtime.translators.BroadcastStateTransformationTranslator;
import org.apache.flink.streaming.runtime.translators.KeyedBroadcastStateTransformationTranslator;
import org.apache.flink.streaming.runtime.translators.LegacySinkTransformationTranslator;
import org.apache.flink.streaming.runtime.translators.LegacySourceTransformationTranslator;
import org.apache.flink.streaming.runtime.translators.MultiInputTransformationTranslator;
import org.apache.flink.streaming.runtime.translators.OneInputTransformationTranslator;
import org.apache.flink.streaming.runtime.translators.PartitionTransformationTranslator;
import org.apache.flink.streaming.runtime.translators.ReduceTransformationTranslator;
import org.apache.flink.streaming.runtime.translators.SideOutputTransformationTranslator;
import org.apache.flink.streaming.runtime.translators.SinkTransformationTranslator;
import org.apache.flink.streaming.runtime.translators.SourceTransformationTranslator;
import org.apache.flink.streaming.runtime.translators.TimestampsAndWatermarksTransformationTranslator;
import org.apache.flink.streaming.runtime.translators.TwoInputTransformationTranslator;
import org.apache.flink.streaming.runtime.translators.UnionTransformationTranslator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.apache.flink.util.Preconditions.checkNotNull;
import static org.apache.flink.util.Preconditions.checkState;

/**
 * 从{@link Transformation}的图生成{@link StreamGraph}的生成器。
 *
 * <p>这将遍历从sinks 开始的{@code Transformations}树。
 *
 * <p>在每次转换时，我们递归地转换输入，然后在{@code StreamGraph}中创建一个节点，并将输入节点的边添加到新创建的节点。
 *
 * <p>转换方法返回StreamGraph中表示输入转换的节点的id。
 *
 * <p>可以返回几个id来处理反馈转换和联合。
 *
 * <p>为此，我们在{@code StreamGraph}中创建一个虚拟节点，该节点包含特定的属性，即分区、选择器等。
 *
 * <p>当从虚拟节点到下游节点创建边时，{@code StreamGraph}解析原始节点的id，并在图中创建具有所需属性的边。
 *
 * <p>例如，如果您有以下图表：
 *
 * <pre>
 *     Map-1 >  HashPartition-2 >  Map-3
 * </pre>
 *
 * 其中数字表示转换ID。 我们首先一路递归下去。 {@code Map-1} 被转换，即我们创建一个ID为1的 {@code StreamNode} 。 然后我们转换{@code
 * HashPartition}，为此，我们创建ID为4的虚拟节点，其中包含属性{@code HashPartition}。 这个转换返回ID 4。 然后我们转换 {@code Map-3}.
 * 我们添加边 {@code 4 -> 3}. {@code StreamGraph} 解析了ID为1的实际节点，并用HashPartition属性创建和边缘{@code 1 -> 3}。
 *
 * <p>Partitioning, split/select and union 不会在{@code StreamGraph}中创建实际的节点。
 *
 * <p>A generator that generates a {@link StreamGraph} from a graph of {@link Transformation}s.
 *
 * <p>This traverses the tree of {@code Transformations} starting from the sinks.
 *
 * <p>At each transformation we recursively transform the inputs, then create a node in the {@code
 * StreamGraph} and add edges from the input Nodes to our newly created node.
 *
 * <p>The transformation methods return the IDs of the nodes in the StreamGraph that represent the
 * input transformation.
 *
 * <p>Several IDs can be returned to be able to deal with feedback transformations and unions.
 *
 * <p>Partitioning, split/select and union don't create actual nodes in the {@code StreamGraph}.
 *
 * <p>For these, we create a virtual node in the {@code StreamGraph} that holds the specific
 * property, i.e. partitioning, selector and so on.
 *
 * <p>When an edge is created from a virtual node to a downstream node the {@code StreamGraph}
 * resolved the id of the original node and creates an edge in the graph with the desired property.
 *
 * <p>For example, if you have this graph:
 *
 * <pre>
 *     Map-1 -&gt; HashPartition-2 -&gt; Map-3
 * </pre>
 *
 * <p>where the numbers represent transformation IDs.
 *
 * <p>We first recurse all the way down.
 *
 * <p>{@code Map-1} is transformed, i.e. we create a {@code StreamNode} with ID 1.
 *
 * <p>Then we transform the {@code HashPartition}, for this, we create virtual node of ID 4 that
 * holds the property {@code HashPartition}.
 *
 * <p>This transformation returns the ID 4.
 *
 * <p>Then we transform the {@code Map-3}.
 *
 * <p>We add the edge {@code 4 -> 3}.
 *
 * <p>The {@code StreamGraph} resolved the actual node with ID 1 and creates and edge {@code 1 -> 3}
 * with the property HashPartition.
 */
@Internal
public class StreamGraphGenerator {

    private static final Logger LOG = LoggerFactory.getLogger(StreamGraphGenerator.class);

    public static final int DEFAULT_LOWER_BOUND_MAX_PARALLELISM =
            KeyGroupRangeAssignment.DEFAULT_LOWER_BOUND_MAX_PARALLELISM;

    public static final TimeCharacteristic DEFAULT_TIME_CHARACTERISTIC =
            TimeCharacteristic.ProcessingTime;

    public static final String DEFAULT_JOB_NAME = "Flink Streaming Job";

    public static final String DEFAULT_SLOT_SHARING_GROUP = "default";

    private final List<Transformation<?>> transformations;

    private final ExecutionConfig executionConfig;

    private final CheckpointConfig checkpointConfig;

    private final ReadableConfig configuration;

    private StateBackend stateBackend;

    private boolean chaining = true;

    private Collection<Tuple2<String, DistributedCache.DistributedCacheEntry>> userArtifacts;

    private TimeCharacteristic timeCharacteristic = DEFAULT_TIME_CHARACTERISTIC;

    private String jobName = DEFAULT_JOB_NAME;

    private SavepointRestoreSettings savepointRestoreSettings = SavepointRestoreSettings.none();

    private long defaultBufferTimeout = StreamingJobGraphGenerator.UNDEFINED_NETWORK_BUFFER_TIMEOUT;

    private RuntimeExecutionMode runtimeExecutionMode = RuntimeExecutionMode.STREAMING;

    private boolean shouldExecuteInBatchMode;

    @SuppressWarnings("rawtypes")
    private static final Map<
                    Class<? extends Transformation>,
                    TransformationTranslator<?, ? extends Transformation>>
            translatorMap;

    static {
        @SuppressWarnings("rawtypes")
        Map<Class<? extends Transformation>, TransformationTranslator<?, ? extends Transformation>>
                tmp = new HashMap<>();
        tmp.put(OneInputTransformation.class, new OneInputTransformationTranslator<>());
        tmp.put(TwoInputTransformation.class, new TwoInputTransformationTranslator<>());
        tmp.put(MultipleInputTransformation.class, new MultiInputTransformationTranslator<>());
        tmp.put(KeyedMultipleInputTransformation.class, new MultiInputTransformationTranslator<>());
        tmp.put(SourceTransformation.class, new SourceTransformationTranslator<>());
        tmp.put(SinkTransformation.class, new SinkTransformationTranslator<>());
        tmp.put(LegacySinkTransformation.class, new LegacySinkTransformationTranslator<>());
        tmp.put(LegacySourceTransformation.class, new LegacySourceTransformationTranslator<>());
        tmp.put(UnionTransformation.class, new UnionTransformationTranslator<>());
        tmp.put(PartitionTransformation.class, new PartitionTransformationTranslator<>());
        tmp.put(SideOutputTransformation.class, new SideOutputTransformationTranslator<>());
        tmp.put(ReduceTransformation.class, new ReduceTransformationTranslator<>());
        tmp.put(
                TimestampsAndWatermarksTransformation.class,
                new TimestampsAndWatermarksTransformationTranslator<>());
        tmp.put(BroadcastStateTransformation.class, new BroadcastStateTransformationTranslator<>());
        tmp.put(
                KeyedBroadcastStateTransformation.class,
                new KeyedBroadcastStateTransformationTranslator<>());
        translatorMap = Collections.unmodifiableMap(tmp);
    }

    // This is used to assign a unique ID to iteration source/sink
    protected static Integer iterationIdCounter = 0;

    public static int getNewIterationNodeId() {
        iterationIdCounter--;
        return iterationIdCounter;
    }

    private StreamGraph streamGraph;

    // Keep track of which Transforms we have already transformed, this is necessary because
    // we have loops, i.e. feedback edges.
    private Map<Transformation<?>, Collection<Integer>> alreadyTransformed;

    public StreamGraphGenerator(
            final List<Transformation<?>> transformations,
            final ExecutionConfig executionConfig,
            final CheckpointConfig checkpointConfig) {
        this(transformations, executionConfig, checkpointConfig, new Configuration());
    }

    public StreamGraphGenerator(
            List<Transformation<?>> transformations,
            ExecutionConfig executionConfig,
            CheckpointConfig checkpointConfig,
            ReadableConfig configuration) {
        this.transformations = checkNotNull(transformations);
        this.executionConfig = checkNotNull(executionConfig);
        this.checkpointConfig = new CheckpointConfig(checkpointConfig);
        this.configuration = checkNotNull(configuration);
    }

    public StreamGraphGenerator setRuntimeExecutionMode(
            final RuntimeExecutionMode runtimeExecutionMode) {
        this.runtimeExecutionMode = checkNotNull(runtimeExecutionMode);
        return this;
    }

    public StreamGraphGenerator setStateBackend(StateBackend stateBackend) {
        this.stateBackend = stateBackend;
        return this;
    }

    public StreamGraphGenerator setChaining(boolean chaining) {
        this.chaining = chaining;
        return this;
    }

    public StreamGraphGenerator setUserArtifacts(
            Collection<Tuple2<String, DistributedCache.DistributedCacheEntry>> userArtifacts) {
        this.userArtifacts = userArtifacts;
        return this;
    }

    public StreamGraphGenerator setTimeCharacteristic(TimeCharacteristic timeCharacteristic) {
        this.timeCharacteristic = timeCharacteristic;
        return this;
    }

    public StreamGraphGenerator setDefaultBufferTimeout(long defaultBufferTimeout) {
        this.defaultBufferTimeout = defaultBufferTimeout;
        return this;
    }

    public StreamGraphGenerator setJobName(String jobName) {
        this.jobName = jobName;
        return this;
    }

    public void setSavepointRestoreSettings(SavepointRestoreSettings savepointRestoreSettings) {
        this.savepointRestoreSettings = savepointRestoreSettings;
    }

    // 构造StreamGraph
    public StreamGraph generate() {

        //    streamGraph = {StreamGraph@4411}
        //    jobName = null
        //    executionConfig = {ExecutionConfig@4105} "ExecutionConfig{executionMode=PIPELINED,
        // closureCleanerLevel=RECURSIVE, parallelism=1, maxParallelism=-1,
        // numberOfExecutionRetries=-1, forceKryo=false, disableGenericTypes=false,
        // enableAutoGeneratedUids=true, objectReuse=false, autoTypeRegistrationEnabled=true,
        // forceAvro=false, autoWatermarkInterval=200, latencyTrackingInterval=0,
        // isLatencyTrackingConfigured=false, executionRetryDelay=10000,
        // restartStrategyConfiguration=Cluster level default restart strategy,
        // taskCancellationIntervalMillis=-1, taskCancellationTimeoutMillis=-1,
        // useSnapshotCompression=false, defaultInputDependencyConstraint=ANY,
        // globalJobParameters=org.apache.flink.api.common.ExecutionConfig$GlobalJobParameters@1,
        // registeredTypesWithKryoSerializers={}, registeredTypesWithKryoSerializerClasses={},
        // defaultKryoSerializers={}, defaultKryoSerializerClasses={}, registeredKryoTypes=[],
        // registeredPojoTypes=[]}"
        //            executionMode = {ExecutionMode@3509} "PIPELINED"
        //            closureCleanerLevel = {ExecutionConfig$ClosureCleanerLevel@3510} "RECURSIVE"
        //            parallelism = 4
        //            maxParallelism = -1
        //            numberOfExecutionRetries = -1
        //            forceKryo = false
        //            disableGenericTypes = false
        //            enableAutoGeneratedUids = true
        //            objectReuse = false
        //            autoTypeRegistrationEnabled = true
        //            forceAvro = false
        //            autoWatermarkInterval = 200
        //            latencyTrackingInterval = 0
        //            isLatencyTrackingConfigured = false
        //            executionRetryDelay = 10000
        //            restartStrategyConfiguration =
        // {RestartStrategies$FallbackRestartStrategyConfiguration@3511} "Cluster level default
        // restart strategy"
        //            taskCancellationIntervalMillis = -1
        //            taskCancellationTimeoutMillis = -1
        //            useSnapshotCompression = false
        //            defaultInputDependencyConstraint = {InputDependencyConstraint@3512} "ANY"
        //            globalJobParameters = {ExecutionConfig$GlobalJobParameters@3513}
        //            registeredTypesWithKryoSerializers = {LinkedHashMap@3514}  size = 0
        //            registeredTypesWithKryoSerializerClasses = {LinkedHashMap@3515}  size = 0
        //            defaultKryoSerializers = {LinkedHashMap@3516}  size = 0
        //            defaultKryoSerializerClasses = {LinkedHashMap@3517}  size = 0
        //            registeredKryoTypes = {LinkedHashSet@3518}  size = 0
        //            registeredPojoTypes = {LinkedHashSet@3519}  size = 0
        //    checkpointConfig = {CheckpointConfig@4399}
        //            checkpointingMode = {CheckpointingMode@3524} "EXACTLY_ONCE"
        //            checkpointInterval = -1
        //            checkpointTimeout = 600000
        //            minPauseBetweenCheckpoints = 0
        //            maxConcurrentCheckpoints = 1
        //            forceCheckpointing = false
        //            forceUnalignedCheckpoints = false
        //            unalignedCheckpointsEnabled = false
        //            alignmentTimeout = 0
        //            approximateLocalRecovery = false
        //            externalizedCheckpointCleanup = null
        //            failOnCheckpointingErrors = true
        //            preferCheckpointForRecovery = false
        //            tolerableCheckpointFailureNumber = -1
        //    savepointRestoreSettings = {SavepointRestoreSettings@4412}
        // "SavepointRestoreSettings.none()"
        //    scheduleMode = null
        //    chaining = false
        //    userArtifacts = null
        //    timeCharacteristic = null
        //    globalDataExchangeMode = null
        //    allVerticesInSameSlotSharingGroupByDefault = true
        //    streamNodes = {HashMap@4415}  size = 0
        //    sources = {HashSet@4416}  size = 0
        //    sinks = {HashSet@4417}  size = 0
        //    virtualSideOutputNodes = {HashMap@4418}  size = 0
        //    virtualPartitionNodes = {HashMap@4419}  size = 0
        //    vertexIDtoBrokerID = {HashMap@4420}  size = 0
        //    vertexIDtoLoopTimeout = {HashMap@4421}  size = 0
        //    stateBackend = null
        //    iterationSourceSinkPairs = {HashSet@4422}  size = 0
        //    timerServiceProvider = null
        streamGraph = new StreamGraph(executionConfig, checkpointConfig, savepointRestoreSettings);

        // STREAMING
        shouldExecuteInBatchMode = shouldExecuteInBatchMode(runtimeExecutionMode);

        configureStreamGraph(streamGraph);

        alreadyTransformed = new HashMap<>();

        // transformations 的数据实例. 这个有三个集合,
        // 我只截取了最新的一个.也就是下标为2的集合.
        //
        //        {LegacySinkTransformation@3460} "LegacySinkTransformation{id=5, name='Print to
        // Std. Out', outputType=GenericType<java.lang.Object>, parallelism=1}"
        //        |   input = {OneInputTransformation@3443} "OneInputTransformation{id=4,
        // name='Window(TumblingProcessingTimeWindows(5000), ProcessingTimeTrigger,
        // ReduceFunction$1, PassThroughWindowFunction)',
        // outputType=PojoType<org.apache.flink.streaming.examples.socket.SocketWindowWordCount$WordWithCount, fields = [count: Long, word: String]>, parallelism=4}"
        //        |   |     input = {PartitionTransformation@3577} "PartitionTransformation{id=3,
        // name='Partition',
        // outputType=PojoType<org.apache.flink.streaming.examples.socket.SocketWindowWordCount$WordWithCount, fields = [count: Long, word: String]>, parallelism=4}"
        //        |   |     |     input = {OneInputTransformation@3364}
        // "OneInputTransformation{id=2, name='Flat Map',
        // outputType=PojoType<org.apache.flink.streaming.examples.socket.SocketWindowWordCount$WordWithCount, fields = [count: Long, word: String]>, parallelism=4}"
        //        |   |     |     |---- input = {LegacySourceTransformation@3548}
        // "LegacySourceTransformation{id=1, name='Socket Stream', outputType=String,
        // parallelism=1}"
        //        |   |     |     |       |---- operatorFactory =
        // {SimpleUdfStreamOperatorFactory@3555}
        //        |   |     |     |       |---- boundedness = {Boundedness@3253}
        // "CONTINUOUS_UNBOUNDED"
        //        |   |     |     |       |---- id = 1
        //        |   |     |     |       |---- name = "Socket Stream"
        //        |   |     |     |       |---- outputType = {BasicTypeInfo@3254} "String"
        //        |   |     |     |       |---- typeUsed = true
        //        |   |     |     |       |---- parallelism = 1
        //        |   |     |     |       |---- maxParallelism = -1
        //        |   |     |     |       |---- minResources = {ResourceSpec@3549}
        // "ResourceSpec{UNKNOWN}"
        //        |   |     |     |       |---- preferredResources = {ResourceSpec@3549}
        // "ResourceSpec{UNKNOWN}"
        //        |   |     |     |       |---- managedMemoryOperatorScopeUseCaseWeights =
        // {HashMap@3556}  size = 0
        //        |   |     |     |       |---- managedMemorySlotScopeUseCases = {HashSet@3557}
        // size = 0
        //        |   |     |     |       |---- uid = null
        //        |   |     |     |       |---- userProvidedNodeHash = null
        //        |   |     |     |       |---- bufferTimeout = -1
        //        |   |     |     |       |---- slotSharingGroup = null
        //        |   |     |     |       |---- coLocationGroupKey = null
        //        |   |     |     |---- operatorFactory = {SimpleUdfStreamOperatorFactory@3370}
        //        |   |     |     |---- stateKeySelector = null
        //        |   |     |     |---- stateKeyType = null
        //        |   |     |     |---- id = 2
        //        |   |     |     |---- name = "Flat Map"
        //        |   |     |     |---- outputType = {PojoTypeInfo@3369}
        // "PojoType<org.apache.flink.streaming.examples.socket.SocketWindowWordCount$WordWithCount,
        // fields = [count: Long, word: String]>"
        //        |   |     |     |---- typeUsed = true
        //        |   |     |     |---- parallelism = 4
        //        |   |     |     |---- maxParallelism = -1
        //        |   |     |     |---- minResources = {ResourceSpec@3549} "ResourceSpec{UNKNOWN}"
        //        |   |     |     |---- preferredResources = {ResourceSpec@3549}
        // "ResourceSpec{UNKNOWN}"
        //        |   |     |     |---- managedMemoryOperatorScopeUseCaseWeights = {HashMap@3550}
        // size = 0
        //        |   |     |     |---- managedMemorySlotScopeUseCases = {HashSet@3551}  size = 0
        //        |   |     |     |---- uid = null
        //        |   |     |     |---- userProvidedNodeHash = null
        //        |   |     |     |---- bufferTimeout = -1
        //        |   |     |     |---- slotSharingGroup = null
        //        |   |     |     |---- coLocationGroupKey = null
        //        |   |     |-- partitioner = {KeyGroupStreamPartitioner@3591} "HASH"
        //        |   |     |-- shuffleMode = {ShuffleMode@3592} "UNDEFINED"
        //        |   |     |-- id = 3
        //        |   |     |-- name = "Partition"
        //        |   |     |-- outputType = {PojoTypeInfo@3369}
        // "PojoType<org.apache.flink.streaming.examples.socket.SocketWindowWordCount$WordWithCount,
        // fields = [count: Long, word: String]>"
        //        |   |     |-- typeUsed = true
        //        |   |     |-- parallelism = 4
        //        |   |     |-- maxParallelism = -1
        //        |   |     |-- minResources = {ResourceSpec@3549} "ResourceSpec{UNKNOWN}"
        //        |   |     |-- preferredResources = {ResourceSpec@3549} "ResourceSpec{UNKNOWN}"
        //        |   |     |-- managedMemoryOperatorScopeUseCaseWeights = {HashMap@3594}  size = 0
        //        |   |     |-- managedMemorySlotScopeUseCases = {HashSet@3595}  size = 0
        //        |   |     |-- uid = null
        //        |   |     |-- userProvidedNodeHash = null
        //        |   |     |-- bufferTimeout = -1
        //        |   |     |-- slotSharingGroup = null
        //        |   |     |-- coLocationGroupKey = null
        //        |   |-- operatorFactory = {SimpleUdfStreamOperatorFactory@3578}
        //        |   |-- stateKeySelector = {SocketWindowWordCount$lambda@3579}
        //        |   |-- stateKeyType = {BasicTypeInfo@3254} "String"
        //        |   |-- id = 4
        //        |   |-- name = "Window(TumblingProcessingTimeWindows(5000), ProcessingTimeTrigger,
        // ReduceFunction$1, PassThroughWindowFunction)"
        //        |   |-- outputType = {PojoTypeInfo@3369}
        // "PojoType<org.apache.flink.streaming.examples.socket.SocketWindowWordCount$WordWithCount,
        // fields = [count: Long, word: String]>"
        //        |   |-- typeUsed = true
        //        |   |-- parallelism = 4
        //        |   |-- maxParallelism = -1
        //        |   |-- minResources = {ResourceSpec@3549} "ResourceSpec{UNKNOWN}"
        //        |   |-- preferredResources = {ResourceSpec@3549} "ResourceSpec{UNKNOWN}"
        //        |   |-- managedMemoryOperatorScopeUseCaseWeights = {HashMap@3580}  size = 0
        //        |   |-- managedMemorySlotScopeUseCases = {HashSet@3581}  size = 1
        //        |   |-- uid = null
        //        |   |-- userProvidedNodeHash = null
        //        |   |-- bufferTimeout = -1
        //        |   |-- slotSharingGroup = null
        //        |   |-- coLocationGroupKey = null
        //        |-- operatorFactory = {SimpleUdfStreamOperatorFactory@3606}
        //        |-- stateKeySelector = null
        //        |-- stateKeyType = null
        //        |-- id = 5
        //        |-- name = "Print to Std. Out"
        //        |-- outputType = {GenericTypeInfo@3608} "GenericType<java.lang.Object>"
        //        |-- typeUsed = false
        //        |-- parallelism = 1
        //        |-- maxParallelism = -1
        //        |-- minResources = {ResourceSpec@3549} "ResourceSpec{UNKNOWN}"
        //        |-- preferredResources = {ResourceSpec@3549} "ResourceSpec{UNKNOWN}"
        //        |-- managedMemoryOperatorScopeUseCaseWeights = {HashMap@3609}  size = 0
        //        |-- managedMemorySlotScopeUseCases = {HashSet@3610}  size = 0
        //        |-- uid = null
        //        |-- userProvidedNodeHash = null
        //        |-- bufferTimeout = -1
        //        |-- slotSharingGroup = null
        //        |-- coLocationGroupKey = null

        // transformations 是在DataStream#doTransform方法中
        for (Transformation<?> transformation : transformations) {
            // 遍历所有transformation并转换为计算图
            transform(transformation);
        }

        final StreamGraph builtStreamGraph = streamGraph;

        alreadyTransformed.clear();
        alreadyTransformed = null;
        streamGraph = null;

        return builtStreamGraph;
    }

    private void configureStreamGraph(final StreamGraph graph) {
        checkNotNull(graph);

        // true
        graph.setChaining(chaining);
        //  size = 0
        graph.setUserArtifacts(userArtifacts);
        // EvnetTime
        graph.setTimeCharacteristic(timeCharacteristic);
        // Socket Window WordCount
        graph.setJobName(jobName);

        if (shouldExecuteInBatchMode) {

            if (checkpointConfig.isCheckpointingEnabled()) {
                LOG.info(
                        "Disabled Checkpointing. Checkpointing is not supported and not needed when executing jobs in BATCH mode.");
                checkpointConfig.disableCheckpointing();
            }

            graph.setGlobalDataExchangeMode(GlobalDataExchangeMode.FORWARD_EDGES_PIPELINED);
            graph.setScheduleMode(ScheduleMode.LAZY_FROM_SOURCES_WITH_BATCH_SLOT_REQUEST);
            setDefaultBufferTimeout(-1);
            setBatchStateBackendAndTimerService(graph);
        } else {
            graph.setStateBackend(stateBackend);

            // 立即调度所有的 任务.
            //  Schedules all tasks immediately.
            graph.setScheduleMode(ScheduleMode.EAGER);

            if (checkpointConfig.isApproximateLocalRecoveryEnabled()) {
                checkApproximateLocalRecoveryCompatibility();
                graph.setGlobalDataExchangeMode(
                        GlobalDataExchangeMode.ALL_EDGES_PIPELINED_APPROXIMATE);
            } else {

                // Set all job edges {@link ResultPartitionType#PIPELINED_BOUNDED}.
                graph.setGlobalDataExchangeMode(GlobalDataExchangeMode.ALL_EDGES_PIPELINED);
            }
        }
    }

    private void checkApproximateLocalRecoveryCompatibility() {
        checkState(
                !checkpointConfig.isUnalignedCheckpointsEnabled(),
                "Approximate Local Recovery and Unaligned Checkpoint can not be used together yet");
    }

    private void setBatchStateBackendAndTimerService(StreamGraph graph) {
        boolean useStateBackend = configuration.get(ExecutionOptions.USE_BATCH_STATE_BACKEND);
        boolean sortInputs = configuration.get(ExecutionOptions.SORT_INPUTS);
        checkState(
                !useStateBackend || sortInputs,
                "Batch state backend requires the sorted inputs to be enabled!");

        if (useStateBackend) {
            LOG.debug("Using BATCH execution state backend and timer service.");
            graph.setStateBackend(new BatchExecutionStateBackend());
            graph.setTimerServiceProvider(BatchExecutionInternalTimeServiceManager::create);
        } else {
            graph.setStateBackend(stateBackend);
        }
    }

    private boolean shouldExecuteInBatchMode(final RuntimeExecutionMode configuredMode) {
        final boolean existsUnboundedSource = existsUnboundedSource();

        checkState(
                configuredMode != RuntimeExecutionMode.BATCH || !existsUnboundedSource,
                "Detected an UNBOUNDED source with the '"
                        + ExecutionOptions.RUNTIME_MODE.key()
                        + "' set to 'BATCH'. "
                        + "This combination is not allowed, please set the '"
                        + ExecutionOptions.RUNTIME_MODE.key()
                        + "' to STREAMING or AUTOMATIC");

        if (checkNotNull(configuredMode) != RuntimeExecutionMode.AUTOMATIC) {
            return configuredMode == RuntimeExecutionMode.BATCH;
        }
        return !existsUnboundedSource;
    }

    private boolean existsUnboundedSource() {
        return transformations.stream()
                .anyMatch(
                        transformation ->
                                isUnboundedSource(transformation)
                                        || transformation.getTransitivePredecessors().stream()
                                                .anyMatch(this::isUnboundedSource));
    }

    private boolean isUnboundedSource(final Transformation<?> transformation) {
        checkNotNull(transformation);
        return transformation instanceof WithBoundedness
                && ((WithBoundedness) transformation).getBoundedness() != Boundedness.BOUNDED;
    }

    /**
     * Transforms one {@code Transformation}.
     *
     * <p>这将检查我们是否已经对其进行了转换，并在这种情况下提前退出。 如果不是，它将委托给特定于转换的方法处理。
     *
     * <p>This checks whether we already transformed it and exits early in that case. If not it
     * delegates to one of the transformation specific methods.
     */

    // 对每个 transformation 进行转换，转换成 StreamGraph 中的 StreamNode 和 StreamEdge
    // 返回值为该 transform 的 id 集合，通常大小为 1 个（除 FeedbackTransformation）
    private Collection<Integer> transform(Transformation<?> transform) {
        // 直接返回已经完成转换的实例
        if (alreadyTransformed.containsKey(transform)) {
            return alreadyTransformed.get(transform);
        }

        LOG.debug("Transforming " + transform);

        if (transform.getMaxParallelism() <= 0) {

            // if the max parallelism hasn't been set, then first use the job wide max parallelism
            // from the ExecutionConfig.
            int globalMaxParallelismFromConfig = executionConfig.getMaxParallelism();
            if (globalMaxParallelismFromConfig > 0) {
                transform.setMaxParallelism(globalMaxParallelismFromConfig);
            }
        }

        // 为了触发 MissingTypeInfo 的异常
        // call at least once to trigger exceptions about MissingTypeInfo
        transform.getOutputType();

        @SuppressWarnings("unchecked")
        final TransformationTranslator<?, Transformation<?>> translator =
                (TransformationTranslator<?, Transformation<?>>)
                        translatorMap.get(transform.getClass());

        Collection<Integer> transformedIds;
        if (translator != null) {
            // 转换 ???
            transformedIds = translate(translator, transform);
        } else {
            transformedIds = legacyTransform(transform);
        }

        // need this check because the iterate transformation adds itself before
        // transforming the feedback edges
        if (!alreadyTransformed.containsKey(transform)) {
            alreadyTransformed.put(transform, transformedIds);
        }

        return transformedIds;
    }

    private Collection<Integer> legacyTransform(Transformation<?> transform) {
        Collection<Integer> transformedIds;
        if (transform instanceof FeedbackTransformation<?>) {
            transformedIds = transformFeedback((FeedbackTransformation<?>) transform);
        } else if (transform instanceof CoFeedbackTransformation<?>) {
            transformedIds = transformCoFeedback((CoFeedbackTransformation<?>) transform);
        } else {
            throw new IllegalStateException("Unknown transformation: " + transform);
        }

        if (transform.getBufferTimeout() >= 0) {
            streamGraph.setBufferTimeout(transform.getId(), transform.getBufferTimeout());
        } else {
            streamGraph.setBufferTimeout(transform.getId(), defaultBufferTimeout);
        }

        if (transform.getUid() != null) {
            streamGraph.setTransformationUID(transform.getId(), transform.getUid());
        }
        if (transform.getUserProvidedNodeHash() != null) {
            streamGraph.setTransformationUserHash(
                    transform.getId(), transform.getUserProvidedNodeHash());
        }

        if (!streamGraph.getExecutionConfig().hasAutoGeneratedUIDsEnabled()) {
            if (transform instanceof PhysicalTransformation
                    && transform.getUserProvidedNodeHash() == null
                    && transform.getUid() == null) {
                throw new IllegalStateException(
                        "Auto generated UIDs have been disabled "
                                + "but no UID or hash has been assigned to operator "
                                + transform.getName());
            }
        }

        if (transform.getMinResources() != null && transform.getPreferredResources() != null) {
            streamGraph.setResources(
                    transform.getId(),
                    transform.getMinResources(),
                    transform.getPreferredResources());
        }

        streamGraph.setManagedMemoryUseCaseWeights(
                transform.getId(),
                transform.getManagedMemoryOperatorScopeUseCaseWeights(),
                transform.getManagedMemorySlotScopeUseCases());

        return transformedIds;
    }

    /**
     * Transforms a {@code FeedbackTransformation}.
     *
     * <p>This will recursively transform the input and the feedback edges. We return the
     * concatenation of the input IDs and the feedback IDs so that downstream operations can be
     * wired to both.
     *
     * <p>This is responsible for creating the IterationSource and IterationSink which are used to
     * feed back the elements.
     */
    private <T> Collection<Integer> transformFeedback(FeedbackTransformation<T> iterate) {

        if (shouldExecuteInBatchMode) {
            throw new UnsupportedOperationException(
                    "Iterations are not supported in BATCH"
                            + " execution mode. If you want to execute such a pipeline, please set the "
                            + "'"
                            + ExecutionOptions.RUNTIME_MODE.key()
                            + "'="
                            + RuntimeExecutionMode.STREAMING.name());
        }

        if (iterate.getFeedbackEdges().size() <= 0) {
            throw new IllegalStateException(
                    "Iteration " + iterate + " does not have any feedback edges.");
        }

        List<Transformation<?>> inputs = iterate.getInputs();
        checkState(inputs.size() == 1);
        Transformation<?> input = inputs.get(0);

        List<Integer> resultIds = new ArrayList<>();

        // first transform the input stream(s) and store the result IDs
        Collection<Integer> inputIds = transform(input);
        resultIds.addAll(inputIds);

        // the recursive transform might have already transformed this
        if (alreadyTransformed.containsKey(iterate)) {
            return alreadyTransformed.get(iterate);
        }

        // create the fake iteration source/sink pair
        Tuple2<StreamNode, StreamNode> itSourceAndSink =
                streamGraph.createIterationSourceAndSink(
                        iterate.getId(),
                        getNewIterationNodeId(),
                        getNewIterationNodeId(),
                        iterate.getWaitTime(),
                        iterate.getParallelism(),
                        iterate.getMaxParallelism(),
                        iterate.getMinResources(),
                        iterate.getPreferredResources());

        StreamNode itSource = itSourceAndSink.f0;
        StreamNode itSink = itSourceAndSink.f1;

        // We set the proper serializers for the sink/source
        streamGraph.setSerializers(
                itSource.getId(),
                null,
                null,
                iterate.getOutputType().createSerializer(executionConfig));
        streamGraph.setSerializers(
                itSink.getId(),
                iterate.getOutputType().createSerializer(executionConfig),
                null,
                null);

        // also add the feedback source ID to the result IDs, so that downstream operators will
        // add both as input
        resultIds.add(itSource.getId());

        // at the iterate to the already-seen-set with the result IDs, so that we can transform
        // the feedback edges and let them stop when encountering the iterate node
        alreadyTransformed.put(iterate, resultIds);

        // so that we can determine the slot sharing group from all feedback edges
        List<Integer> allFeedbackIds = new ArrayList<>();

        for (Transformation<T> feedbackEdge : iterate.getFeedbackEdges()) {
            Collection<Integer> feedbackIds = transform(feedbackEdge);
            allFeedbackIds.addAll(feedbackIds);
            for (Integer feedbackId : feedbackIds) {
                streamGraph.addEdge(feedbackId, itSink.getId(), 0);
            }
        }

        String slotSharingGroup = determineSlotSharingGroup(null, allFeedbackIds);
        // slot sharing group of iteration node must exist
        if (slotSharingGroup == null) {
            slotSharingGroup = "SlotSharingGroup-" + iterate.getId();
        }

        itSink.setSlotSharingGroup(slotSharingGroup);
        itSource.setSlotSharingGroup(slotSharingGroup);

        return resultIds;
    }

    /**
     * Transforms a {@code CoFeedbackTransformation}.
     *
     * <p>This will only transform feedback edges, the result of this transform will be wired to the
     * second input of a Co-Transform. The original input is wired directly to the first input of
     * the downstream Co-Transform.
     *
     * <p>This is responsible for creating the IterationSource and IterationSink which are used to
     * feed back the elements.
     */
    private <F> Collection<Integer> transformCoFeedback(CoFeedbackTransformation<F> coIterate) {

        if (shouldExecuteInBatchMode) {
            throw new UnsupportedOperationException(
                    "Iterations are not supported in BATCH"
                            + " execution mode. If you want to execute such a pipeline, please set the "
                            + "'"
                            + ExecutionOptions.RUNTIME_MODE.key()
                            + "'="
                            + RuntimeExecutionMode.STREAMING.name());
        }

        // For Co-Iteration we don't need to transform the input and wire the input to the
        // head operator by returning the input IDs, the input is directly wired to the left
        // input of the co-operation. This transform only needs to return the ids of the feedback
        // edges, since they need to be wired to the second input of the co-operation.

        // create the fake iteration source/sink pair
        Tuple2<StreamNode, StreamNode> itSourceAndSink =
                streamGraph.createIterationSourceAndSink(
                        coIterate.getId(),
                        getNewIterationNodeId(),
                        getNewIterationNodeId(),
                        coIterate.getWaitTime(),
                        coIterate.getParallelism(),
                        coIterate.getMaxParallelism(),
                        coIterate.getMinResources(),
                        coIterate.getPreferredResources());

        StreamNode itSource = itSourceAndSink.f0;
        StreamNode itSink = itSourceAndSink.f1;

        // We set the proper serializers for the sink/source
        streamGraph.setSerializers(
                itSource.getId(),
                null,
                null,
                coIterate.getOutputType().createSerializer(executionConfig));
        streamGraph.setSerializers(
                itSink.getId(),
                coIterate.getOutputType().createSerializer(executionConfig),
                null,
                null);

        Collection<Integer> resultIds = Collections.singleton(itSource.getId());

        // at the iterate to the already-seen-set with the result IDs, so that we can transform
        // the feedback edges and let them stop when encountering the iterate node
        alreadyTransformed.put(coIterate, resultIds);

        // so that we can determine the slot sharing group from all feedback edges
        List<Integer> allFeedbackIds = new ArrayList<>();

        for (Transformation<F> feedbackEdge : coIterate.getFeedbackEdges()) {
            Collection<Integer> feedbackIds = transform(feedbackEdge);
            allFeedbackIds.addAll(feedbackIds);
            for (Integer feedbackId : feedbackIds) {
                streamGraph.addEdge(feedbackId, itSink.getId(), 0);
            }
        }

        String slotSharingGroup = determineSlotSharingGroup(null, allFeedbackIds);

        itSink.setSlotSharingGroup(slotSharingGroup);
        itSource.setSlotSharingGroup(slotSharingGroup);

        return Collections.singleton(itSource.getId());
    }

    private Collection<Integer> translate(
            final TransformationTranslator<?, Transformation<?>> translator,
            final Transformation<?> transform) {
        checkNotNull(translator);
        checkNotNull(transform);

        final List<Collection<Integer>> allInputIds = getParentInputIds(transform.getInputs());

        // 根据不同的类型调用相应的转换逻辑

        // 已经处理, 直接返回..
        // the recursive call might have already transformed this
        if (alreadyTransformed.containsKey(transform)) {
            return alreadyTransformed.get(transform);
        }
        // 确定slot共享组 : default
        final String slotSharingGroup =
                determineSlotSharingGroup(
                        transform.getSlotSharingGroup(),
                        allInputIds.stream()
                                .flatMap(Collection::stream)
                                .collect(Collectors.toList()));

        final TransformationTranslator.Context context =
                new ContextImpl(this, streamGraph, slotSharingGroup, configuration);

        // 是否执行在批处理模式 ????  ==> translateForStreaming
        return shouldExecuteInBatchMode
                ? translator.translateForBatch(transform, context) // 批处理
                : translator.translateForStreaming(transform, context); // 流处理
    }

    /**
     * Returns a list of lists containing the ids of the nodes in the transformation graph that
     * correspond to the provided transformations. Each transformation may have multiple nodes.
     *
     * <p>Parent transformations will be translated if they are not already translated.
     *
     * @param parentTransformations the transformations whose node ids to return.
     * @return the nodeIds per transformation or an empty list if the {@code parentTransformations}
     *     are empty.
     */
    private List<Collection<Integer>> getParentInputIds(
            @Nullable final Collection<Transformation<?>> parentTransformations) {
        final List<Collection<Integer>> allInputIds = new ArrayList<>();
        if (parentTransformations == null) {
            return allInputIds;
        }

        for (Transformation<?> transformation : parentTransformations) {
            allInputIds.add(transform(transformation));
        }
        return allInputIds;
    }

    /**
     * Determines the slot sharing group for an operation based on the slot sharing group set by the
     * user and the slot sharing groups of the inputs.
     *
     * <p>If the user specifies a group name, this is taken as is. If nothing is specified and the
     * input operations all have the same group name then this name is taken. Otherwise the default
     * group is chosen.
     *
     * @param specifiedGroup The group specified by the user.
     * @param inputIds The IDs of the input operations.
     */
    private String determineSlotSharingGroup(String specifiedGroup, Collection<Integer> inputIds) {
        if (specifiedGroup != null) {
            return specifiedGroup;
        } else {
            String inputGroup = null;
            for (int id : inputIds) {
                String inputGroupCandidate = streamGraph.getSlotSharingGroup(id);
                if (inputGroup == null) {
                    inputGroup = inputGroupCandidate;
                } else if (!inputGroup.equals(inputGroupCandidate)) {
                    return DEFAULT_SLOT_SHARING_GROUP;
                }
            }
            return inputGroup == null ? DEFAULT_SLOT_SHARING_GROUP : inputGroup;
        }
    }

    private static class ContextImpl implements TransformationTranslator.Context {

        private final StreamGraphGenerator streamGraphGenerator;

        private final StreamGraph streamGraph;

        private final String slotSharingGroup;

        private final ReadableConfig config;

        public ContextImpl(
                final StreamGraphGenerator streamGraphGenerator,
                final StreamGraph streamGraph,
                final String slotSharingGroup,
                final ReadableConfig config) {
            this.streamGraphGenerator = checkNotNull(streamGraphGenerator);
            this.streamGraph = checkNotNull(streamGraph);
            this.slotSharingGroup = checkNotNull(slotSharingGroup);
            this.config = checkNotNull(config);
        }

        @Override
        public StreamGraph getStreamGraph() {
            return streamGraph;
        }

        @Override
        public Collection<Integer> getStreamNodeIds(final Transformation<?> transformation) {
            checkNotNull(transformation);
            final Collection<Integer> ids =
                    streamGraphGenerator.alreadyTransformed.get(transformation);
            checkState(
                    ids != null,
                    "Parent transformation \"" + transformation + "\" has not been transformed.");
            return ids;
        }

        @Override
        public String getSlotSharingGroup() {
            return slotSharingGroup;
        }

        @Override
        public long getDefaultBufferTimeout() {
            return streamGraphGenerator.defaultBufferTimeout;
        }

        @Override
        public ReadableConfig getGraphGeneratorConfig() {
            return config;
        }
    }
}
