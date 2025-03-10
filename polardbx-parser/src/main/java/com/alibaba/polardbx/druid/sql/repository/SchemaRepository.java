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
package com.alibaba.polardbx.druid.sql.repository;

import com.alibaba.polardbx.druid.DbType;
import com.alibaba.polardbx.druid.FastsqlException;
import com.alibaba.polardbx.druid.sql.SQLUtils;
import com.alibaba.polardbx.druid.sql.ast.SQLDataType;
import com.alibaba.polardbx.druid.sql.ast.SQLExpr;
import com.alibaba.polardbx.druid.sql.ast.SQLName;
import com.alibaba.polardbx.druid.sql.ast.SQLStatement;
import com.alibaba.polardbx.druid.sql.ast.expr.SQLAllColumnExpr;
import com.alibaba.polardbx.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.polardbx.druid.sql.ast.expr.SQLIntegerExpr;
import com.alibaba.polardbx.druid.sql.ast.expr.SQLPropertyExpr;
import com.alibaba.polardbx.druid.sql.ast.statement.SQLAlterTableDropIndex;
import com.alibaba.polardbx.druid.sql.ast.statement.SQLAlterTableItem;
import com.alibaba.polardbx.druid.sql.ast.statement.SQLAlterTableRenameIndex;
import com.alibaba.polardbx.druid.sql.ast.statement.SQLAlterTableStatement;
import com.alibaba.polardbx.druid.sql.ast.statement.SQLAlterViewStatement;
import com.alibaba.polardbx.druid.sql.ast.statement.SQLAssignItem;
import com.alibaba.polardbx.druid.sql.ast.statement.SQLColumnConstraint;
import com.alibaba.polardbx.druid.sql.ast.statement.SQLColumnDefinition;
import com.alibaba.polardbx.druid.sql.ast.statement.SQLCreateDatabaseStatement;
import com.alibaba.polardbx.druid.sql.ast.statement.SQLCreateFunctionStatement;
import com.alibaba.polardbx.druid.sql.ast.statement.SQLCreateIndexStatement;
import com.alibaba.polardbx.druid.sql.ast.statement.SQLCreateSequenceStatement;
import com.alibaba.polardbx.druid.sql.ast.statement.SQLCreateTableStatement;
import com.alibaba.polardbx.druid.sql.ast.statement.SQLCreateViewStatement;
import com.alibaba.polardbx.druid.sql.ast.statement.SQLDropIndexStatement;
import com.alibaba.polardbx.druid.sql.ast.statement.SQLDropSequenceStatement;
import com.alibaba.polardbx.druid.sql.ast.statement.SQLDropTableStatement;
import com.alibaba.polardbx.druid.sql.ast.statement.SQLExprTableSource;
import com.alibaba.polardbx.druid.sql.ast.statement.SQLSelect;
import com.alibaba.polardbx.druid.sql.ast.statement.SQLSelectItem;
import com.alibaba.polardbx.druid.sql.ast.statement.SQLSelectQueryBlock;
import com.alibaba.polardbx.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.polardbx.druid.sql.ast.statement.SQLShowColumnsStatement;
import com.alibaba.polardbx.druid.sql.ast.statement.SQLShowCreateTableStatement;
import com.alibaba.polardbx.druid.sql.ast.statement.SQLShowTablesStatement;
import com.alibaba.polardbx.druid.sql.ast.statement.SQLTableSource;
import com.alibaba.polardbx.druid.sql.ast.statement.SQLUseStatement;
import com.alibaba.polardbx.druid.sql.dialect.mysql.ast.statement.MySqlAlterTableChangeColumn;
import com.alibaba.polardbx.druid.sql.dialect.mysql.ast.statement.MySqlAlterTableOption;
import com.alibaba.polardbx.druid.sql.dialect.mysql.ast.statement.MySqlAlterTableChangeColumn;
import com.alibaba.polardbx.druid.sql.dialect.mysql.ast.statement.MySqlAlterTableOption;
import com.alibaba.polardbx.druid.sql.dialect.mysql.ast.statement.MySqlCreateTableStatement;
import com.alibaba.polardbx.druid.sql.dialect.mysql.ast.statement.MySqlRenameTableStatement;
import com.alibaba.polardbx.druid.sql.dialect.mysql.visitor.MySqlASTVisitorAdapter;
import com.alibaba.polardbx.druid.sql.parser.ParserException;
import com.alibaba.polardbx.druid.sql.parser.SQLParserFeature;
import com.alibaba.polardbx.druid.sql.repository.function.Function;
import com.alibaba.polardbx.druid.sql.visitor.SQLASTVisitor;
import com.alibaba.polardbx.druid.sql.visitor.SQLASTVisitorAdapter;
import com.alibaba.polardbx.druid.support.logging.Log;
import com.alibaba.polardbx.druid.support.logging.LogFactory;
import com.alibaba.polardbx.druid.util.CharsetNameForParser;
import com.alibaba.polardbx.druid.util.CollationNameForParser;
import com.alibaba.polardbx.druid.util.FnvHash;
import org.apache.commons.lang.StringUtils;

import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Created by wenshao on 03/06/2017.
 */
