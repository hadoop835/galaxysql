/*
 * Copyright [2013-2021], Alibaba Group Holding Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.polardbx.optimizer.core.planner.rule;

import com.alibaba.polardbx.common.properties.ConnectionParams;
import com.alibaba.polardbx.optimizer.PlannerContext;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import org.apache.calcite.linq4j.Ord;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.core.GroupConcatAggregateCall;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.logical.LogicalAggregate;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlAggFunction;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.fun.SqlMinMaxAggFunction;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.fun.SqlSumEmptyIsZeroAggFunction;
import org.apache.calcite.sql.type.ReturnTypes;
import org.apache.calcite.tools.RelBuilder;
import org.apache.calcite.util.ImmutableBitSet;
import org.apache.calcite.util.ImmutableIntList;
import org.apache.calcite.util.Pair;
import org.apache.calcite.util.Util;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * This rules is copied from Calcite's {@link org.apache.calcite.rel.rules.AggregateExpandDistinctAggregatesRule}.
 * Modification:
 * - Throws an exception if aggregate both contains approximate distinct aggregate call and distinct
 * aggregate call.
 * - Exclude non-simple aggregate(e.g. CUBE, ROLLUP).
 * - Fix bug: Some aggregate functions (e.g. COUNT) has a non-null result even without any input.
 * - Fix bug: Add filter argument into rewritten aggregateCall if filter arg is not -1.
 */

/**
 * Planner rule that expands distinct aggregates
 * (such as {@code COUNT(DISTINCT x)}) from a
 * {@link org.apache.calcite.rel.core.Aggregate}.
 *
 * <p>How this is done depends upon the arguments to the function. If all
 * functions have the same argument
 * (e.g. {@code COUNT(DISTINCT x), SUM(DISTINCT x)} both have the argument
 * {@code x}) then one extra {@link org.apache.calcite.rel.core.Aggregate} is
 * sufficient.
 *
 * <p>If there are multiple arguments
 * (e.g. {@code COUNT(DISTINCT x), COUNT(DISTINCT y)})
 * the rule creates separate {@code Aggregate}s and combines using a
 * {@link org.apache.calcite.rel.core.Join}.
 */
public final class DrdsAggregateExpandDistinctAggregatesRule extends RelOptRule {
    //~ Static fields/initializers ---------------------------------------------

    /**
     * The default instance of the rule; operates only on logical expressions.
     */
    public static final DrdsAggregateExpandDistinctAggregatesRule INSTANCE =
        new DrdsAggregateExpandDistinctAggregatesRule(LogicalAggregate.class, true);

    /**
     * Instance of the rule that operates only on logical expressions and
     * generates a join.
     */
    public static final DrdsAggregateExpandDistinctAggregatesRule JOIN =
        new DrdsAggregateExpandDistinctAggregatesRule(LogicalAggregate.class, false);

    public final boolean useGroupingSets;

    //~ Constructors -----------------------------------------------------------

    public DrdsAggregateExpandDistinctAggregatesRule(
        Class<? extends Aggregate> clazz,
        boolean useGroupingSets) {
        super(operand(clazz, any()), "DrdsAggregateExpandDistinctAggregatesRule");
        this.useGroupingSets = useGroupingSets;
    }

    @Override
    public boolean matches(RelOptRuleCall call) {
        return PlannerContext.getPlannerContext(call).getParamManager()
            .getBoolean(ConnectionParams.ENABLE_EXPAND_DISTINCTAGG);
    }

    //~ Methods ----------------------------------------------------------------

