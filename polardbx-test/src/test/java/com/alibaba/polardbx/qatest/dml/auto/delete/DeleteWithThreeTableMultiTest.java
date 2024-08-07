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

package com.alibaba.polardbx.qatest.dml.auto.delete;

import com.alibaba.polardbx.qatest.AutoCrudBasedLockTestCase;
import com.alibaba.polardbx.qatest.CrudBasedLockTestCase;
import com.alibaba.polardbx.qatest.data.ExecuteTableName;
import com.alibaba.polardbx.qatest.data.TableColumnGenerator;
import com.alibaba.polardbx.qatest.util.JdbcUtil;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

import static com.alibaba.polardbx.qatest.data.ExecuteTableName.BROADCAST_TB_SUFFIX;
import static com.alibaba.polardbx.qatest.data.ExecuteTableName.MULTI_DB_ONE_TB_SUFFIX;
import static com.alibaba.polardbx.qatest.data.ExecuteTableName.MUlTI_DB_MUTIL_TB_SUFFIX;
import static com.alibaba.polardbx.qatest.data.ExecuteTableName.ONE_DB_MUTIL_TB_SUFFIX;
import static com.alibaba.polardbx.qatest.data.ExecuteTableName.ONE_DB_ONE_TB_SUFFIX;
import static com.alibaba.polardbx.qatest.data.ExecuteTableName.THREE;
import static com.alibaba.polardbx.qatest.data.ExecuteTableName.TWO;
import static com.alibaba.polardbx.qatest.validator.DataOperator.executeOnMysqlAndTddl;
import static com.alibaba.polardbx.qatest.validator.DataValidator.selectContentSameAssert;
import static com.alibaba.polardbx.qatest.validator.DataValidator.selectContentSameAssertWithDiffSql;
import static com.alibaba.polardbx.qatest.validator.PrepareData.tableDataPrepare;

/**
 * 复杂Delete测试, 使用多线程Delete模式
 */

public class DeleteWithThreeTableMultiTest extends AutoCrudBasedLockTestCase {

    private static final String HINT =
        "/*+TDDL:CMD_EXTRA(UPDATE_DELETE_SELECT_BATCH_SIZE=1,MODIFY_SELECT_MULTI=TRUE)*/ ";

    @Parameterized.Parameters(name = "{index}:table0={0},table1={1},table2={2}")
    public static List<String[]> prepareData() {
        //不用全部类型表排列，组合就行
        String [][] allTests = new String[][] {
            {   ExecuteTableName.UPDATE_DELETE_BASE + ONE_DB_ONE_TB_SUFFIX,
                ExecuteTableName.UPDATE_DELETE_BASE + TWO + ONE_DB_MUTIL_TB_SUFFIX,
                ExecuteTableName.UPDATE_DELETE_BASE + THREE + MULTI_DB_ONE_TB_SUFFIX,
            },
            {   ExecuteTableName.UPDATE_DELETE_BASE + ONE_DB_MUTIL_TB_SUFFIX,
                ExecuteTableName.UPDATE_DELETE_BASE + TWO + MULTI_DB_ONE_TB_SUFFIX,
                ExecuteTableName.UPDATE_DELETE_BASE + THREE + MUlTI_DB_MUTIL_TB_SUFFIX,
            },
            {   ExecuteTableName.UPDATE_DELETE_BASE + MULTI_DB_ONE_TB_SUFFIX,
                ExecuteTableName.UPDATE_DELETE_BASE + TWO + MUlTI_DB_MUTIL_TB_SUFFIX,
                ExecuteTableName.UPDATE_DELETE_BASE + THREE + BROADCAST_TB_SUFFIX,
            },
            {   ExecuteTableName.UPDATE_DELETE_BASE + MUlTI_DB_MUTIL_TB_SUFFIX,
                ExecuteTableName.UPDATE_DELETE_BASE + TWO + BROADCAST_TB_SUFFIX,
                ExecuteTableName.UPDATE_DELETE_BASE + THREE + ONE_DB_ONE_TB_SUFFIX,
            },
            {   ExecuteTableName.UPDATE_DELETE_BASE + BROADCAST_TB_SUFFIX,
                ExecuteTableName.UPDATE_DELETE_BASE + TWO + ONE_DB_ONE_TB_SUFFIX,
                ExecuteTableName.UPDATE_DELETE_BASE + THREE + ONE_DB_MUTIL_TB_SUFFIX,
            },
        };
        return Arrays.asList(allTests);
    }