public class SchemaRepository {
    private static final Log LOG = LogFactory.getLog(SchemaRepository.class);
    protected final Map<Long, Function> internalFunctions = new ConcurrentHashMap<Long, Function>(16, 0.75f, 1);
    protected DbType dbType;
    protected DbType schemaDbType;
    protected SQLASTVisitor consoleVisitor;
    protected Map<String, Schema> schemas = new LinkedHashMap<String, Schema>();
    protected SchemaLoader schemaLoader;
    protected SchemaObjectStoreProvider schemaObjectStoreProvider = new DefaultSchemaObjectStoreProvider();
    private Schema defaultSchema;

    private String defaultCharset;

    private boolean forceReplace;

    public SchemaRepository() {

    }

    public SchemaRepository(DbType dbType) {
        this(dbType, dbType);
    }

    public SchemaRepository(DbType dbType, DbType schemaDbType) {
        if (dbType == null) {
            dbType = DbType.mysql;
        }
        if (schemaDbType == null) {
            schemaDbType = dbType;
        }

        this.dbType = dbType;
        this.schemaDbType = schemaDbType;

            consoleVisitor = new MySqlConsoleSchemaVisitor();

    }

    private static String generateRandomString(int len) {
        String str = "abcdefghijklmnopqrstuvwxyz";
        Random random = new Random();
        StringBuilder buf = new StringBuilder();

        for (int i = 0; i < len; i++) {
            int num = random.nextInt(str.length());
            buf.append(str.charAt(num));
        }

        return buf.toString();
    }

    public DbType getDbType() {
        return dbType;
    }

    public String getDefaultSchemaName() {
        return getDefaultSchema().getName();
    }

    public Schema findSchema(String schema) {
        return findSchema(schema, false);
    }

    protected Schema findSchema(String name, boolean create) {
        if (name == null || name.length() == 0) {
            return getDefaultSchema();
        }

        name = SQLUtils.normalize(name);
        String normalizedName = name.toLowerCase();

        if (getDefaultSchema() != null && defaultSchema.getName() == null && create) {
            defaultSchema.setName(name);
            schemas.put(normalizedName, defaultSchema);
            return defaultSchema;
        }

        Schema schema = schemas.get(normalizedName);
        if (schema == null && create) {
            int p = name.indexOf('.');

            String catalog = null;
            if (p != -1) {
                catalog = name.substring(0, p);
            }
            schema = new Schema(this, catalog, name);
            schemas.put(normalizedName, schema);
        }
        return schema;
    }

    public Schema getDefaultSchema() {
        if (defaultSchema == null) {
            defaultSchema = new Schema(this);
        }

        return defaultSchema;
    }

    public void setDefaultSchema(String name) {
        setDefaultSchemaWithCharset(name, defaultCharset);
    }

    public void setDefaultSchemaWithCharset(String name, String charset) {
        if (name == null) {
            defaultSchema = null;
            return;
        }

        String normalizedName = SQLUtils.normalize(name)
            .toLowerCase();

        Schema defaultSchema = schemas.get(normalizedName);
        if (defaultSchema != null) {
            this.defaultSchema = defaultSchema;
            return;
        }

        if (this.defaultSchema != null
            && this.defaultSchema.getName() == null) {
            this.defaultSchema.setName(name);

            schemas.put(normalizedName, this.defaultSchema);
            return;
        }

        defaultSchema = new Schema(this);
        defaultSchema.setName(name);
        defaultSchema.setCharacterSet(charset);
        schemas.put(normalizedName, defaultSchema);
        this.defaultSchema = defaultSchema;
    }

    public void setDefaultSchema(Schema schema) {
        this.defaultSchema = schema;
    }

    public boolean isForceReplace() {
        return forceReplace;
    }

    public void setForceReplace(boolean forceReplace) {
        this.forceReplace = forceReplace;
    }

    public SchemaObject findTable(String tableName) {
        if (tableName.indexOf('.') != -1) {
            SQLExpr expr = SQLUtils.toSQLExpr(tableName, dbType);
            if (!(expr instanceof SQLIdentifierExpr)) {
                return findTable((SQLName) expr);
            }
        }

        SchemaObject object = getDefaultSchema()
            .findTable(tableName);

        if (object != null) {
            return object;
        }

        String ddl = loadDDL(tableName);
        if (ddl == null) {
            return null;
        }

        DbType schemaDbType = this.schemaDbType;
        if (schemaDbType == null) {
            schemaDbType = dbType;
        }

        SchemaObject schemaObject = acceptDDL(ddl, schemaDbType);
        if (schemaObject != null) {
            return schemaObject;
        }

        return getDefaultSchema()
            .findTable(tableName);
    }

    public SchemaObject findView(String viewName) {
        SchemaObject object = getDefaultSchema()
            .findView(viewName);

        if (object != null) {
            return object;
        }

        String ddl = loadDDL(viewName);
        if (ddl == null) {
            return null;
        }

        acceptDDL(ddl);

        return getDefaultSchema()
            .findView(viewName);
    }

    public SchemaObject findTable(long tableNameHash) {
        return getDefaultSchema()
            .findTable(tableNameHash);
    }