    public void onMatch(RelOptRuleCall call) {
        final LogicalAggregate aggregate = call.rel(0);
        boolean containsDistinctCall = false;
        for (AggregateCall aggCall : aggregate.getAggCallList()) {
            if (aggCall instanceof GroupConcatAggregateCall) {
                GroupConcatAggregateCall groupConcatAggCall = (GroupConcatAggregateCall) aggCall;
                if (!groupConcatAggCall.getOrderList().isEmpty()) {
                    return;
                }
            }
            if (aggCall.isDistinct()) {
                containsDistinctCall = true;
            }
        }

        if (!containsDistinctCall) {
            return;
        }

        // If this aggregate is a non-simple aggregate(e.g. CUBE, ROLLUP)
        // and contains distinct calls, it should be transformed to simple aggregate first
        // by DecomposeGroupingSetsRule. Then this rule expands it's distinct aggregates.
        if (aggregate.getGroupSets().size() > 1) {
            return;
        }

        aggregate.getAggOptimizationContext().setFromDistinctAgg(true);
        // Find all of the agg expressions. We use a LinkedHashSet to ensure determinism.
        int nonDistinctAggCallCount = 0;  // find all aggregate calls without distinct
        int filterCount = 0;
        int unsupportedNonDistinctAggCallCount = 0;
        final Set<Pair<List<Integer>, Integer>> argLists = new LinkedHashSet<>();
        for (AggregateCall aggCall : aggregate.getAggCallList()) {
            if (aggCall.filterArg >= 0) {
                ++filterCount;
            }
            if (!aggCall.isDistinct()) {
                ++nonDistinctAggCallCount;
                final SqlKind aggCallKind = aggCall.getAggregation().getKind();
                // We only support COUNT/SUM/MIN/MAX for the "single" count distinct optimization
                switch (aggCallKind) {
                case COUNT:
                case SUM:
                case SUM0:
                case MIN:
                case MAX:
                    break;
                default:
                    ++unsupportedNonDistinctAggCallCount;
                }
            } else {
                argLists.add(Pair.of(aggCall.getArgList(), aggCall.filterArg));
            }
        }

        final int distinctAggCallCount =
            aggregate.getAggCallList().size() - nonDistinctAggCallCount;
        Preconditions.checkState(argLists.size() > 0, "containsDistinctCall lied");

        // If all of the agg expressions are distinct and have the same
        // arguments then we can use a more efficient form.
        if (nonDistinctAggCallCount == 0
            && argLists.size() == 1
            && aggregate.getGroupType() == Aggregate.Group.SIMPLE) {
            final Pair<List<Integer>, Integer> pair =
                com.google.common.collect.Iterables.getOnlyElement(argLists);
            final RelBuilder relBuilder = call.builder();
            convertMonopole(relBuilder, aggregate, pair.left, pair.right);
            call.transformTo(relBuilder.build());
            return;
        }

        if (useGroupingSets) {
            rewriteUsingGroupingSets(call, aggregate);
            return;
        }

        // If only one distinct aggregate and one or more non-distinct aggregates,
        // we can generate multi-phase aggregates
        if (distinctAggCallCount == 1 // one distinct aggregate
            && filterCount == 0 // no filter
            && unsupportedNonDistinctAggCallCount == 0 // sum/min/max/count in non-distinct aggregate
            && nonDistinctAggCallCount > 0) { // one or more non-distinct aggregates
            final RelBuilder relBuilder = call.builder();
            convertSingletonDistinct(relBuilder, aggregate, argLists);
            call.transformTo(relBuilder.build());
            return;
        }

        // Create a list of the expressions which will yield the final result.
        // Initially, the expressions point to the input field.
        final List<RelDataTypeField> aggFields =
            aggregate.getRowType().getFieldList();
        final List<RexInputRef> refs = new ArrayList<>();
        final List<String> fieldNames = aggregate.getRowType().getFieldNames();
        final ImmutableBitSet groupSet = aggregate.getGroupSet();
        final int groupAndIndicatorCount =
            aggregate.getGroupCount() + aggregate.getIndicatorCount();
        for (int i : Util.range(groupAndIndicatorCount)) {
            refs.add(RexInputRef.of(i, aggFields));
        }

        // Aggregate the original relation, including any non-distinct aggregates.
        final List<AggregateCall> newAggCallList = new ArrayList<>();
        int i = -1;
        for (AggregateCall aggCall : aggregate.getAggCallList()) {
            ++i;
            if (aggCall.isDistinct()) {
                refs.add(null);
                continue;
            }
            refs.add(
                new RexInputRef(
                    groupAndIndicatorCount + newAggCallList.size(),
                    aggFields.get(groupAndIndicatorCount + i).getType()));
            newAggCallList.add(aggCall);
        }

        // In the case where there are no non-distinct aggregates (regardless of
        // whether there are group bys), there's no need to generate the
        // extra aggregate and join.
        final RelBuilder relBuilder = call.builder();
        relBuilder.push(aggregate.getInput());
        int n = 0;
        if (!newAggCallList.isEmpty()) {
            final RelBuilder.GroupKey groupKey =
                relBuilder.groupKey(groupSet, aggregate.getGroupSets());
            relBuilder.aggregate(groupKey, newAggCallList);
            ++n;
        }

        // For each set of operands, find and rewrite all calls which have that
        // set of operands.
        for (Pair<List<Integer>, Integer> argList : argLists) {
            doRewrite(relBuilder, aggregate, n++, argList.left, argList.right, refs);
        }

        relBuilder.project(refs, fieldNames);
        call.transformTo(relBuilder.build());
    }

