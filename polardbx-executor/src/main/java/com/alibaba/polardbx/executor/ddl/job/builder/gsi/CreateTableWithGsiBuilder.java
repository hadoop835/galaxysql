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

package com.alibaba.polardbx.executor.ddl.job.builder.gsi;

import com.alibaba.polardbx.executor.ddl.job.builder.CreateTableBuilder;
import com.alibaba.polardbx.executor.ddl.job.builder.DdlPhyPlanBuilder;
import com.alibaba.polardbx.optimizer.context.ExecutionContext;
import com.alibaba.polardbx.optimizer.core.rel.PhyDdlTableOperation;
import com.alibaba.polardbx.optimizer.core.rel.ddl.data.CreateTablePreparedData;
import com.alibaba.polardbx.optimizer.core.rel.ddl.data.gsi.CreateGlobalIndexPreparedData;
import com.alibaba.polardbx.optimizer.core.rel.ddl.data.gsi.CreateTableWithGsiPreparedData;
import org.apache.calcite.rel.core.DDL;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class CreateTableWithGsiBuilder {

    private final DDL relDdl;
    private final CreateTableWithGsiPreparedData preparedData;
    private final ExecutionContext executionContext;

    private DdlPhyPlanBuilder primaryTableBuilder;

    private TreeMap<String, List<List<String>>> primaryTableTopology;
    private List<PhyDdlTableOperation> primaryTablePhysicalPlans;

    private Map<String, Map<String, List<List<String>>>> indexTableTopologyMap = new LinkedHashMap<>();
    private Map<String, List<PhyDdlTableOperation>> indexTablePhysicalPlansMap = new LinkedHashMap<>();

    public CreateTableWithGsiBuilder(DDL ddl, CreateTableWithGsiPreparedData preparedData,
                                     ExecutionContext executionContext) {
        this.relDdl = ddl;
        this.preparedData = preparedData;
        this.executionContext = executionContext;
    }

    public void build() {
        buildPrimaryTablePhysicalPlans();
        buildIndexTablePhysicalPlans();
    }

    public TreeMap<String, List<List<String>>> getPrimaryTableTopology() {
        return primaryTableTopology;
    }

    public List<PhyDdlTableOperation> getPrimaryTablePhysicalPlans() {
        return primaryTablePhysicalPlans;
    }

    public Map<String, Map<String, List<List<String>>>> getIndexTableTopologyMap() {
        return indexTableTopologyMap;
    }

    public Map<String, List<PhyDdlTableOperation>> getIndexTablePhysicalPlansMap() {
        return indexTablePhysicalPlansMap;
    }

    private void buildPrimaryTablePhysicalPlans() {
        CreateTablePreparedData primaryTablePreparedData = preparedData.getPrimaryTablePreparedData();
        primaryTableBuilder = new CreateTableBuilder(relDdl, primaryTablePreparedData, executionContext).build();
        this.primaryTableTopology = primaryTableBuilder.getTableTopology();
        this.primaryTablePhysicalPlans = primaryTableBuilder.getPhysicalPlans();
    }

    private void buildIndexTablePhysicalPlans() {
        for (Map.Entry<String, CreateGlobalIndexPreparedData> entry :
            preparedData.getIndexTablePreparedDataMap().entrySet()) {
            buildIndexTablePhysicalPlans(entry.getKey(), entry.getValue());
        }
    }

    private void buildIndexTablePhysicalPlans(String indexTableName,
                                              CreateGlobalIndexPreparedData indexTablePreparedData) {
        indexTablePreparedData.setPrimaryTableRule(primaryTableBuilder.getTableRule());

        DdlPhyPlanBuilder indexTableBuilder =
            new CreateGlobalIndexBuilder(relDdl, indexTablePreparedData, executionContext).build();

        this.indexTablePhysicalPlansMap.put(indexTableName, indexTableBuilder.getPhysicalPlans());
    }

}