    public SchemaObject findTableOrView(String tableName) {
        return findTableOrView(tableName, true);
    }

    public SchemaObject findTableOrView(String tableName, boolean onlyCurrent) {
        Schema schema = getDefaultSchema();

        SchemaObject object = schema.findTableOrView(tableName);
        if (object != null) {
            return object;
        }

        for (Schema s : this.schemas.values()) {
            if (s == schema) {
                continue;
            }

            object = schema.findTableOrView(tableName);
            if (object != null) {
                return object;
            }
        }

        String ddl = loadDDL(tableName);
        if (ddl == null) {
            return null;
        }

        acceptDDL(ddl);

        // double check
        object = schema.findTableOrView(tableName);
        if (object != null) {
            return object;
        }

        for (Schema s : this.schemas.values()) {
            if (s == schema) {
                continue;
            }

            object = schema.findTableOrView(tableName);
            if (object != null) {
                return object;
            }
        }

        return null;
    }

    public Collection<Schema> getSchemas() {
        return schemas.values();
    }

    public SchemaObject findFunction(String functionName) {
        return getDefaultSchema().findFunction(functionName);
    }

    public void acceptDDL(String ddl) {
        acceptDDL(ddl, schemaDbType);
    }

    public SchemaObject acceptDDL(String ddl, DbType dbType) {
        List<SQLStatement> stmtList = SQLUtils.parseStatements(ddl, dbType);
        for (SQLStatement stmt : stmtList) {
            if (stmt instanceof SQLCreateTableStatement) {
                SchemaObject schemaObject = acceptCreateTable((SQLCreateTableStatement) stmt);
                if (stmtList.size() == 1) {
                    return schemaObject;
                }
            } else {
                accept(stmt);
            }
        }

        return null;
    }

    public void accept(SQLStatement stmt) {
        stmt.accept(consoleVisitor);
    }

    public boolean isSequence(String name) {
        return getDefaultSchema().isSequence(name);
    }

    public SchemaObject findTable(SQLTableSource tableSource, String alias) {
        return getDefaultSchema().findTable(tableSource, alias);
    }

    public SQLColumnDefinition findColumn(SQLTableSource tableSource, SQLSelectItem selectItem) {
        return getDefaultSchema().findColumn(tableSource, selectItem);
    }

    public SQLColumnDefinition findColumn(SQLTableSource tableSource, SQLExpr expr) {
        return getDefaultSchema().findColumn(tableSource, expr);
    }

    public SchemaObject findTable(SQLTableSource tableSource, SQLSelectItem selectItem) {
        return getDefaultSchema().findTable(tableSource, selectItem);
    }

    public SchemaObject findTable(SQLTableSource tableSource, SQLExpr expr) {
        return getDefaultSchema().findTable(tableSource, expr);
    }

    public Map<String, SchemaObject> getTables(SQLTableSource x) {
        return getDefaultSchema().getTables(x);
    }

    public int getTableCount() {
        return getDefaultSchema().getTableCount();
    }

    public Collection<SchemaObject> getObjects() {
        return getDefaultSchema().getObjects();
    }

    public int getViewCount() {
        return getDefaultSchema().getViewCount();
    }

    public void resolve(SQLSelectStatement stmt, SchemaResolveVisitor.Option... options) {
        if (stmt == null) {
            return;
        }

        SchemaResolveVisitor resolveVisitor = createResolveVisitor(options);
        resolveVisitor.visit(stmt);
    }

    public void resolve(SQLSelectQueryBlock queryBlock, SchemaResolveVisitor.Option... options) {
        if (queryBlock == null) {
            return;
        }

        SchemaResolveVisitor resolveVisitor = createResolveVisitor(options);
        resolveVisitor.visit(queryBlock);
    }

    public void resolve(SQLStatement stmt, SchemaResolveVisitor.Option... options) {
        if (stmt == null) {
            return;
        }

        SchemaResolveVisitor resolveVisitor = createResolveVisitor(options);
        if (stmt instanceof SQLSelectStatement) {
            resolveVisitor.visit((SQLSelectStatement) stmt);
        } else {
            stmt.accept(resolveVisitor);
        }
    }

    private SchemaResolveVisitor createResolveVisitor(SchemaResolveVisitor.Option... options) {
        int optionsValue = SchemaResolveVisitor.Option.of(options);

        SchemaResolveVisitor resolveVisitor;
        switch (dbType) {
        case mysql:

            resolveVisitor = new SchemaResolveVisitorFactory.MySqlResolveVisitor(this,  optionsValue);
            break;
        default:
            resolveVisitor = new SchemaResolveVisitorFactory.SQLResolveVisitor(this, optionsValue);
            break;
        }

        return resolveVisitor;
    }

    public String resolve(String input) {
        SchemaResolveVisitor visitor
            = createResolveVisitor(
            SchemaResolveVisitor.Option.ResolveAllColumn,
            SchemaResolveVisitor.Option.ResolveIdentifierAlias);

        List<SQLStatement> stmtList = SQLUtils.parseStatements(input, dbType);

        for (SQLStatement stmt : stmtList) {
            stmt.accept(visitor);
        }

        return SQLUtils.toSQLString(stmtList, dbType);
    }