    public DeleteWithThreeTableMultiTest(String baseOneTableName, String baseTwoTableName, String baseThreeTableName) {
        this.baseOneTableName = baseOneTableName;
        this.baseTwoTableName = baseTwoTableName;
        this.baseThreeTableName = baseThreeTableName;
    }

    @Before
    public void prepare() throws Exception {
        tableDataPrepare(baseOneTableName, 20,
            TableColumnGenerator.getAllTypeColum(), PK_COLUMN_NAME, mysqlConnection,
            tddlConnection, columnDataGenerator);
        tableDataPrepare(baseTwoTableName, 20,
            TableColumnGenerator.getAllTypeColum(), PK_COLUMN_NAME, mysqlConnection,
            tddlConnection, columnDataGenerator);
        tableDataPrepare(baseThreeTableName, 20,
            TableColumnGenerator.getAllTypeColum(), PK_COLUMN_NAME, mysqlConnection,
            tddlConnection, columnDataGenerator);
    }

    /**
     * @since 5.0.1
     */
    @Test
    public void deleteWithSubQuery1() throws Exception {
        String sql = HINT + String.format(
            "delete a.*, b.* from %s a, %s b where a.pk = b.pk and a.integer_test not in (select a.integer_test from %s c where a.pk = c.pk + 5 )",
            baseOneTableName, baseTwoTableName, baseThreeTableName);

        executeOnMysqlAndTddl(mysqlConnection, tddlConnection, sql, null, true);

        sql = "select * from " + baseOneTableName;
        selectContentSameAssert(sql, null, mysqlConnection,
            tddlConnection);
        sql = "select * from " + baseTwoTableName;
        selectContentSameAssert(sql, null, mysqlConnection,
            tddlConnection);
    }

    /**
     * @since 5.0.1
     */
    @Test
    public void deleteIgnoreWithSubQuery1() throws Exception {
        String sql = HINT + String.format(
            "delete ignore a.*, b.* from %s a, %s b where a.pk = b.pk and a.integer_test not in (select a.integer_test from %s c where a.pk = c.pk + 5 )",
            baseOneTableName, baseTwoTableName, baseThreeTableName);

        executeOnMysqlAndTddl(mysqlConnection, tddlConnection, sql, null, true);

        sql = "select * from " + baseOneTableName;
        selectContentSameAssert(sql, null, mysqlConnection,
            tddlConnection);
        sql = "select * from " + baseTwoTableName;
        selectContentSameAssert(sql, null, mysqlConnection,
            tddlConnection);
    }

    /**
     * @since 5.0.1
     */
    @Test
    public void deleteWithThreeTableJoin() throws Exception {

        if (usingNewPartDb()) {
            /**
             * Ignore assert for broadcast in qatest of new part db
             */
            return;
        }

        String sql = HINT + String
            .format("delete b.*, c.* from %s a, %s b, %s c where a.pk = b.pk and b.pk = c.pk and c.pk = a.pk",
                baseOneTableName, baseTwoTableName, baseThreeTableName);
        executeOnMysqlAndTddl(mysqlConnection, tddlConnection, sql, null, true);

        sql = "select * from " + baseOneTableName;
        selectContentSameAssert(sql, null, mysqlConnection,
            tddlConnection, true);
        sql = "select * from " + baseTwoTableName;
        selectContentSameAssert(sql, null, mysqlConnection,
            tddlConnection, true);
        sql = "select * from " + baseThreeTableName;
        selectContentSameAssert(sql, null, mysqlConnection,
            tddlConnection, true);

        assertBrocastTableSame(baseOneTableName);
        assertBrocastTableSame(baseTwoTableName);
        assertBrocastTableSame(baseThreeTableName);
    }

