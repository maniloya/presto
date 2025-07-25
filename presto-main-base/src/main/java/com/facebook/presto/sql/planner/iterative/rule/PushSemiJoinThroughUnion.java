/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.presto.sql.planner.iterative.rule;

import com.facebook.presto.Session;
import com.facebook.presto.matching.Capture;
import com.facebook.presto.matching.Captures;
import com.facebook.presto.matching.Pattern;
import com.facebook.presto.spi.WarningCollector;
import com.facebook.presto.spi.plan.PlanNode;
import com.facebook.presto.spi.plan.SemiJoinNode;
import com.facebook.presto.spi.plan.UnionNode;
import com.facebook.presto.spi.relation.VariableReferenceExpression;
import com.facebook.presto.sql.planner.iterative.Rule;
import com.facebook.presto.sql.planner.optimizations.SymbolMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ListMultimap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.facebook.presto.SystemSessionProperties.isPushSemiJoinThroughUnion;
import static com.facebook.presto.matching.Capture.newCapture;
import static com.facebook.presto.sql.planner.optimizations.SetOperationNodeUtils.fromListMultimap;
import static com.facebook.presto.sql.planner.plan.Patterns.semiJoin;
import static com.facebook.presto.sql.planner.plan.Patterns.sources;
import static com.facebook.presto.sql.planner.plan.Patterns.union;
import static com.google.common.collect.ImmutableMap.toImmutableMap;

public class PushSemiJoinThroughUnion
        implements Rule<SemiJoinNode>
{
    private static final Capture<UnionNode> CHILD = newCapture();

    private static final Pattern<SemiJoinNode> PATTERN = semiJoin()
            .with(sources()
                    .map(list -> list.isEmpty() ? null : list.get(0))
                    .matching(union().capturedAs(CHILD)));

    @Override
    public Pattern<SemiJoinNode> getPattern()
    {
        return PATTERN;
    }

    @Override
    public boolean isEnabled(Session session)
    {
        return isPushSemiJoinThroughUnion(session);
    }

    @Override
    public Result apply(SemiJoinNode semiJoinNode, Captures captures, Context context)
    {
        UnionNode unionNode = captures.get(CHILD);

        ImmutableList.Builder<PlanNode> rewrittenSources = ImmutableList.builder();
        List<Map<VariableReferenceExpression, VariableReferenceExpression>> sourceMappings = new ArrayList<>();
        for (int i = 0; i < unionNode.getSources().size(); i++) {
            rewrittenSources.add(rewriteSource(semiJoinNode, unionNode, i, sourceMappings, context));
        }

        ImmutableListMultimap.Builder<VariableReferenceExpression, VariableReferenceExpression> unionMappings = ImmutableListMultimap.builder();
        sourceMappings.forEach(mappings -> mappings.forEach(unionMappings::put));
        ListMultimap<VariableReferenceExpression, VariableReferenceExpression> mappings = unionMappings.build();

        return Result.ofPlanNode(new UnionNode(
                semiJoinNode.getSourceLocation(),
                context.getIdAllocator().getNextId(),
                rewrittenSources.build(),
                ImmutableList.copyOf(mappings.keySet()),
                fromListMultimap(mappings)));
    }

    private static SemiJoinNode rewriteSource(
            SemiJoinNode semiJoinNode,
            UnionNode unionNode,
            int sourceIdx,
            List<Map<VariableReferenceExpression, VariableReferenceExpression>> sourceMappings,
            Context context)
    {
        Map<VariableReferenceExpression, VariableReferenceExpression> inputMappings = getInputVariableMapping(unionNode, sourceIdx);
        ImmutableMap.Builder<VariableReferenceExpression, VariableReferenceExpression> mappings = ImmutableMap.builder();
        mappings.putAll(inputMappings);
        ImmutableMap.Builder<VariableReferenceExpression, VariableReferenceExpression> outputMappings = ImmutableMap.builder();
        for (VariableReferenceExpression outputVariable : semiJoinNode.getOutputVariables()) {
            if (inputMappings.containsKey(outputVariable)) {
                outputMappings.put(outputVariable, inputMappings.get(outputVariable));
            }
            else {
                VariableReferenceExpression newVariable = context.getVariableAllocator().newVariable(outputVariable);
                outputMappings.put(outputVariable, newVariable);
                mappings.put(outputVariable, newVariable);
            }
        }
        sourceMappings.add(outputMappings.build());
        SymbolMapper symbolMapper = new SymbolMapper(mappings.build(), WarningCollector.NOOP);
        return symbolMapper.map(semiJoinNode, unionNode.getSources().get(sourceIdx), context.getIdAllocator().getNextId());
    }

    private static Map<VariableReferenceExpression, VariableReferenceExpression> getInputVariableMapping(UnionNode unionNode, int sourceIdx)
    {
        return unionNode.getOutputVariables().stream().collect(toImmutableMap(key -> key, key -> unionNode.getVariableMapping().get(key).get(sourceIdx)));
    }
}