    public String console(String input, SQLParserFeature... features) {
        try {
            StringBuffer buf = new StringBuffer();

            List<SQLStatement> stmtList = SQLUtils.parseStatements(input, dbType, features);

            for (SQLStatement stmt : stmtList) {
                if (stmt instanceof SQLShowColumnsStatement) {
                    SQLShowColumnsStatement showColumns = ((SQLShowColumnsStatement) stmt);
                    SQLName db = showColumns.getDatabase();
                    Schema schema;
                    if (db == null) {
                        schema = getDefaultSchema();
                    } else {
                        schema = findSchema(db.getSimpleName());
                    }

                    SQLName table = null;
                    SchemaObject schemaObject = null;
                    if (schema != null) {
                        table = showColumns.getTable();
                        schemaObject = schema.findTable(table.nameHashCode64());
                    }

                    if (schemaObject == null) {
                        buf.append("ERROR 1146 (42S02): Table '" + table + "' doesn't exist\n");
                    } else {
                        MySqlCreateTableStatement createTableStmt =
                            (MySqlCreateTableStatement) schemaObject.getStatement();
                        createTableStmt.showCoumns(buf);
                    }
                } else if (stmt instanceof SQLShowCreateTableStatement) {
                    SQLShowCreateTableStatement showCreateTableStmt = (SQLShowCreateTableStatement) stmt;
                    SQLName table = showCreateTableStmt.getName();
                    SchemaObject schemaObject = findTable(table);
                    if (schemaObject == null) {
                        buf.append("ERROR 1146 (42S02): Table '" + table + "' doesn't exist\n");
                    } else {
                        MySqlCreateTableStatement createTableStmt =
                            (MySqlCreateTableStatement) schemaObject.getStatement();
                        createTableStmt.output(buf);
                    }
                } else if (stmt instanceof MySqlRenameTableStatement) {
                    MySqlRenameTableStatement renameStmt = (MySqlRenameTableStatement) stmt;
                    for (MySqlRenameTableStatement.Item item : renameStmt.getItems()) {
                        renameTable(item.getName(), item.getTo());
                    }
                } else if (stmt instanceof SQLShowTablesStatement) {
                    SQLShowTablesStatement showTables = (SQLShowTablesStatement) stmt;
                    SQLName database = showTables.getDatabase();

                    Schema schema;
                    if (database == null) {
                        schema = getDefaultSchema();
                    } else {
                        schema = findSchema(database.getSimpleName());
                    }
                    if (schema != null) {
                        for (String table : schema.showTables()) {
                            buf.append(table);
                            buf.append('\n');
                        }
                    }
                } else {
                    stmt.accept(consoleVisitor);
                }
            }

            if (buf.length() == 0) {
                return "\n";
            }

            return buf.toString();
        } catch (IOException ex) {
            throw new FastsqlException("exeucte command error.", ex);
        }
    }

    public SchemaObject findTable(SQLName name) {
        if (name instanceof SQLIdentifierExpr) {
            return findTable(
                ((SQLIdentifierExpr) name).getName());
        }

        if (name instanceof SQLPropertyExpr) {
            SQLPropertyExpr propertyExpr = (SQLPropertyExpr) name;
            SQLExpr owner = propertyExpr.getOwner();
            String schema;
            String catalog = null;
            if (owner instanceof SQLIdentifierExpr) {
                schema = ((SQLIdentifierExpr) owner).getName();
            } else if (owner instanceof SQLPropertyExpr) {
                schema = ((SQLPropertyExpr) owner).getName();
                catalog = ((SQLPropertyExpr) owner).getOwnernName();
            } else {
                return null;
            }

            long tableHashCode64 = propertyExpr.nameHashCode64();

            Schema schemaObj = findSchema(schema, false);
            if (schemaObj != null) {
                SchemaObject table = schemaObj.findTable(tableHashCode64);
                if (table != null) {
                    return table;
                }
            }

            String ddl = loadDDL(catalog, schema, propertyExpr.getName());

            if (ddl == null) {
                schemaObj = findSchema(schema, true);
            } else {
                List<SQLStatement> stmtList = SQLUtils.parseStatements(ddl, schemaDbType);
                for (SQLStatement stmt : stmtList) {
                    accept(stmt);
                }

                if (stmtList.size() == 1) {
                    SQLStatement stmt = stmtList.get(0);
                    if (stmt instanceof SQLCreateTableStatement) {
                        SQLCreateTableStatement createStmt = (SQLCreateTableStatement) stmt;
                        String schemaName = createStmt.getSchema();
                        schemaObj = findSchema(schemaName, true);
                    }
                }
            }

            if (schemaObj == null) {
                return null;
            }

            return schemaObj.findTable(tableHashCode64);
        }

        return null;
    }

