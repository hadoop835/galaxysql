/*
 * Copyright 1999-2017 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.polardbx.druid.sql.dialect.mysql.ast.statement;

import com.alibaba.polardbx.druid.sql.ast.SQLName;
import com.alibaba.polardbx.druid.sql.ast.statement.SQLAlterTableItem;
import com.alibaba.polardbx.druid.sql.ast.statement.SQLColumnDefinition;
import com.alibaba.polardbx.druid.sql.dialect.mysql.ast.MySqlObjectImpl;
import com.alibaba.polardbx.druid.sql.dialect.mysql.visitor.MySqlASTVisitor;

public class MySqlAlterTableModifyColumn extends MySqlObjectImpl implements SQLAlterTableItem {

    private SQLColumnDefinition newColumnDefinition;

    private boolean first;

    private SQLName firstColumn;
    private SQLName afterColumn;

    public MySqlAlterTableModifyColumn() {
    }

    @Override
    public void accept0(MySqlASTVisitor visitor) {
        if (visitor.visit(this)) {
            acceptChild(visitor, newColumnDefinition);

            acceptChild(visitor, firstColumn);
            acceptChild(visitor, afterColumn);
        }
    }

    public SQLName getFirstColumn() {
        return firstColumn;
    }

    public void setFirstColumn(SQLName firstColumn) {
        this.firstColumn = firstColumn;
    }

    public SQLName getAfterColumn() {
        return afterColumn;
    }

    public void setAfterColumn(SQLName afterColumn) {
        this.afterColumn = afterColumn;
    }

    public SQLColumnDefinition getNewColumnDefinition() {
        return newColumnDefinition;
    }

    public void setNewColumnDefinition(SQLColumnDefinition newColumnDefinition) {
        this.newColumnDefinition = newColumnDefinition;
    }

    public boolean isFirst() {
        return first;
    }

    public void setFirst(boolean first) {
        this.first = first;
    }

}