    /**
     * Converts an aggregate with one distinct aggregate and one or more
     * non-distinct aggregates to multi-phase aggregates (see reference example
     * below).
     *
     * @param relBuilder Contains the input relational expression
     * @param aggregate Original aggregate
     * @param argLists Arguments and filters to the distinct aggregate function
     */
    private RelBuilder convertSingletonDistinct(RelBuilder relBuilder,
                                                Aggregate aggregate, Set<Pair<List<Integer>, Integer>> argLists) {

        // In this case, we are assuming that there is a single distinct function.
        // So make sure that argLists is of size one.
        Preconditions.checkArgument(argLists.size() == 1);

        // For example,
        //    SELECT deptno, COUNT(*), SUM(bonus), MIN(DISTINCT sal)
        //    FROM emp
        //    GROUP BY deptno
        //
        // becomes
        //
        //    SELECT deptno, SUM(cnt), SUM(bonus), MIN(sal)
        //    FROM (
        //          SELECT deptno, COUNT(*) as cnt, SUM(bonus), sal
        //          FROM EMP
        //          GROUP BY deptno, sal)            // Aggregate B
        //    GROUP BY deptno                        // Aggregate A
        relBuilder.push(aggregate.getInput());

        final List<AggregateCall> originalAggCalls = aggregate.getAggCallList();
        final ImmutableBitSet originalGroupSet = aggregate.getGroupSet();

        // Add the distinct aggregate column(s) to the group-by columns,
        // if not already a part of the group-by
        final SortedSet<Integer> bottomGroupSet = new TreeSet<>();
        bottomGroupSet.addAll(aggregate.getGroupSet().asList());
        for (AggregateCall aggCall : originalAggCalls) {
            if (aggCall.isDistinct()) {
                bottomGroupSet.addAll(aggCall.getArgList());
                break;  // since we only have single distinct call
            }
        }

        // Generate the intermediate aggregate B, the one on the bottom that converts
        // a distinct call to group by call.
        // Bottom aggregate is the same as the original aggregate, except that
        // the bottom aggregate has converted the DISTINCT aggregate to a group by clause.
        final List<AggregateCall> bottomAggregateCalls = new ArrayList<>();
        for (AggregateCall aggCall : originalAggCalls) {
            // Project the column corresponding to the distinct aggregate. Project
            // as-is all the non-distinct aggregates
            if (!aggCall.isDistinct()) {
                final AggregateCall newCall =
                    AggregateCall.create(aggCall.getAggregation(), false,
                        aggCall.isApproximate(), aggCall.getArgList(), -1,
                        ImmutableBitSet.of(bottomGroupSet).cardinality(),
                        relBuilder.peek(), null, aggCall.name);
                bottomAggregateCalls.add(newCall);
            }
        }
        // Generate the aggregate B (see the reference example above)
        relBuilder.push(
            aggregate.copy(
                aggregate.getTraitSet(), relBuilder.build(),
                false, ImmutableBitSet.of(bottomGroupSet), null, bottomAggregateCalls));

        // Add aggregate A (see the reference example above), the top aggregate
        // to handle the rest of the aggregation that the bottom aggregate hasn't handled
        final List<AggregateCall> topAggregateCalls = com.google.common.collect.Lists.newArrayList();
        // Use the remapped arguments for the (non)distinct aggregate calls
        int nonDistinctAggCallProcessedSoFar = 0;
        for (AggregateCall aggCall : originalAggCalls) {
            final AggregateCall newCall;
            if (aggCall.isDistinct()) {
                List<Integer> newArgList = new ArrayList<>();
                for (int arg : aggCall.getArgList()) {
                    newArgList.add(bottomGroupSet.headSet(arg).size());
                }
                newCall =
                    AggregateCall.create(aggCall.getAggregation(),
                        false,
                        aggCall.isApproximate(),
                        newArgList,
                        -1,
                        originalGroupSet.cardinality(),
                        relBuilder.peek(),
                        aggCall.getType(),
                        aggCall.name);
            } else {
                // If aggregate B had a COUNT aggregate call the corresponding aggregate at
                // aggregate A must be SUM. For other aggregates, it remains the same.
                final List<Integer> newArgs =
                    com.google.common.collect.Lists.newArrayList(
                        bottomGroupSet.size() + nonDistinctAggCallProcessedSoFar);
                if (aggCall.getAggregation().getKind() == SqlKind.COUNT) {
                    newCall =
                        AggregateCall.create(new SqlSumEmptyIsZeroAggFunction(), false,
                            aggCall.isApproximate(), newArgs, -1,
                            originalGroupSet.cardinality(), relBuilder.peek(),
                            aggCall.getType(), aggCall.getName());
                } else {
                    newCall =
                        AggregateCall.create(aggCall.getAggregation(), false,
                            aggCall.isApproximate(), newArgs, -1,
                            originalGroupSet.cardinality(),
                            relBuilder.peek(), aggCall.getType(), aggCall.name);
                }
                nonDistinctAggCallProcessedSoFar++;
            }

            topAggregateCalls.add(newCall);
        }

        // Populate the group-by keys with the remapped arguments for aggregate A
        // The top groupset is basically an identity (first X fields of aggregate B's
        // output), minus the distinct aggCall's input.
        final Set<Integer> topGroupSet = new HashSet<>();
        int groupSetToAdd = 0;
        for (int bottomGroup : bottomGroupSet) {
            if (originalGroupSet.get(bottomGroup)) {
                topGroupSet.add(groupSetToAdd);
            }
            groupSetToAdd++;
        }
        relBuilder.push(
            aggregate.copy(aggregate.getTraitSet(),
                relBuilder.build(), aggregate.indicator,
                ImmutableBitSet.of(topGroupSet), null, topAggregateCalls));
        return relBuilder;
    }

