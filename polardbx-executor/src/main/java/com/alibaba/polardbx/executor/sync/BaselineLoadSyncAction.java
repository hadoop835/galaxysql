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

package com.alibaba.polardbx.executor.sync;

import com.alibaba.polardbx.executor.cursor.ResultCursor;
import com.alibaba.polardbx.gms.module.LogLevel;
import com.alibaba.polardbx.gms.module.Module;
import com.alibaba.polardbx.gms.module.ModuleLogInfo;
import com.alibaba.polardbx.optimizer.planmanager.PlanManager;

import static com.alibaba.polardbx.gms.module.LogPattern.PROCESS_END;
import static com.alibaba.polardbx.gms.scheduler.ScheduledJobExecutorType.BASELINE_SYNC;

public class BaselineLoadSyncAction implements ISyncAction {

    public BaselineLoadSyncAction() {
    }

    @Override
    public ResultCursor sync() {
        PlanManager.getInstance().forceLoadAll();
        ModuleLogInfo.getInstance()
            .logRecord(
                Module.SPM,
                PROCESS_END,
                new String[] {"BaselineLoadSyncAction", ""},
                LogLevel.NORMAL);
        return null;
    }
}