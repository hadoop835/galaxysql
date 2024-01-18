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

package com.alibaba.polardbx.executor.ddl.job.factory;

import com.alibaba.polardbx.common.ddl.foreignkey.ForeignKeyData;
import com.alibaba.polardbx.common.utils.Pair;
import com.alibaba.polardbx.executor.ddl.job.converter.PhysicalPlanData;
import com.alibaba.polardbx.executor.ddl.job.factory.util.FactoryUtils;
import com.alibaba.polardbx.executor.ddl.job.task.BaseValidateTask;
import com.alibaba.polardbx.executor.ddl.job.task.basic.AlterColumnDefaultTask;
import com.alibaba.polardbx.executor.ddl.job.task.basic.AlterForeignKeyTask;
import com.alibaba.polardbx.executor.ddl.job.task.basic.AlterTableChangeMetaTask;
import com.alibaba.polardbx.executor.ddl.job.task.basic.AlterTableHideMetaTask;
import com.alibaba.polardbx.executor.ddl.job.task.basic.AlterTableInsertColumnsMetaTask;
import com.alibaba.polardbx.executor.ddl.job.task.basic.AlterTablePhyDdlTask;
import com.alibaba.polardbx.executor.ddl.job.task.basic.AlterTableValidateTask;
import com.alibaba.polardbx.executor.ddl.job.task.basic.TableSyncTask;
import com.alibaba.polardbx.executor.ddl.job.task.basic.spec.AlterTableRollbacker;
import com.alibaba.polardbx.executor.ddl.job.task.cdc.CdcAlterTableRewrittenDdlMarkTask;
import com.alibaba.polardbx.executor.ddl.job.task.cdc.CdcDdlMarkTask;
import com.alibaba.polardbx.executor.ddl.job.task.factory.GsiTaskFactory;
import com.alibaba.polardbx.executor.ddl.job.task.shared.EmptyTask;
import com.alibaba.polardbx.executor.ddl.newengine.job.DdlExceptionAction;
import com.alibaba.polardbx.executor.ddl.newengine.job.DdlJobFactory;
import com.alibaba.polardbx.executor.ddl.newengine.job.DdlTask;
import com.alibaba.polardbx.executor.ddl.newengine.job.ExecutableDdlJob;
import com.alibaba.polardbx.executor.ddl.newengine.job.wrapper.ExecutableDdlJob4AlterTable;
import com.alibaba.polardbx.gms.tablegroup.TableGroupConfig;
import com.alibaba.polardbx.gms.topology.DbInfoManager;
import com.alibaba.polardbx.optimizer.OptimizerContext;
import com.alibaba.polardbx.optimizer.config.table.SchemaManager;
import com.alibaba.polardbx.optimizer.config.table.TableMeta;
import com.alibaba.polardbx.optimizer.context.ExecutionContext;
import com.alibaba.polardbx.optimizer.core.planner.rule.util.CBOUtil;
import com.alibaba.polardbx.optimizer.core.rel.ddl.LogicalAlterTable;
import com.alibaba.polardbx.optimizer.core.rel.ddl.data.AlterTablePreparedData;
import com.google.common.collect.Lists;
import org.apache.commons.collections.CollectionUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class AlterTableJobFactory extends DdlJobFactory {

    protected final PhysicalPlanData physicalPlanData;
    protected final String schemaName;
    protected final String logicalTableName;
    protected final AlterTablePreparedData prepareData;
    protected final LogicalAlterTable logicalAlterTable;

    /**
     * Whether altering a gsi table.
     */
    private boolean alterGsiTable = false;
    private String primaryTableName;

    /**
     * Whether altering a gsi table for repartition
     */
    private boolean repartition = false;

    /**
     * Whether generate validate table task
     */
    protected boolean validateExistence = true;

    protected ExecutionContext executionContext;

    public AlterTableJobFactory(PhysicalPlanData physicalPlanData,
                                AlterTablePreparedData preparedData,
                                LogicalAlterTable logicalAlterTable,
                                ExecutionContext executionContext) {
        this.schemaName = physicalPlanData.getSchemaName();
        this.logicalTableName = physicalPlanData.getLogicalTableName();
        this.physicalPlanData = physicalPlanData;
        this.prepareData = preparedData;
        this.logicalAlterTable = logicalAlterTable;
        this.executionContext = executionContext;
    }

    public void withAlterGsi(boolean alterGsi, String primaryTableName) {
        this.alterGsiTable = alterGsi;
        this.primaryTableName = primaryTableName;
    }

    public void withAlterGsi4Repartition(boolean alterGsi, boolean repartition, String primaryTableName) {
        withAlterGsi(alterGsi, primaryTableName);
        this.repartition = repartition;
    }

    @Override
    protected void validate() {
    }

    @Override
    protected ExecutableDdlJob doCreate() {
        boolean isNewPart = DbInfoManager.getInstance().isNewPartitionDb(schemaName);

        TableGroupConfig tableGroupConfig = isNewPart ? physicalPlanData.getTableGroupConfig() : null;
        DdlTask validateTask =
            this.validateExistence ?
                new AlterTableValidateTask(schemaName, logicalTableName,
                    logicalAlterTable.getSqlAlterTable().getSourceSql(), prepareData.getTableVersion(),
                    tableGroupConfig) :
                new EmptyTask(schemaName);

        final boolean isDropColumnOrDropIndex =
            CollectionUtils.isNotEmpty(prepareData.getDroppedColumns())
                || CollectionUtils.isNotEmpty(prepareData.getDroppedIndexes());

        List<DdlTask> alterGsiMetaTasks = new ArrayList<>();
        if (this.alterGsiTable) {
            // TODO(moyi) simplify these tasks, which could be executed batched
            if (CollectionUtils.isNotEmpty(prepareData.getDroppedColumns())) {
                alterGsiMetaTasks.addAll(GsiTaskFactory.alterGlobalIndexDropColumnTasks(
                    schemaName,
                    primaryTableName,
                    logicalTableName,
                    prepareData.getDroppedColumns()));
            }

            if (CollectionUtils.isNotEmpty(prepareData.getAddedColumns())) {
                alterGsiMetaTasks.addAll(GsiTaskFactory.alterGlobalIndexAddColumnsStatusTasks(
                    schemaName,
                    primaryTableName,
                    logicalTableName,
                    prepareData.getAddedColumns(),
                    prepareData.getBackfillColumns()));
            }
        }

        // End alter column default after gsi physical ddl is finished
        DdlTask beginAlterColumnDefault = null;
        DdlTask beginAlterColumnDefaultSyncTask = null;
        if (!this.alterGsiTable && CollectionUtils.isNotEmpty(prepareData.getAlterDefaultColumns())) {
            beginAlterColumnDefault =
                new AlterColumnDefaultTask(schemaName, logicalTableName, prepareData.getAlterDefaultColumns(), true);
            beginAlterColumnDefaultSyncTask = new TableSyncTask(schemaName, logicalTableName);
            beginAlterColumnDefault.setExceptionAction(DdlExceptionAction.TRY_RECOVERY_THEN_ROLLBACK);
            beginAlterColumnDefaultSyncTask.setExceptionAction(DdlExceptionAction.TRY_RECOVERY_THEN_ROLLBACK);
        }

        boolean isForeignKeysDdl =
            !prepareData.getAddedForeignKeys().isEmpty() || !prepareData.getDroppedForeignKeys().isEmpty();
        boolean isForeignKeyCdcMark = isForeignKeysDdl && !executionContext.getDdlContext().isFkRepartition();

        DdlTask phyDdlTask = new AlterTablePhyDdlTask(schemaName, logicalTableName, physicalPlanData);
        if (this.repartition) {
            ((AlterTablePhyDdlTask) phyDdlTask).setSourceSql(logicalAlterTable.getNativeSql());
        }

        physicalPlanData.setAlterTablePreparedData(prepareData);
        DdlTask cdcDdlMarkTask;
        if (this.prepareData.isOnlineModifyColumnIndexTask() || CBOUtil.isOss(schemaName,
            logicalTableName)) {
            cdcDdlMarkTask = null;
        } else if (this.logicalAlterTable.isRewrittenAlterSql()) {
            // Use rewritten sql to mark cdc instead of sql in ddl context
            cdcDdlMarkTask = new CdcAlterTableRewrittenDdlMarkTask(schemaName, physicalPlanData,
                logicalAlterTable.getBytesSql().toString(), isForeignKeyCdcMark);
        } else {
            if (ignoreMarkCdcDDL()) {
                cdcDdlMarkTask = null;
            } else {
                cdcDdlMarkTask = new CdcDdlMarkTask(schemaName, physicalPlanData, false, isForeignKeyCdcMark);
            }
        }

        DdlTask updateMetaTask = null;
        if (!this.repartition) {
            updateMetaTask = new AlterTableChangeMetaTask(
                schemaName,
                logicalTableName,
                physicalPlanData.getDefaultDbIndex(),
                physicalPlanData.getDefaultPhyTableName(),
                physicalPlanData.getKind(),
                physicalPlanData.isPartitioned(),
                prepareData.getDroppedColumns(),
                prepareData.getAddedColumns(),
                prepareData.getUpdatedColumns(),
                prepareData.getChangedColumns(),
                prepareData.isTimestampColumnDefault(),
                prepareData.getSpecialDefaultValues(),
                prepareData.getSpecialDefaultValueFlags(),
                prepareData.getDroppedIndexes(),
                prepareData.getAddedIndexes(),
                prepareData.getAddedIndexesWithoutNames(),
                prepareData.getRenamedIndexes(),
                prepareData.isPrimaryKeyDropped(),
                prepareData.getAddedPrimaryKeyColumns(),
                prepareData.getColumnAfterAnother(),
                prepareData.isLogicalColumnOrder(),
                prepareData.getTableComment(),
                prepareData.getTableRowFormat(),
                physicalPlanData.getSequence(),
                prepareData.isOnlineModifyColumnIndexTask());
        } else {
            // only add columns
            updateMetaTask = new AlterTableInsertColumnsMetaTask(
                schemaName,
                logicalTableName,
                physicalPlanData.getDefaultDbIndex(),
                physicalPlanData.getDefaultPhyTableName(),
                prepareData.getAddedColumns()
            );
        }

        DdlTask tableSyncTaskAfterShowing = new TableSyncTask(schemaName, logicalTableName);

        ExecutableDdlJob4AlterTable executableDdlJob = new ExecutableDdlJob4AlterTable();

        List<DdlTask> taskList = null;
        if (isDropColumnOrDropIndex) {
            DdlTask hideMetaTask =
                new AlterTableHideMetaTask(schemaName, logicalTableName,
                    prepareData.getDroppedColumns(),
                    prepareData.getDroppedIndexes());
            DdlTask tableSyncTaskAfterHiding = new TableSyncTask(schemaName, logicalTableName);
            taskList = Lists.newArrayList(
                validateTask,
                hideMetaTask,
                tableSyncTaskAfterHiding,
                phyDdlTask,
                cdcDdlMarkTask,
                updateMetaTask
            ).stream().filter(Objects::nonNull).collect(Collectors.toList());
        } else {
            // 1. physical DDL
            // 2. alter GSI meta if necessary
            // 3. update meta
            // 4. sync table
            String originDdl = executionContext.getDdlContext().getDdlStmt();
            if (AlterTableRollbacker.checkIfRollbackable(originDdl)) {
                phyDdlTask = phyDdlTask.onExceptionTryRecoveryThenRollback();
            }
            taskList = Lists.newArrayList(
                validateTask,
                beginAlterColumnDefault,
                beginAlterColumnDefaultSyncTask,
                phyDdlTask,
                cdcDdlMarkTask,
                updateMetaTask
            ).stream().filter(Objects::nonNull).collect(Collectors.toList());
        }

        taskList.addAll(alterGsiMetaTasks);

        if (isForeignKeysDdl) {
            DdlTask updateForeignKeysTask =
                new AlterForeignKeyTask(schemaName, logicalTableName, physicalPlanData.getDefaultDbIndex(),
                    physicalPlanData.getDefaultPhyTableName(), prepareData.getAddedForeignKeys(),
                    prepareData.getDroppedForeignKeys());
            taskList.add(updateForeignKeysTask);
        }

        // sync foreign key table meta
        syncFkTables(taskList);

        taskList.add(tableSyncTaskAfterShowing);

        executableDdlJob.addSequentialTasks(taskList);

        executableDdlJob.labelAsHead(validateTask);
        executableDdlJob.labelAsTail(tableSyncTaskAfterShowing);

        executableDdlJob.setTableValidateTask((BaseValidateTask) validateTask);
        executableDdlJob.setTableSyncTask((TableSyncTask) tableSyncTaskAfterShowing);

        return executableDdlJob;
    }

    @Override
    protected void excludeResources(Set<String> resources) {
        resources.add(concatWithDot(schemaName, logicalTableName));

        String tgName = FactoryUtils.getTableGroupNameByTableName(schemaName, logicalTableName);
        if (tgName != null) {
            resources.add(concatWithDot(schemaName, tgName));
        }
    }

    @Override
    protected void sharedResources(Set<String> resources) {
    }

    public void validateExistence(boolean validateExistence) {
        this.validateExistence = validateExistence;
    }

    private boolean ignoreMarkCdcDDL() {

        TableMeta tableMeta = null;
        SchemaManager schemaManager = executionContext.getSchemaManager(schemaName);
        if (schemaManager != null) {
            tableMeta = schemaManager.getTable(logicalTableName);
        }

        boolean isAutoPartition = tableMeta != null && tableMeta.isAutoPartition();
        boolean isGSI = tableMeta != null && tableMeta.isGsi();
        if (!isAutoPartition && !isGSI) {
            return false;
        }

        AlterTablePreparedData alterTablePreparedData = logicalAlterTable.getAlterTablePreparedData();
        boolean renameIndex = false;
        if (alterTablePreparedData != null) {
            List<Pair<String, String>> renameIndexesList = alterTablePreparedData.getRenamedIndexes();
            if (CollectionUtils.isNotEmpty(renameIndexesList)) {
                renameIndex = true;
            }
        }

        return this.alterGsiTable || renameIndex;
    }

    public void syncFkTables(List<DdlTask> taskList) {
        if (!prepareData.getAddedForeignKeys().isEmpty()) {
            ForeignKeyData data = prepareData.getAddedForeignKeys().get(0);
            taskList.add(new TableSyncTask(data.refSchema, data.refTableName));
        }
        if (!prepareData.getDroppedForeignKeys().isEmpty()) {
            TableMeta tableMeta =
                OptimizerContext.getContext(schemaName).getLatestSchemaManager().getTable(logicalTableName);
            for (ForeignKeyData data : tableMeta.getForeignKeys().values()) {
                if (data.constraint.equals(prepareData.getDroppedForeignKeys().get(0))) {
                    taskList.add(new TableSyncTask(data.refSchema, data.refTableName));
                }
            }
        }
        if (!prepareData.getChangedColumns().isEmpty()) {
            Map<String, String> columnNameMap = new HashMap<>();
            for (Pair<String, String> pair : prepareData.getChangedColumns()) {
                columnNameMap.put(pair.getValue(), pair.getKey());
            }
            TableMeta tableMeta =
                OptimizerContext.getContext(schemaName).getLatestSchemaManager().getTable(logicalTableName);
            Map<String, ForeignKeyData> referencedForeignKeys = tableMeta.getReferencedForeignKeys();
            for (Map.Entry<String, ForeignKeyData> e : referencedForeignKeys.entrySet()) {
                boolean sync = false;
                for (int i = 0; i < e.getValue().columns.size(); ++i) {
                    String oldColumn = e.getValue().refColumns.get(i);
                    if (columnNameMap.containsKey(oldColumn)) {
                        sync = true;
                        break;
                    }
                }
                if (sync) {
                    String referencedSchemaName = e.getValue().schema;
                    String referredTableName = e.getValue().tableName;
                    taskList.add(new TableSyncTask(referencedSchemaName, referredTableName));
                }
            }

            Map<String, ForeignKeyData> foreignKeys = tableMeta.getForeignKeys();
            if (!foreignKeys.isEmpty()) {
                for (Map.Entry<String, ForeignKeyData> e : foreignKeys.entrySet()) {
                    boolean sync = false;
                    for (int i = 0; i < e.getValue().columns.size(); ++i) {
                        String oldColumn = e.getValue().columns.get(i);
                        if (columnNameMap.containsKey(oldColumn)) {
                            sync = true;
                            break;
                        }
                    }
                    if (sync) {
                        taskList.add(new TableSyncTask(e.getValue().refSchema, e.getValue().refTableName));
                    }
                }
            }
        }
    }
}