    private void rewriteUsingGroupingSets(RelOptRuleCall call,
                                          LogicalAggregate aggregate) {
        final Set<ImmutableBitSet> groupSetTreeSet =
            new TreeSet<>(ImmutableBitSet.ORDERING);
        final Map<ImmutableBitSet, Integer> groupSetToFilterArg = new LinkedHashMap<>();
        for (AggregateCall aggCall : aggregate.getAggCallList()) {
            if (!aggCall.isDistinct()) {
                groupSetTreeSet.add(aggregate.getGroupSet());
            } else {
                ImmutableBitSet groupSet = ImmutableBitSet.of(aggCall.getArgList())
                    .setIf(aggCall.filterArg, aggCall.filterArg >= 0)
                    .union(aggregate.getGroupSet());
                groupSetTreeSet.add(groupSet);
                if (aggCall.filterArg >= 0) {
                    groupSetToFilterArg.put(groupSet, aggCall.filterArg);
                }
            }
        }

        final com.google.common.collect.ImmutableList<ImmutableBitSet> groupSets =
            com.google.common.collect.ImmutableList.copyOf(groupSetTreeSet);
        final ImmutableBitSet fullGroupSet = ImmutableBitSet.union(groupSets);

        final Map<ImmutableBitSet, Integer> newGroupSetToFilterArg = new LinkedHashMap<>();
        for (Map.Entry<ImmutableBitSet, Integer> entry : groupSetToFilterArg.entrySet()) {
            newGroupSetToFilterArg.put(
                entry.getKey(),
                fullGroupSet.indexOf(entry.getValue()));
        }

        final List<AggregateCall> distinctAggCalls = new ArrayList<>();
        for (Pair<AggregateCall, String> aggCall : aggregate.getNamedAggCalls()) {
            if (!aggCall.left.isDistinct()) {
                AggregateCall newAggCall = aggCall.left.adaptTo(
                    aggregate.getInput(),
                    aggCall.left.getArgList(),
                    aggCall.left.filterArg,
                    aggregate.getGroupCount(),
                    fullGroupSet.cardinality());
                distinctAggCalls.add(newAggCall.rename(aggCall.right));
            }
        }

        final RelBuilder relBuilder = call.builder();
        relBuilder.push(aggregate.getInput());
        final int groupCount = fullGroupSet.cardinality();

        final Map<ImmutableBitSet, Integer> filters = new LinkedHashMap<>();
        final int z = groupCount + distinctAggCalls.size();
        distinctAggCalls.add(
            AggregateCall.create(SqlStdOperatorTable.GROUPING, false, false,
                ImmutableIntList.copyOf(fullGroupSet), -1, groupSets.size(),
                relBuilder.peek(), null, "$g"));
        for (Ord<ImmutableBitSet> groupSet : Ord.zip(groupSets)) {
            filters.put(groupSet.e, z + groupSet.i);
        }

        relBuilder.aggregate(relBuilder.groupKey(fullGroupSet, groupSets),
            distinctAggCalls);
        final LogicalAggregate distinct = (LogicalAggregate) relBuilder.peek();
        distinct.getAggOptimizationContext().setFromDistinctAgg(true);

        final Map<ImmutableBitSet, RexNode> newFilterArgInputRef = new LinkedHashMap<>();
        for (Map.Entry<ImmutableBitSet, Integer> entry : filters.entrySet()) {
            if (newGroupSetToFilterArg.containsKey(entry.getKey())) {
                RexNode node = relBuilder.field(newGroupSetToFilterArg.get(entry.getKey()));
                newFilterArgInputRef.put(entry.getKey(),
                    relBuilder.call(SqlStdOperatorTable.IS_TRUE, node));
            }
        }

        // GROUPING returns an integer (0 or 1). Add a project to convert those
        // values to BOOLEAN.
        if (!filters.isEmpty()) {
            final List<RexNode> nodes = new ArrayList<>(relBuilder.fields());
            final RexNode nodeZ = nodes.remove(nodes.size() - 1);
            for (Map.Entry<ImmutableBitSet, Integer> entry : filters.entrySet()) {
                final long v = groupValue(fullGroupSet, entry.getKey());
                if (newFilterArgInputRef.containsKey(entry.getKey())) {
                    nodes.add(
                        relBuilder.alias(
                            relBuilder.and(newFilterArgInputRef.get(entry.getKey()),
                                relBuilder.equals(nodeZ, relBuilder.literal(v))),
                            "$g_" + v));
                } else {
                    nodes.add(
                        relBuilder.alias(
                            relBuilder.equals(nodeZ, relBuilder.literal(v)),
                            "$g_" + v));
                }
            }
            relBuilder.project(nodes);
        }

        int aggCallIdx = 0;
        int x = groupCount;
        final List<AggregateCall> newCalls = new ArrayList<>();
        // TODO supports more aggCalls (currently only supports COUNT)
        // Some aggregate functions (e.g. COUNT) have the special property that they can return a
        // non-null result without any input. We need to make sure we return a result in this case.
        final List<Integer> needDefaultValueAggCalls = new ArrayList<>();
        Set<Integer> nullRefs = new HashSet<>();
        for (AggregateCall aggCall : aggregate.getAggCallList()) {
            final int newFilterArg;
            final List<Integer> newArgList;
            final SqlAggFunction aggregation;
            if (!aggCall.isDistinct()) {
                aggregation = new SqlMinMaxAggFunction(SqlKind.MIN, ReturnTypes.ARG0_NULLABLE_IF_EMPTY);
                newArgList = ImmutableIntList.of(x++);
                newFilterArg = filters.get(aggregate.getGroupSet());
                switch (aggCall.getAggregation().getKind()) {
                case COUNT:
                    needDefaultValueAggCalls.add(aggCallIdx);
                    break;
                default:
                }
            } else {
                aggregation = aggCall.getAggregation();
                newArgList = remap(fullGroupSet, aggCall.getArgList());
                newFilterArg =
                    filters.get(
                        ImmutableBitSet.of(aggCall.getArgList())
                            .setIf(aggCall.filterArg, aggCall.filterArg >= 0)
                            .union(aggregate.getGroupSet()));
            }
            SqlKind sqlKind = aggregation.getKind();
            AggregateCall newCall;
            if (sqlKind == SqlKind.GROUP_CONCAT) {
                GroupConcatAggregateCall groupConcatAggCall = (GroupConcatAggregateCall) aggCall;
                newCall =
                    groupConcatAggCall.create(false, newArgList, newFilterArg, aggregate.getGroupCount(), distinct,
                        aggCall.getType());
            } else {
                newCall = AggregateCall.create(aggregation, false, aggCall.isApproximate(),
                    newArgList, newFilterArg, aggregate.getGroupCount(), distinct,
                    aggCall.getType(), aggCall.name);
            }

            newCalls.add(newCall);
            aggCallIdx++;
        }

        buildNullFilter(nullRefs, relBuilder);

        relBuilder.aggregate(
            relBuilder.groupKey(
                remap(fullGroupSet, aggregate.getGroupSet()),
                remap(fullGroupSet, aggregate.getGroupSets())),
            newCalls);
        if (!needDefaultValueAggCalls.isEmpty() && aggregate.getGroupCount() == 0) {
            final Aggregate newAgg = (Aggregate) relBuilder.peek();
            final List<RexNode> nodes = new ArrayList<>();
            for (int i = 0; i < newAgg.getGroupCount(); ++i) {
                nodes.add(RexInputRef.of(i, newAgg.getRowType()));
            }
            for (int i = 0; i < newAgg.getAggCallList().size(); ++i) {
                final RexNode inputRef = RexInputRef.of(newAgg.getGroupCount() + i, newAgg.getRowType());
                RexNode newNode = inputRef;
                if (needDefaultValueAggCalls.contains(i)) {
                    SqlKind originalFunKind = aggregate.getAggCallList().get(i).getAggregation().getKind();
                    switch (originalFunKind) {
                    case COUNT:
                        newNode = relBuilder.call(
                            SqlStdOperatorTable.CASE,
                            relBuilder.isNotNull(inputRef),
                            inputRef,
                            relBuilder.literal(BigDecimal.ZERO));
                        break;
                    default:
                    }
                }
                nodes.add(newNode);
            }
            relBuilder.project(nodes);
        }

        relBuilder.convert(aggregate.getRowType(), true);
        call.transformTo(relBuilder.build());
    }

