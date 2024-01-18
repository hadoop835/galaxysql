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

import com.alibaba.polardbx.optimizer.core.DrdsConvention;
import com.google.common.base.Predicates;
import org.apache.calcite.plan.Convention;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.convert.ConverterRule;
import org.apache.calcite.rel.core.RecursiveCTEAnchor;
import org.apache.calcite.rel.core.RelFactories;
import org.apache.calcite.rel.logical.LogicalSort;

public class DrdsRecursiveCTEAnchorConvertRule extends ConverterRule {
    public static final DrdsRecursiveCTEAnchorConvertRule INSTANCE = new DrdsRecursiveCTEAnchorConvertRule();

    DrdsRecursiveCTEAnchorConvertRule() {
        super(RecursiveCTEAnchor.class, Convention.NONE, DrdsConvention.INSTANCE, "DrdsRecursiveCTEAnchorConvertRule");
    }

    @Override
    public Convention getOutConvention() {
        return DrdsConvention.INSTANCE;
    }

    @Override
    public RelNode convert(RelNode rel) {
        final RecursiveCTEAnchor recursiveCTEAnchor = (RecursiveCTEAnchor) rel;
        return new RecursiveCTEAnchor(recursiveCTEAnchor.getCluster(),
            recursiveCTEAnchor.getTraitSet().simplify().replace(DrdsConvention.INSTANCE),
            recursiveCTEAnchor.getCteName(), recursiveCTEAnchor.getRowType());
    }
}