    private boolean renameTable(SQLName name, SQLName to) {
        Schema schema;
        if (name instanceof SQLPropertyExpr) {
            String schemaName = ((SQLPropertyExpr) name).getOwnernName();
            schema = findSchema(schemaName);
        } else {
            schema = getDefaultSchema();
        }

        if (schema == null) {
            return false;
        }

        long nameHashCode64 = FnvHash.hashCode64(SQLUtils.normalizeNoTrim(name.getSimpleName()));
        SchemaObject schemaObject = schema.findTable(nameHashCode64);
        if (schemaObject != null) {
            MySqlCreateTableStatement createTableStmt = (MySqlCreateTableStatement) schemaObject.getStatement();
            if (createTableStmt != null) {
                createTableStmt.setName(to.clone());
                acceptCreateTable(createTableStmt);
            }

            schema.getStore().removeObject(nameHashCode64);
        }
        return true;
    }

    public SchemaObject findTable(SQLExprTableSource x) {
        if (x == null) {
            return null;
        }

        SQLExpr expr = x.getExpr();
        if (expr instanceof SQLName) {
            return findTable((SQLName) expr);
        }

        return null;
    }

    public class MySqlConsoleSchemaVisitor extends MySqlASTVisitorAdapter {
        @Override
        public boolean visit(SQLDropSequenceStatement x) {
            acceptDropSequence(x);
            return false;
        }

        @Override
        public boolean visit(SQLCreateSequenceStatement x) {
            acceptCreateSequence(x);
            return false;
        }

        public boolean visit(MySqlCreateTableStatement x) {
            acceptCreateTable(x);
            return false;
        }

        @Override
        public boolean visit(SQLCreateTableStatement x) {
            acceptCreateTable(x);
            return false;
        }

        @Override
        public boolean visit(SQLDropTableStatement x) {
            acceptDropTable(x);
            return false;
        }

        @Override
        public boolean visit(SQLCreateViewStatement x) {
            acceptView(x);
            return false;
        }

        @Override
        public boolean visit(SQLAlterViewStatement x) {
            acceptView(x);
            return false;
        }

        @Override
        public boolean visit(SQLCreateIndexStatement x) {
            acceptCreateIndex(x);
            return false;
        }

        @Override
        public boolean visit(SQLCreateFunctionStatement x) {
            acceptCreateFunction(x);
            return false;
        }

        @Override
        public boolean visit(SQLAlterTableStatement x) {
            acceptAlterTable(x);
            return false;
        }

        @Override
        public boolean visit(SQLUseStatement x) {
            String schema = x.getDatabase().getSimpleName();
            setDefaultSchema(schema);
            return false;
        }

        @Override
        public boolean visit(SQLDropIndexStatement x) {
            acceptDropIndex(x);
            return false;
        }
    }

    public class DefaultConsoleSchemaVisitor extends SQLASTVisitorAdapter {
        @Override
        public boolean visit(SQLDropSequenceStatement x) {
            acceptDropSequence(x);
            return false;
        }

        @Override
        public boolean visit(SQLCreateSequenceStatement x) {
            acceptCreateSequence(x);
            return false;
        }

        @Override
        public boolean visit(SQLCreateTableStatement x) {
            acceptCreateTable(x);
            return false;
        }

        public boolean visit(SQLDropTableStatement x) {
            acceptDropTable(x);
            return false;
        }

        @Override
        public boolean visit(SQLCreateViewStatement x) {
            acceptView(x);
            return false;
        }

        @Override
        public boolean visit(SQLAlterViewStatement x) {
            acceptView(x);
            return false;
        }

        @Override
        public boolean visit(SQLCreateIndexStatement x) {
            acceptCreateIndex(x);
            return false;
        }

        @Override
        public boolean visit(SQLCreateFunctionStatement x) {
            acceptCreateFunction(x);
            return false;
        }

        @Override
        public boolean visit(SQLAlterTableStatement x) {
            acceptAlterTable(x);
            return false;
        }

        @Override
        public boolean visit(SQLDropIndexStatement x) {
            acceptDropIndex(x);
            return false;
        }
    }

    SchemaObject acceptCreateTable(MySqlCreateTableStatement x) {
        SQLExprTableSource like = x.getLike();
        if (like != null) {
            SchemaObject table = findTable((SQLName) like.getExpr());
            if (table != null) {
                MySqlCreateTableStatement stmt = (MySqlCreateTableStatement) table.getStatement();
                MySqlCreateTableStatement stmtCloned = stmt.clone();
                stmtCloned.setName(x.getName().clone());
                acceptCreateTable((SQLCreateTableStatement) stmtCloned);
                return table;
            }
        }

        return acceptCreateTable((SQLCreateTableStatement) x);
    }

    void acceptCreateDatabase(SQLCreateDatabaseStatement x) {
        String schemaName = SQLUtils.normalize(x.getDatabaseName());
        String normalizedName = schemaName
            .toLowerCase();
        Schema schema = new Schema(this, schemaName);
        String databaseCharset = SQLUtils.normalize(x.getCharacterSet());
        if (StringUtils.isBlank(databaseCharset)) {
            databaseCharset = defaultCharset;
        }
        schema.setCharacterSet(databaseCharset);
        schemas.put(normalizedName, schema);
    }