    //DELETE t1, t2, t3 FROM t1, t2, t3;

    /**
     * @since 5.0.1
     */
    @Test
    public void deleteWithThreeTable() throws Exception {

        if (usingNewPartDb()) {
            /**
             * Ignore assert for broadcast in qatest of new part db
             */
            return;
        }

        String sql = HINT + String
            .format("delete %s, %s, %s from %s, %s, %s ", baseOneTableName, baseTwoTableName, baseThreeTableName,
                baseOneTableName, baseTwoTableName, baseThreeTableName);
        executeOnMysqlAndTddl(mysqlConnection, tddlConnection, sql, null, true);

        sql = "select * from " + baseOneTableName;
        selectContentSameAssert(sql, null, mysqlConnection,
            tddlConnection, true);
        sql = "select * from " + baseTwoTableName;
        selectContentSameAssert(sql, null, mysqlConnection,
            tddlConnection, true);
        sql = "select * from " + baseThreeTableName;
        selectContentSameAssert(sql, null, mysqlConnection,
            tddlConnection, true);

        assertBrocastTableSame(baseOneTableName);
        assertBrocastTableSame(baseTwoTableName);
        assertBrocastTableSame(baseThreeTableName);
    }

    //DELETE FROM t1.*, test.t2.*, a.* USING t1, t2, t3 AS a;

    /**
     * @since 5.0.1
     */
    @Test
    public void deleteWithUsing() throws Exception {

        if (usingNewPartDb()) {
            /**
             * Ignore assert for broadcast in qatest of new part db
             */
            return;
        }

        String sql = HINT + String
            .format("delete from %s.*, %s.*, a.* using %s, %s, %s as a  ", baseOneTableName, baseTwoTableName,
                baseOneTableName, baseTwoTableName, baseThreeTableName);
        executeOnMysqlAndTddl(mysqlConnection, tddlConnection, sql, null, true);

        sql = "select * from " + baseOneTableName;
        selectContentSameAssert(sql, null, mysqlConnection,
            tddlConnection, true);
        sql = "select * from " + baseTwoTableName;
        selectContentSameAssert(sql, null, mysqlConnection,
            tddlConnection, true);
        sql = "select * from " + baseThreeTableName;
        selectContentSameAssert(sql, null, mysqlConnection,
            tddlConnection, true);

        assertBrocastTableSame(baseOneTableName);
        assertBrocastTableSame(baseTwoTableName);
        assertBrocastTableSame(baseThreeTableName);
    }

    public void assertBrocastTableSame(String tableName) {

        if (usingNewPartDb()) {
            /**
             * Ignore assert for broadcast in qatest of new part db
             */
            return;
        }

        ResultSet resultSet =
            JdbcUtil.executeQuerySuccess(tddlConnection, "show topology from " + tableName);
        String physicalTableName = tableName;
        try {
            resultSet.next();
            physicalTableName = (String) JdbcUtil.getObject(resultSet, 3);
        } catch (SQLException throwables) {
            throwables.printStackTrace();
        }
        String mysqlSql = "select * from " + tableName;
        String tddlSql = "select * from " + physicalTableName;

        if (tableName.contains("broadcast")) {
            for (int i = 0; i < 4; i++) {
                String hint = String.format("/*TDDL:node=%s*/", i);
                selectContentSameAssertWithDiffSql(
                    hint + tddlSql,
                    hint + mysqlSql,
                    null,
                    mysqlConnection,
                    tddlConnection,
                    true,
                    false,
                    true
                );
            }
        }
    }

}