    private static long groupValue(ImmutableBitSet fullGroupSet,
                                   ImmutableBitSet groupSet) {
        long v = 0;
        long x = 1L << (fullGroupSet.cardinality() - 1);
        assert fullGroupSet.contains(groupSet);
        for (int i : fullGroupSet) {
            if (!groupSet.get(i)) {
                v |= x;
            }
            x >>= 1;
        }
        return v;
    }

    private static ImmutableBitSet remap(ImmutableBitSet groupSet,
                                         ImmutableBitSet bitSet) {
        final ImmutableBitSet.Builder builder = ImmutableBitSet.builder();
        for (Integer bit : bitSet) {
            builder.set(remap(groupSet, bit));
        }
        return builder.build();
    }

    private static com.google.common.collect.ImmutableList<ImmutableBitSet> remap(
        ImmutableBitSet groupSet,
        Iterable<ImmutableBitSet> bitSets) {
        final com.google.common.collect.ImmutableList.Builder<ImmutableBitSet> builder =
            com.google.common.collect.ImmutableList.builder();
        for (ImmutableBitSet bitSet : bitSets) {
            builder.add(remap(groupSet, bitSet));
        }
        return builder.build();
    }

    private static List<Integer> remap(ImmutableBitSet groupSet,
                                       List<Integer> argList) {
        ImmutableIntList list = ImmutableIntList.of();
        for (int arg : argList) {
            list = list.append(remap(groupSet, arg));
        }
        return list;
    }

    private static int remap(ImmutableBitSet groupSet, int arg) {
        return arg < 0 ? -1 : groupSet.indexOf(arg);
    }

    /**
     * Converts an aggregate relational expression that contains just one
     * distinct aggregate function (or perhaps several over the same arguments)
     * and no non-distinct aggregate functions.
     */
    private RelBuilder convertMonopole(RelBuilder relBuilder, LogicalAggregate aggregate,
                                       List<Integer> argList, int filterArg) {
        // For example,
        //    SELECT deptno, COUNT(DISTINCT sal), SUM(DISTINCT sal)
        //    FROM emp
        //    GROUP BY deptno
        //
        // becomes
        //
        //    SELECT deptno, COUNT(distinct_sal), SUM(distinct_sal)
        //    FROM (
        //      SELECT DISTINCT deptno, sal AS distinct_sal
        //      FROM EMP GROUP BY deptno)
        //    GROUP BY deptno

        // Project the columns of the GROUP BY plus the arguments
        // to the agg function.
        final Map<Integer, Integer> sourceOf = new HashMap<>();
        createSelectDistinct(relBuilder, aggregate, argList, filterArg, sourceOf);

        // Create an aggregate on top, with the new aggregate list.
        final List<AggregateCall> newAggCalls =
            com.google.common.collect.Lists.newArrayList(aggregate.getAggCallList());
        final Set<Integer> nullRefs = new HashSet<>();
        rewriteAggCalls(newAggCalls, argList, sourceOf, nullRefs);
        final int cardinality = aggregate.getGroupSet().cardinality();

        buildNullFilter(nullRefs, relBuilder);

        relBuilder.push(
            aggregate.copy(aggregate.getTraitSet(), relBuilder.build(),
                aggregate.indicator, ImmutableBitSet.range(cardinality), null,
                newAggCalls));
        return relBuilder;
    }