    SchemaObject acceptCreateTable(SQLCreateTableStatement x) {
        SQLCreateTableStatement x1 = x.clone();
        String schemaName = x1.getSchema();

        Schema schema = findSchema(schemaName, true);

        SQLSelect select = x1.getSelect();

        if (select != null) {
            select.accept(createResolveVisitor(SchemaResolveVisitor.Option.ResolveAllColumn));

            SQLSelectQueryBlock queryBlock = select.getFirstQueryBlock();
            this.resolve(queryBlock);

            if (queryBlock != null) {
                List<SQLSelectItem> selectList = queryBlock.getSelectList();
                for (SQLSelectItem selectItem : selectList) {
                    SQLExpr selectItemExpr = selectItem.getExpr();
                    if (selectItemExpr instanceof SQLAllColumnExpr
                        || (selectItemExpr instanceof SQLPropertyExpr && ((SQLPropertyExpr) selectItemExpr).getName()
                        .equals("*"))) {
                        continue;
                    }

                    SQLColumnDefinition column = null;
                    if (selectItemExpr instanceof SQLName) {
                        final SQLColumnDefinition resolvedColumn = ((SQLName) selectItemExpr).getResolvedColumn();
                        if (resolvedColumn != null) {
                            column = new SQLColumnDefinition();
                            column.setDataType(
                                selectItem.computeDataType());
                            if (DbType.mysql == dbType) {
                                if (resolvedColumn.getDefaultExpr() != null) {
                                    column.setDefaultExpr(resolvedColumn.getDefaultExpr().clone());
                                }
                                if (resolvedColumn.getConstraints().size() > 0) {
                                    for (SQLColumnConstraint constraint : resolvedColumn.getConstraints()) {
                                        column.addConstraint(constraint.clone());
                                    }
                                }
                                if (resolvedColumn.getComment() != null) {
                                    column.setComment(resolvedColumn.getComment());
                                }
                            }
                        }
                    }

                    if (column == null) {
                        column = new SQLColumnDefinition();
                        column.setDataType(
                            selectItem.computeDataType());
                    }

                    String name = selectItem.computeAlias();
                    column.setName(name);
                    column.setDbType(dbType);
                    x1.addColumn(column);
                }
                if (x1.getTableElementList().size() > 0) {
                    x1.setSelect(null);
                }
            }
        }

        SQLExprTableSource like = x1.getLike();
        if (like != null) {
            SchemaObject tableObject = null;

            SQLName name = like.getName();
            if (name != null) {
                tableObject = findTable(name);
            }

            SQLCreateTableStatement tableStmt = null;
            if (tableObject != null) {
                SQLStatement stmt = tableObject.getStatement();
                if (stmt instanceof SQLCreateTableStatement) {
                    tableStmt = (SQLCreateTableStatement) stmt;
                }
            }

            if (tableStmt != null) {
                SQLName tableName = x1.getName();
                tableStmt.cloneTo(x1);
                x1.setName(tableName);
                x1.setLike((SQLExprTableSource) null);
            }
        }

        x1.setSchema(null);

        String name = x1.computeName();
        SchemaObject table = schema.findTableOrView(name);
        if (table != null) {
            if (x1.isIfNotExists() && !forceReplace) {
                return table;
            }

            if (LOG.isDebugEnabled()) {
                LOG.debug("replaced table '" + name + "'");
            }
        }

        table = new SchemaObject(schema, name, SchemaObjectType.Table, x1);
        List<SQLAssignItem> assignItems = x1.getTableOptions();
        String defineCharset = null;
        String defineCollation = null;
        // if table not assign character, use schema character set
        for (SQLAssignItem item : assignItems) {
            if (findCharset(item.getTarget().toString())) {
                defineCharset = item.getValue().toString();
            } else if (findCollate(item.getTarget().toString())) {
                defineCollation = item.getValue().toString();
            }
        }
        if (defineCharset == null && defineCollation != null) {
            CharsetNameForParser charsetNameForParser = CollationNameForParser.getCharsetOf(defineCollation);
            if (charsetNameForParser == null) {
                throw new ParserException("can not find charset by define collate " + defineCollation);
            }
            defineCharset = charsetNameForParser.name();
            x1.addOption("CHARACTER SET", new SQLIdentifierExpr(defineCharset));
        }
        if (StringUtils.isEmpty(defineCharset) && StringUtils.isNotBlank(schema.getCharacterSet())) {
            x1.addOption("CHARACTER SET", new SQLIdentifierExpr(schema.getCharacterSet()));
        }
        schema.getStore().addObject(table.nameHashCode64(), table);
        return table;
    }

    private static final String[] CHARSET_CONSIST = new String[] {"CHARSET", "CHARACTER"};
    private static final String[] COLLATION_CONSIST = new String[] {"COLLATE"};

    private boolean findCharset(String target) {
        for (String cc : CHARSET_CONSIST) {
            if (StringUtils.containsIgnoreCase(target, cc)) {
                return true;
            }
        }
        return false;
    }

    private boolean findCollate(String target) {
        for (String cc : COLLATION_CONSIST) {
            if (StringUtils.containsIgnoreCase(target, cc)) {
                return true;
            }
        }
        return false;
    }

