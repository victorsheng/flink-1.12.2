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

package org.apache.flink.runtime.io.network.api.writer;

import org.apache.flink.annotation.Internal;
import org.apache.flink.runtime.state.KeyGroupRange;
import org.apache.flink.runtime.state.KeyGroupRangeAssignment;

import org.apache.flink.shaded.guava18.com.google.common.collect.Sets;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.util.Collections.emptySet;
import static java.util.Collections.singleton;

/**
 * {@code SubtaskStateMapper}缩小了回放期间需要读取的子任务，以便在检查点中存储了in-flight中的数据时从特定子任务恢复。
 *
 * <p>旧子任务到新子任务的映射可以是唯一的，也可以是非唯一的。
 *
 * <p>唯一分配意味着一个特定的旧子任务只分配给一个新的子任务。
 *
 * <p>非唯一分配需要向下筛选。 这意味着接收方必须交叉验证反序列化记录是否真正属于新的子任务。
 *
 * <p>大多数{@code SubtaskStateMapper}只会产生唯一的赋值，因此是最优的
 * 一些重缩放器，比如{@link#RANGE}，创建了唯一映射和非唯一映射的混合，其中下游任务需要对一些映射的子任务进行过滤。
 *
 * <p>The {@code SubtaskStateMapper} narrows down the subtasks that need to be read during rescaling
 * to recover from a particular subtask when in-flight data has been stored in the checkpoint.
 *
 * <p>Mappings of old subtasks to new subtasks may be unique or non-unique.
 *
 * <p>A unique assignment means that a particular old subtask is only assigned to exactly one new
 * subtask.
 *
 * <p>Non-unique assignments require filtering downstream.
 *
 * <p>That means that the receiver side has to cross-verify for a deserialized record if it truly
 * belongs to the new subtask or not.
 *
 * <p>Most {@code SubtaskStateMapper} will only produce unique assignments and are thus optimal.
 *
 * <p>Some rescaler, such as {@link #RANGE}, create a mixture of unique and non-unique mappings,
 * where downstream tasks need to filter on some mapped subtasks.
 */
@Internal
public enum SubtaskStateMapper {

    /**
     * 额外的状态被重新分配到其他子任务，而没有任何特定的保证（只匹配上行和下行）。
     *
     * <p>Extra state is redistributed to other subtasks without any specific guarantee (only that
     * up- and downstream are matched).
     */
    ARBITRARY {
        @Override
        public Set<Integer> getOldSubtasks(
                int newSubtaskIndex, int oldNumberOfSubtasks, int newNumberOfSubtasks) {
            // The current implementation uses round robin but that may be changed later.
            return ROUND_ROBIN.getOldSubtasks(
                    newSubtaskIndex, oldNumberOfSubtasks, newNumberOfSubtasks);
        }
    },

    /**
     * 丢弃额外状态。如果所有子任务都已包含相同的信息（广播），则非常有用。
     *
     * <p>Discards extra state. Useful if all subtasks already contain the same information
     * (broadcast).
     */
    DISCARD_EXTRA_STATE {
        @Override
        public Set<Integer> getOldSubtasks(
                int newSubtaskIndex, int oldNumberOfSubtasks, int newNumberOfSubtasks) {
            return newSubtaskIndex >= oldNumberOfSubtasks ? emptySet() : singleton(newSubtaskIndex);
        }
    },

    /** 将额外的子任务还原到第一个子任务。 Restores extra subtasks to the first subtask. */
    FIRST {
        @Override
        public Set<Integer> getOldSubtasks(
                int newSubtaskIndex, int oldNumberOfSubtasks, int newNumberOfSubtasks) {
            return newSubtaskIndex == 0
                    ? IntStream.range(0, oldNumberOfSubtasks).boxed().collect(Collectors.toSet())
                    : emptySet();
        }
    },

    /**
     * 将状态复制到所有子任务。这种回放会造成巨大的开销，完全依赖于对下游数据进行过滤。
     *
     * <p>Replicates the state to all subtasks. This rescaling causes a huge overhead and completely
     * relies on filtering the data downstream.
     */
    FULL {
        @Override
        public Set<Integer> getOldSubtasks(
                int newSubtaskIndex, int oldNumberOfSubtasks, int newNumberOfSubtasks) {
            return IntStream.range(0, oldNumberOfSubtasks).boxed().collect(Collectors.toSet());
        }
    },

