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

package com.alibaba.polardbx.executor.operator.util;

import com.alibaba.polardbx.executor.chunk.Chunk;
import com.alibaba.polardbx.executor.utils.OrderByOption;
import com.alibaba.polardbx.optimizer.core.datatype.DataType;
import com.alibaba.polardbx.optimizer.memory.MemoryAllocatorCtx;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.List;

public abstract class Sorter {

    protected final int chunkLimit;
    protected final List<OrderByOption> orderBys;
    protected final List<DataType> columnMetas;
    protected final MemoryAllocatorCtx memoryAllocator;

    public Sorter(MemoryAllocatorCtx memoryAllocator, List<OrderByOption> orderBys,
                  List<DataType> columnMetas, int chunkLimit) {
        this.memoryAllocator = memoryAllocator;
        this.orderBys = orderBys;
        this.columnMetas = columnMetas;
        this.chunkLimit = chunkLimit;
    }

    public abstract void addChunk(Chunk chunk);

    public abstract void sort();

    public abstract Chunk nextChunk();

    public abstract void close();

    public abstract ListenableFuture<?> startMemoryRevoke();

    public abstract void finishMemoryRevoke();
}