    /**
     * Converts all distinct aggregate calls to a given set of arguments.
     *
     * <p>This method is called several times, one for each set of arguments.
     * Each time it is called, it generates a JOIN to a new SELECT DISTINCT
     * relational expression, and modifies the set of top-level calls.
     *
     * @param aggregate Original aggregate
     * @param n Ordinal of this in a join. {@code relBuilder} contains the
     * input relational expression (either the original
     * aggregate, the output from the previous call to this
     * method. {@code n} is 0 if we're converting the
     * first distinct aggregate in a query with no non-distinct
     * aggregates)
     * @param argList Arguments to the distinct aggregate function
     * @param filterArg Argument that filters input to aggregate function, or -1
     * @param refs Array of expressions which will be the projected by the
     * result of this rule. Those relating to this arg list will
     * be modified  @return Relational expression
     */
    private void doRewrite(RelBuilder relBuilder, Aggregate aggregate, int n,
                           List<Integer> argList, int filterArg, List<RexInputRef> refs) {
        final RexBuilder rexBuilder = aggregate.getCluster().getRexBuilder();
        final List<RelDataTypeField> leftFields;
        if (n == 0) {
            leftFields = null;
        } else {
            leftFields = relBuilder.peek().getRowType().getFieldList();
        }

        // Aggregate(
        //     child,
        //     {COUNT(DISTINCT 1), SUM(DISTINCT 1), SUM(2)})
        //
        // becomes
        //
        // Aggregate(
        //     Join(
        //         child,
        //         Aggregate(child, < all columns > {}),
        //         INNER,
        //         <f2 = f5>))
        //
        // E.g.
        //   SELECT deptno, SUM(DISTINCT sal), COUNT(DISTINCT gender), MAX(age)
        //   FROM Emps
        //   GROUP BY deptno
        //
        // becomes
        //
        //   SELECT e.deptno, adsal.sum_sal, adgender.count_gender, e.max_age
        //   FROM (
        //     SELECT deptno, MAX(age) as max_age
        //     FROM Emps GROUP BY deptno) AS e
        //   JOIN (
        //     SELECT deptno, COUNT(gender) AS count_gender FROM (
        //       SELECT DISTINCT deptno, gender FROM Emps) AS dgender
        //     GROUP BY deptno) AS adgender
        //     ON e.deptno = adgender.deptno
        //   JOIN (
        //     SELECT deptno, SUM(sal) AS sum_sal FROM (
        //       SELECT DISTINCT deptno, sal FROM Emps) AS dsal
        //     GROUP BY deptno) AS adsal
        //   ON e.deptno = adsal.deptno
        //   GROUP BY e.deptno
        //
        // Note that if a query contains no non-distinct aggregates, then the
        // very first join/group by is omitted.  In the example above, if
        // MAX(age) is removed, then the sub-select of "e" is not needed, and
        // instead the two other group by's are joined to one another.

        // Project the columns of the GROUP BY plus the arguments
        // to the agg function.
        final Map<Integer, Integer> sourceOf = new HashMap<>();
        createSelectDistinct(relBuilder, aggregate, argList, filterArg, sourceOf);

        // Now compute the aggregate functions on top of the distinct dataset.
        // Each distinct agg becomes a non-distinct call to the corresponding
        // field from the right; for example,
        //   "COUNT(DISTINCT e.sal)"
        // becomes
        //   "COUNT(distinct_e.sal)".
        final List<AggregateCall> aggCallList = new ArrayList<>();
        final List<AggregateCall> aggCalls = aggregate.getAggCallList();

        final int groupAndIndicatorCount =
            aggregate.getGroupCount() + aggregate.getIndicatorCount();
        int i = groupAndIndicatorCount - 1;
        Set<Integer> nullRefs = new HashSet<>();
        for (AggregateCall aggCall : aggCalls) {
            ++i;

            // Ignore agg calls which are not distinct or have the wrong set
            // arguments. If we're rewriting aggs whose args are {sal}, we will
            // rewrite COUNT(DISTINCT sal) and SUM(DISTINCT sal) but ignore
            // COUNT(DISTINCT gender) or SUM(sal).
            if (!aggCall.isDistinct()) {
                continue;
            }
            if (!aggCall.getArgList().equals(argList)) {
                continue;
            }

            // Re-map arguments.
            final int argCount = aggCall.getArgList().size();
            final List<Integer> newArgs = new ArrayList<>(argCount);
            for (int j = 0; j < argCount; j++) {
                final Integer arg = aggCall.getArgList().get(j);
                newArgs.add(sourceOf.get(arg));
            }
            final int newFilterArg =
                aggCall.filterArg >= 0 ? sourceOf.get(aggCall.filterArg) : -1;
            SqlKind sqlKind = aggCall.getAggregation().getKind();
            AggregateCall newAggCall;
            if (sqlKind == SqlKind.GROUP_CONCAT) {
                GroupConcatAggregateCall groupConcatAggCall = (GroupConcatAggregateCall) aggCall;
                final ImmutableList.Builder<Integer> newOrderArgs = ImmutableList.builder();
                if (groupConcatAggCall.getOrderList() != null) {
                    for (int arg : groupConcatAggCall.getOrderList()) {
                        newOrderArgs.add(sourceOf.get(arg));
                    }
                }
                newAggCall = groupConcatAggCall.create(aggCall.getAggregation(), false,
                    aggCall.isApproximate(), newArgs, newFilterArg, aggCall.getType(), newOrderArgs.build());
            } else {
                newAggCall =
                    AggregateCall.create(aggCall.getAggregation(), false,
                        aggCall.isApproximate(), newArgs,
                        newFilterArg, aggCall.getType(), aggCall.getName());
            }
            assert refs.get(i) == null;
            if (n == 0) {
                refs.set(i,
                    new RexInputRef(groupAndIndicatorCount + aggCallList.size(),
                        newAggCall.getType()));
            } else {
                refs.set(i,
                    new RexInputRef(leftFields.size() + groupAndIndicatorCount
                        + aggCallList.size(), newAggCall.getType()));
            }
            aggCallList.add(newAggCall);
        }

        final Map<Integer, Integer> map = new HashMap<>();
        for (Integer key : aggregate.getGroupSet()) {
            map.put(key, map.size());
        }
        final ImmutableBitSet newGroupSet = aggregate.getGroupSet().permute(map);
        assert newGroupSet
            .equals(ImmutableBitSet.range(aggregate.getGroupSet().cardinality()));
        com.google.common.collect.ImmutableList<ImmutableBitSet> newGroupingSets = null;
        if (aggregate.indicator) {
            newGroupingSets =
                ImmutableBitSet.ORDERING.immutableSortedCopy(
                    ImmutableBitSet.permute(aggregate.getGroupSets(), map));
        }
        buildNullFilter(nullRefs, relBuilder);
        relBuilder.push(
            aggregate.copy(aggregate.getTraitSet(), relBuilder.build(),
                aggregate.indicator, newGroupSet, newGroupingSets, aggCallList));

        // If there's no left child yet, no need to create the join
        if (n == 0) {
            return;
        }

        // Create the join condition. It is of the form
        //  'left.f0 = right.f0 and left.f1 = right.f1 and ...'
        // where {f0, f1, ...} are the GROUP BY fields.
        final List<RelDataTypeField> distinctFields =
            relBuilder.peek().getRowType().getFieldList();
        final List<RexNode> conditions = com.google.common.collect.Lists.newArrayList();
        for (i = 0; i < groupAndIndicatorCount; ++i) {
            // null values form its own group
            // use "is not distinct from" so that the join condition
            // allows null values to match.
            conditions.add(
                rexBuilder.makeCall(SqlStdOperatorTable.IS_NOT_DISTINCT_FROM,
                    RexInputRef.of(i, leftFields),
                    new RexInputRef(leftFields.size() + i,
                        distinctFields.get(i).getType())));
        }

        // Join in the new 'select distinct' relation.
        relBuilder.join(JoinRelType.INNER, conditions);
    }