    /**
     * 将旧范围重新映射到新范围。 对于较小的重缩放，这意味着新的子任务主要分配给2个旧的子任务。
     *
     * <p>举例: 旧的分配: 0 -> [0;43); 1 -> [43;87); 2 -> [87;128) 新的分配: 0 -> [0;64]; 1 -> [64;128)
     *
     * <p>子任务0从旧子任务0+1恢复数据，子任务1从旧子任务0+2恢复数据
     *
     * <p>对于从n到[n-1 .. n/2]，每个新的子任务正好分配了两个旧的子任务。 对于从n到[n+1 .. 2*n-1]，除最外层的两个子任务外，大多数子任务都分配了两个旧的子任务。
     *
     * <p>较大的比例因子（{@code<n/2}，{@code>2*n}）将相应地增加旧子任务的数量。 但是，它们还将创建更独特的分配，其中一个旧子任务只分配给新子任务。
     * 因此，非唯一映射的数目是2*n的上界。
     *
     * <p>Remaps old ranges to new ranges. For minor rescaling that means that new subtasks are
     * mostly assigned 2 old subtasks.
     *
     * <p>Example:<br>
     * old assignment: 0 -> [0;43); 1 -> [43;87); 2 -> [87;128)<br>
     * new assignment: 0 -> [0;64]; 1 -> [64;128)<br>
     * subtask 0 recovers data from old subtask 0 + 1 and subtask 1 recovers data from old subtask 0
     * + 2
     *
     * <p>For all downscale from n to [n-1 .. n/2], each new subtasks get exactly two old subtasks
     * assigned.
     *
     * <p>For all upscale from n to [n+1 .. 2*n-1], most subtasks get two old subtasks assigned,
     * except the two outermost.
     *
     * <p>Larger scale factors ({@code <n/2}, {@code >2*n}), will increase the number of old
     * subtasks accordingly.
     *
     * <p>However, they will also create more unique assignment, where an old subtask is exclusively
     * assigned to a new subtask.
     *
     * <p>Thus, the number of non-unique mappings is upper bound by 2*n.
     */
    RANGE {
        @Override
        public Set<Integer> getOldSubtasks(
                int newSubtaskIndex, int oldNumberOfSubtasks, int newNumberOfSubtasks) {
            // the actual maxParallelism cancels out
            int maxParallelism = KeyGroupRangeAssignment.UPPER_BOUND_MAX_PARALLELISM;
            final KeyGroupRange newRange =
                    KeyGroupRangeAssignment.computeKeyGroupRangeForOperatorIndex(
                            maxParallelism, newNumberOfSubtasks, newSubtaskIndex);
            final int start =
                    KeyGroupRangeAssignment.computeOperatorIndexForKeyGroup(
                            maxParallelism, oldNumberOfSubtasks, newRange.getStartKeyGroup());
            final int end =
                    KeyGroupRangeAssignment.computeOperatorIndexForKeyGroup(
                            maxParallelism, oldNumberOfSubtasks, newRange.getEndKeyGroup());
            return IntStream.range(start, end + 1).boxed().collect(Collectors.toSet());
        }
    },

    /**
     * 以循环方式重新分配子任务状态。 返回{@code newIndex->oldIndex}的映射。 通过使用{@code Bitset oldIndexes =
     * mapping.get(newIndex)}. 对于{@code oldParallelism < newParallelism} , 映射是琐碎的 . 比如
     * oldParallelism = 6 and newParallelism = 10.
     *
     * <p>--------------------------- | New index | Old indexes | --------------------------- | 0 |
     * 0 | --------------------------- | 1 | 1 | --------------------------- .......
     * --------------------------- | 5 | 5 | --------------------------- | 6 | |
     * --------------------------- ....... --------------------------- | 9 | |
     * ---------------------------
     *
     * <p>对于{@code oldParallelism > newParallelism} ，新索引通过以循环方式环绕赋值来获得多个赋值。
     *
     * <p>比如 oldParallelism = 10 and newParallelism = 4.
     *
     * <p>--------------------------- | New index | Old indexes | --------------------------- | 0 |
     * 0, 4, 8 | --------------------------- | 1 | 1, 5, 9 | --------------------------- | 2 | 2, 6
     * | --------------------------- | 3 | 3, 7 | ---------------------------
     *
     * <p>Redistributes subtask state in a round robin fashion.
     *
     * <p>Returns a mapping of {@code newIndex -> oldIndexes}. The mapping is accessed by using
     * {@code Bitset oldIndexes = mapping.get(newIndex)}.
     *
     * <p>For {@code oldParallelism < newParallelism}, that mapping is trivial. For example if
     * oldParallelism = 6 and newParallelism = 10.
     *
     * <table>
     *     <thead><td>New index</td><td>Old indexes</td></thead>
     *     <tr><td>0</td><td>0</td></tr>
     *     <tr><td>1</td><td>1</td></tr>
     *     <tr><td span="2" align="center">...</td></tr>
     *     <tr><td>5</td><td>5</td></tr>
     *     <tr><td>6</td><td></td></tr>
     *     <tr><td span="2" align="center">...</td></tr>
     *     <tr><td>9</td><td></td></tr>
     * </table>
     *
     * <p>For {@code oldParallelism > newParallelism}, new indexes get multiple assignments by
     * wrapping around assignments in a round-robin fashion. For example if oldParallelism = 10 and
     * newParallelism = 4.
     *
     * <table>
     *     <thead><td>New index</td><td>Old indexes</td></thead>
     *     <tr><td>0</td><td>0, 4, 8</td></tr>
     *     <tr><td>1</td><td>1, 5, 9</td></tr>
     *     <tr><td>2</td><td>2, 6</td></tr>
     *     <tr><td>3</td><td>3, 7</td></tr>
     * </table>
     */
    ROUND_ROBIN {
        @Override
        public Set<Integer> getOldSubtasks(
                int newSubtaskIndex, int oldNumberOfSubtasks, int newNumberOfSubtasks) {
            final Set<Integer> subtasks =
                    Sets.newHashSetWithExpectedSize(newNumberOfSubtasks / oldNumberOfSubtasks + 1);
            for (int subtask = newSubtaskIndex;
                    subtask < oldNumberOfSubtasks;
                    subtask += newNumberOfSubtasks) {
                subtasks.add(subtask);
            }
            return subtasks;
        }
    };

    /**
     * Returns all old subtask indexes that need to be read to restore all buffers for the given new
     * subtask index on rescale.
     */
    public abstract Set<Integer> getOldSubtasks(
            int newSubtaskIndex, int oldNumberOfSubtasks, int newNumberOfSubtasks);

    /** Returns a mapping new subtask index to all old subtask indexes. */
    public Map<Integer, Set<Integer>> getNewToOldSubtasksMapping(
            int oldParallelism, int newParallelism) {
        return IntStream.range(0, newParallelism)
                .boxed()
                .collect(
                        Collectors.toMap(
                                Function.identity(),
                                channelIndex ->
                                        getOldSubtasks(
                                                channelIndex, oldParallelism, newParallelism)));
    }
}
