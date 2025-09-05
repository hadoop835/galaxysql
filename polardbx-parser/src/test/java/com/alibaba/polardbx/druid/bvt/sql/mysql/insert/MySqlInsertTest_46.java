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
package com.alibaba.polardbx.druid.bvt.sql.mysql.insert;

import com.alibaba.polardbx.druid.sql.ast.SQLStatement;
import com.alibaba.polardbx.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.polardbx.druid.sql.ast.statement.SQLReplaceStatement;
import com.alibaba.polardbx.druid.sql.dialect.mysql.ast.statement.MySqlInsertStatement;
import com.alibaba.polardbx.druid.sql.dialect.mysql.parser.MySqlStatementParser;
import com.alibaba.polardbx.druid.sql.parser.SQLParserFeature;
import com.alibaba.polardbx.druid.util.FnvHash;
import junit.framework.TestCase;

import java.util.List;

public class MySqlInsertTest_46 extends TestCase {

    public void test_insert_0() throws Exception {
        String sql = "insert into do_pb_test (cid, 48_ddd, 24_fff ) values (1, 1.1 ,1.2);\n";

        MySqlStatementParser parser = new MySqlStatementParser(sql, false, true);
        parser.config(SQLParserFeature.KeepInsertValueClauseOriginalString, true);

        List<SQLStatement> statementList = parser.parseStatementList();
        SQLStatement stmt = statementList.get(0);

        MySqlInsertStatement insertStmt = (MySqlInsertStatement) stmt;
        assertEquals("INSERT INTO do_pb_test (cid, 48_ddd, 24_fff)\n" +
            "VALUES (1, 1.1, 1.2);", insertStmt.toString());

        assertEquals(FnvHash.hashCode64("24_fff"),
            ((SQLIdentifierExpr) insertStmt.getColumns().get(2)).nameHashCode64());

    }

    public void testInsertArgsInFunction() throws Exception {
        String sql = "insert into test (cid, 48_ddd, 24_fff ) values (1, null, now());";
        MySqlInsertStatement insertStmt = (MySqlInsertStatement)parseInsert(sql);
        assertTrue(!insertStmt.isHasArgsInFunction());

        sql = "insert into test (cid, 48_ddd, 24_fff ) values (1, null, now(3));";
        insertStmt = (MySqlInsertStatement)parseInsert(sql);
        assertTrue(insertStmt.isHasArgsInFunction());

        sql = "insert into test (cid, 48_ddd, fff ) values (1, (select 1), now());";
        insertStmt = (MySqlInsertStatement)parseInsert(sql);
        assertTrue(insertStmt.isHasArgsInFunction());

        sql = "   INSERT INTO table_name (column1, column2, column3)\n"
            + "   VALUES (1, null, now())\n"
            + "   ON DUPLICATE KEY UPDATE column1=VALUES(column1), column2=VALUES(column2);\n";
        insertStmt = (MySqlInsertStatement)parseInsert(sql);
        assertTrue(!insertStmt.isHasArgsInFunction());

        sql = "replace into test (cid, 48_ddd, fff ) values (1, (select 1), now());";
        SQLReplaceStatement replaceStmt = (SQLReplaceStatement) parseInsert(sql);
        assertTrue(replaceStmt.isHasArgsInFunction());
    }

    private SQLStatement parseInsert(String sql) {
        MySqlStatementParser parser = new MySqlStatementParser(sql, false, true);
        List<SQLStatement> statementList = parser.parseStatementList();
        return statementList.get(0);
    }

}