    private static void rewriteAggCalls(
        List<AggregateCall> newAggCalls,
        List<Integer> argList,
        Map<Integer, Integer> sourceOf,
        Set<Integer> nullRefs) {
        // Rewrite the agg calls. Each distinct agg becomes a non-distinct call
        // to the corresponding field from the right; for example,
        // "COUNT(DISTINCT e.sal)" becomes   "COUNT(distinct_e.sal)".
        for (int i = 0; i < newAggCalls.size(); i++) {
            final AggregateCall aggCall = newAggCalls.get(i);

            // Ignore agg calls which are not distinct or have the wrong set
            // arguments. If we're rewriting aggregates whose args are {sal}, we will
            // rewrite COUNT(DISTINCT sal) and SUM(DISTINCT sal) but ignore
            // COUNT(DISTINCT gender) or SUM(sal).
            if (!aggCall.isDistinct()) {
                continue;
            }
            if (!aggCall.getArgList().equals(argList)) {
                continue;
            }

            // Re-map arguments.
            final int argCount = aggCall.getArgList().size();
            final List<Integer> newArgs = new ArrayList<>(argCount);
            for (int j = 0; j < argCount; j++) {
                final Integer arg = aggCall.getArgList().get(j);
                newArgs.add(sourceOf.get(arg));
            }
            SqlKind sqlKind = aggCall.getAggregation().getKind();
            AggregateCall newAggCall;
            if (sqlKind == SqlKind.GROUP_CONCAT) {
                GroupConcatAggregateCall groupConcatAggCall = (GroupConcatAggregateCall) aggCall;
                final ImmutableList.Builder<Integer> newOrderArgs = ImmutableList.builder();
                if (groupConcatAggCall.getOrderList() != null) {
                    for (int arg : groupConcatAggCall.getOrderList()) {
                        newOrderArgs.add(sourceOf.get(arg));
                    }
                }
                newAggCall = groupConcatAggCall.create(aggCall.getAggregation(), false,
                    aggCall.isApproximate(), newArgs, -1, aggCall.getType(), newOrderArgs.build());
            } else {
                newAggCall =
                    AggregateCall.create(aggCall.getAggregation(), false,
                        aggCall.isApproximate(), newArgs, -1,
                        aggCall.getType(), aggCall.getName());
            }
            newAggCalls.set(i, newAggCall);
        }
    }