    boolean acceptDropTable(SQLDropTableStatement x) {
        for (SQLExprTableSource table : x.getTableSources()) {
            String schemaName = table.getSchema();
            Schema schema = findSchema(schemaName, false);
            if (schema == null) {
                continue;
            }
            long nameHashCode64 = FnvHash.hashCode64(SQLUtils.normalizeNoTrim(table.getTableName()));
            schema.getStore().removeObject(nameHashCode64);
        }
        return true;
    }

    boolean acceptView(SQLCreateViewStatement x) {
        String schemaName = x.getSchema();

        Schema schema = findSchema(schemaName, true);

        String name = x.computeName();
        SchemaObject view = schema.findTableOrView(name);
        if (view != null) {
            return false;
        }

        SchemaObject object = new SchemaObject(schema, name, SchemaObjectType.View, x.clone());
        schema.getStore().addObject(object.nameHashCode64(), object);
        return true;
    }

    boolean acceptView(SQLAlterViewStatement x) {
        String schemaName = x.getSchema();

        Schema schema = findSchema(schemaName, true);

        String name = x.computeName();
        SchemaObject view = schema.findTableOrView(name);
        if (view != null) {
            return false;
        }

        SchemaObject object = new SchemaObject(schema, name, SchemaObjectType.View, x.clone());
        schema.getStore().addObject(object.nameHashCode64(), object);
        return true;
    }

    boolean acceptDropIndex(SQLDropIndexStatement x) {
        if (x.getTableName() == null) {
            return false;
        }
        SQLName table = x.getTableName().getName();
        SchemaObject object = findTable(table);

        if (object != null) {
            SQLCreateTableStatement stmt = (SQLCreateTableStatement) object.getStatement();
            if (stmt != null) {
                stmt.apply(x);
                Schema schema = findSchema(stmt.getSchema(), false);
                // same index name on different table is allowed in mysql，so the object name should contact table name with index name
                String name = SQLUtils.normalizeNoTrim(table.getSimpleName()) + "." + SQLUtils
                    .normalize(x.getIndexName().getSimpleName());
                schema.getStore().removeIndex(FnvHash.hashCode64(name));
                return true;
            }
        }

        return false;
    }

    boolean acceptCreateIndex(SQLCreateIndexStatement x) {
        String schemaName = x.getSchema();
        String tableName = SQLUtils.normalizeNoTrim(x.getTableName());
        String indexName = SQLUtils.normalize(x.getName().getSimpleName());

        Schema schema = findSchema(schemaName, true);

        // same index name on different table is allowed in mysql，so the object name should contact table name with index name
        String name = tableName + "." + indexName;
        SchemaObject object = new SchemaObject(schema, name, SchemaObjectType.Index, x.clone());
        schema.getStore().addIndex(object.nameHashCode64(), object);

        return true;
    }

    boolean acceptCreateFunction(SQLCreateFunctionStatement x) {
        String schemaName = x.getSchema();
        Schema schema = findSchema(schemaName, true);

        String name = x.getName().getSimpleName();
        SchemaObject object = new SchemaObject(schema, name, SchemaObjectType.Function, x.clone());
        schema.getStore().addFunction(object.nameHashCode64(), object);

        return true;
    }

