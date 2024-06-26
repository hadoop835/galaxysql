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

import com.alibaba.polardbx.druid.sql.ast.SQLExpr;
import com.alibaba.polardbx.druid.sql.ast.SQLName;
import com.alibaba.polardbx.druid.sql.ast.SqlType;
import com.alibaba.polardbx.druid.sql.ast.expr.SQLIntegerExpr;
import com.alibaba.polardbx.druid.sql.dialect.mysql.visitor.MySqlASTVisitor;

import java.util.ArrayList;
import java.util.List;

public class MySqlManageInstanceGroupStatement extends MySqlStatementImpl {

    private List<SQLExpr> groupNames = new ArrayList<SQLExpr>();
    private SQLIntegerExpr replication;
    private SQLName operation;

    @Override
    public void accept0(MySqlASTVisitor visitor) {
        if (visitor.visit(this)) {
            acceptChild(visitor, groupNames);
            acceptChild(visitor, replication);
            acceptChild(visitor, operation);
        }
        visitor.endVisit(this);
    }

    public List<String> getGroupNamesToString() {
        List<String> names = new ArrayList<String>(groupNames.size());
        for (SQLExpr groupName : groupNames) {
            names.add(groupName.toString());
        }
        return names;
    }

    public List<SQLExpr> getGroupNames() {
        return groupNames;
    }

    public SQLIntegerExpr getReplication() {
        return replication;
    }

    public void setReplication(SQLIntegerExpr replication) {
        this.replication = replication;
    }

    public SQLName getOperation() {
        return operation;
    }

    public void setOperation(SQLName operation) {
        this.operation = operation;
    }

    @Override
    public SqlType getSqlType() {
        return null;
    }
}