    /**
     * Given an {@link org.apache.calcite.rel.core.Aggregate}
     * and the ordinals of the arguments to a
     * particular call to an aggregate function, creates a 'select distinct'
     * relational expression which projects the group columns and those
     * arguments but nothing else.
     *
     * <p>For example, given
     *
     * <blockquote>
     * <pre>select f0, count(distinct f1), count(distinct f2)
     * from t group by f0</pre>
     * </blockquote>
     *
     * <p>and the argument list
     *
     * <blockquote>{2}</blockquote>
     *
     * <p>returns
     *
     * <blockquote>
     * <pre>select distinct f0, f2 from t</pre>
     * </blockquote>
     *
     * <p>The <code>sourceOf</code> map is populated with the source of each
     * column; in this case sourceOf.get(0) = 0, and sourceOf.get(1) = 2.
     *
     * @param relBuilder Relational expression builder
     * @param aggregate Aggregate relational expression
     * @param argList Ordinals of columns to make distinct
     * @param filterArg Ordinal of column to filter on, or -1
     * @param sourceOf Out parameter, is populated with a map of where each
     * output field came from
     * @return Aggregate relational expression which projects the required
     * columns
     */
    private RelBuilder createSelectDistinct(RelBuilder relBuilder,
                                            Aggregate aggregate, List<Integer> argList, int filterArg,
                                            Map<Integer, Integer> sourceOf) {
        relBuilder.push(aggregate.getInput());
        final List<Pair<RexNode, String>> projects = new ArrayList<>();
        final List<RelDataTypeField> childFields =
            relBuilder.peek().getRowType().getFieldList();
        for (int i : aggregate.getGroupSet()) {
            sourceOf.put(i, projects.size());
            projects.add(RexInputRef.of2(i, childFields));
        }
        if (filterArg >= 0) {
            sourceOf.put(filterArg, projects.size());
            projects.add(RexInputRef.of2(filterArg, childFields));
        }
        for (Integer arg : argList) {
            if (filterArg >= 0) {
                // Implement
                //   agg(DISTINCT arg) FILTER $f
                // by generating
                //   SELECT DISTINCT ... CASE WHEN $f THEN arg ELSE NULL END AS arg
                // and then applying
                //   agg(arg)
                // as usual.
                //
                // It works except for (rare) agg functions that need to see null
                // values.
                final RexBuilder rexBuilder = aggregate.getCluster().getRexBuilder();
                final RexInputRef filterRef = RexInputRef.of(filterArg, childFields);
                final Pair<RexNode, String> argRef = RexInputRef.of2(arg, childFields);
                RexNode condition =
                    rexBuilder.makeCall(SqlStdOperatorTable.CASE, filterRef,
                        argRef.left,
                        rexBuilder.ensureType(argRef.left.getType(),
                            rexBuilder.makeCast(argRef.left.getType(),
                                rexBuilder.constantNull()),
                            true));
                sourceOf.put(arg, projects.size());
                projects.add(Pair.of(condition, "i$" + argRef.right));
                continue;
            }
            if (sourceOf.get(arg) != null) {
                continue;
            }
            sourceOf.put(arg, projects.size());
            projects.add(RexInputRef.of2(arg, childFields));
        }
        relBuilder.project(Pair.left(projects), Pair.right(projects));

        // Get the distinct values of the GROUP BY fields and the arguments
        // to the agg functions.
        relBuilder.push(
            aggregate.copy(aggregate.getTraitSet(), relBuilder.build(), false,
                ImmutableBitSet.range(projects.size()),
                null, com.google.common.collect.ImmutableList.<AggregateCall>of()));
        return relBuilder;
    }

    private void buildNullFilter(Set<Integer> nullRefs, RelBuilder relBuilder) {
        if (nullRefs.size() > 0) {
            final List<RexNode> fields = new ArrayList<>(relBuilder.fields());
            List<RexNode> isNotNullOperands = new ArrayList<>();
            for (Integer nullRef : nullRefs) {
                if (fields.get(nullRef).getType().isNullable()) {
                    isNotNullOperands.add(relBuilder.isNotNull(fields.get(nullRef)));
                }
            }
            if (isNotNullOperands.size() > 0) {
                relBuilder.filter(isNotNullOperands);
            }
        }
    }
}