    boolean acceptAlterTable(SQLAlterTableStatement x) {
        String schemaName = x.getSchema();
        Schema schema = findSchema(schemaName, true);

        // we should do some special process, if the renaming or dropping index is created by sql syntax like 'create index ... on ...'
        for (SQLAlterTableItem item : x.getItems()) {
            String tableName = SQLUtils.normalizeNoTrim(x.getTableName());
            if (item instanceof SQLAlterTableDropIndex) {
                SQLAlterTableDropIndex dropIndex = (SQLAlterTableDropIndex) item;
                String indexName = SQLUtils.normalize(dropIndex.getIndexName().getSimpleName());
                schema.getStore().removeIndex(FnvHash.hashCode64(tableName + "." + indexName));
            } else if (item instanceof SQLAlterTableRenameIndex) {
                SQLAlterTableRenameIndex renameIndex = (SQLAlterTableRenameIndex) item;
                String from = SQLUtils.normalize(renameIndex.getName().getSimpleName());
                String to = SQLUtils.normalize(renameIndex.getTo().getSimpleName());
                SchemaObject obj = schema.getStore().getIndex(FnvHash.hashCode64(tableName + "." + from));
                if (obj != null) {
                    SQLCreateIndexStatement stmt = (SQLCreateIndexStatement) obj.getStatement();
                    stmt.setName(renameIndex.getTo());
                    schema.getStore().removeIndex(FnvHash.hashCode64(tableName + "." + from));
                    schema.getStore().addIndex(FnvHash.hashCode64(tableName + "." + to), obj);
                }
            }
        }

        // Remove algorithm table option from alter table statement, it will not apply anyway
        x.getItems().removeIf(item -> (item instanceof MySqlAlterTableOption && ((MySqlAlterTableOption) item).getName()
            .equalsIgnoreCase("algorithm")));

        // Special process for swap column name, e.g. alter table t1 change a b int, change b a int
        // Then new sql will be: alter table change a c int, b a int, c b int
        if (x.getItems().size() == 2 && x.getItems().stream()
            .allMatch(item -> item instanceof MySqlAlterTableChangeColumn)) {
            MySqlAlterTableChangeColumn change1 = ((MySqlAlterTableChangeColumn) (x.getItems().get(0)));
            MySqlAlterTableChangeColumn change2 = ((MySqlAlterTableChangeColumn) (x.getItems().get(1)));

            String stmt1Before = change1.getColumnName().getSimpleName();
            String stmt1After = change1.getNewColumnDefinition().getColumnName();
            String stmt2Before = change2.getColumnName().getSimpleName();
            String stmt2After = change2.getNewColumnDefinition().getColumnName();

            if (stmt1Before.equalsIgnoreCase(stmt2After) && stmt2Before.equalsIgnoreCase(stmt1After)) {
                // Get all column names so that our new column name will not be conflict with existing column
                Set<String> columnNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                long nameHashCode64 = FnvHash.hashCode64(SQLUtils.normalizeNoTrim(x.getTableName()));
                SchemaObject object = schema.findTable(nameHashCode64);
                if (object != null) {
                    SQLCreateTableStatement stmt = (SQLCreateTableStatement) object.getStatement();
                    for (SQLColumnDefinition colDef : stmt.getColumnDefinitions()) {
                        columnNames.add(colDef.getColumnName());
                    }
                }

                // Generate temp colName
                String tmpColName;
                do {
                    tmpColName = generateRandomString(4);
                } while (columnNames.contains(tmpColName));

                SQLColumnDefinition originColDef = change1.getNewColumnDefinition().clone();
                change1.getNewColumnDefinition().setName(tmpColName);
                MySqlAlterTableChangeColumn change3 = new MySqlAlterTableChangeColumn();
                change3.setColumnName(new SQLIdentifierExpr(tmpColName));
                change3.setNewColumnDefinition(originColDef);
                x.getItems().add(change3);
            }
        }

        long nameHashCode64 = FnvHash.hashCode64(SQLUtils.normalizeNoTrim(x.getTableName()));
        SchemaObject object = schema.findTable(nameHashCode64);
        if (object != null) {
            SQLCreateTableStatement stmt = (SQLCreateTableStatement) object.getStatement();
            if (stmt != null) {
                stmt.apply(x);
                schema.getStore().addObject(nameHashCode64, object);
                return true;
            }
        }

        return false;
    }

    public boolean acceptCreateSequence(SQLCreateSequenceStatement x) {
        String schemaName = x.getSchema();
        Schema schema = findSchema(schemaName, true);

        String name = x.getName().getSimpleName();
        SchemaObject object = new SchemaObject(schema, name, SchemaObjectType.Sequence, x);
        schema.getStore().addSequence(object.nameHashCode64(), object);
        return false;
    }

    public boolean acceptDropSequence(SQLDropSequenceStatement x) {
        String schemaName = x.getSchema();
        Schema schema = findSchema(schemaName, true);

        long nameHashCode64 = x.getName().nameHashCode64();
        schema.getStore().removeSequence(nameHashCode64);
        return false;
    }

    public SQLDataType findFuntionReturnType(long functionNameHashCode) {
        if (functionNameHashCode == FnvHash.Constants.LEN || functionNameHashCode == FnvHash.Constants.LENGTH) {
            return SQLIntegerExpr.DATA_TYPE;
        }

        return null;
    }

    protected String loadDDL(String table) {
        if (table == null) {
            return null;
        }

        table = SQLUtils.normalize(table, schemaDbType);

        if (schemaLoader != null) {
            return schemaLoader.loadDDL(null, null, table);
        }
        return null;
    }

    protected String loadDDL(String schema, String table) {
        if (table == null) {
            return null;
        }

        table = SQLUtils.normalize(table, dbType);
        if (schema != null) {
            schema = SQLUtils.normalize(schema, dbType);
        }

        if (schemaLoader != null) {
            return schemaLoader.loadDDL(null, schema, table);
        }
        return null;
    }

    protected String loadDDL(String catalog, String schema, String table) {
        if (table == null) {
            return null;
        }

        table = SQLUtils.normalize(table, dbType);
        if (schema != null) {
            schema = SQLUtils.normalize(schema, dbType);
        }
        if (catalog != null) {
            catalog = SQLUtils.normalize(catalog, dbType);
        }

        if (schemaLoader != null) {
            return schemaLoader.loadDDL(catalog, schema, table);
        }
        return null;
    }

    public SchemaLoader getSchemaLoader() {
        return schemaLoader;
    }

    public void setSchemaLoader(SchemaLoader schemaLoader) {
        this.schemaLoader = schemaLoader;
    }

    public SchemaObjectStoreProvider getSchemaObjectStoreProvider() {
        return schemaObjectStoreProvider;
    }

    public void setSchemaObjectStoreProvider(SchemaObjectStoreProvider schemaObjectStoreProvider) {
        this.schemaObjectStoreProvider = schemaObjectStoreProvider;
    }

    public String getDefaultCharset() {
        return this.defaultCharset;
    }

    public void setDefaultCharset(String defaultCharset) {
        this.defaultCharset = defaultCharset;
    }

    public interface SchemaLoader {
        String loadDDL(String catalog, String schema, String objectName);
    }

}
