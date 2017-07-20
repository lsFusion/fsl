package lsfusion.erp.utils;

import lsfusion.base.col.MapFact;
import lsfusion.base.col.interfaces.immutable.ImMap;
import lsfusion.base.col.interfaces.immutable.ImOrderMap;
import lsfusion.base.col.interfaces.immutable.ImRevMap;
import lsfusion.server.ServerLoggers;
import lsfusion.server.data.SQLHandledException;
import lsfusion.server.data.expr.KeyExpr;
import lsfusion.server.data.query.QueryBuilder;
import lsfusion.server.logics.property.ClassPropertyInterface;
import lsfusion.server.logics.property.ExecutionContext;
import lsfusion.server.logics.scripted.ScriptingActionProperty;
import lsfusion.server.logics.scripted.ScriptingErrorLog;
import lsfusion.server.logics.scripted.ScriptingLogicsModule;
import lsfusion.server.session.DataSession;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ChangeAllDatesActionProperty extends ScriptingActionProperty {

    public ChangeAllDatesActionProperty(ScriptingLogicsModule LM) throws ScriptingErrorLog.SemanticErrorException {
        super(LM);
    }

    public void executeCustom(ExecutionContext<ClassPropertyInterface> context) throws SQLException, SQLHandledException {
        changeAllDates(context);
    }

    private void changeAllDates(ExecutionContext context) throws SQLException, SQLHandledException {

        DataSession session = context.createSession();
        try {

            session.sql.pushNoReadOnly(session.sql.getConnection().sql);

            Integer seconds = (Integer) findProperty("secondsChangeAllDates").read(session);
            if (seconds != null) {

                Map<String, List<String>> tableColumnsMap = new HashMap<>();

                KeyExpr propertyExpr = new KeyExpr("property");

                ImRevMap<Object, KeyExpr> keys = MapFact.singletonRev((Object) "property", propertyExpr);
                QueryBuilder<Object, Object> query = new QueryBuilder<>(keys);
                query.addProperty("dbName", findProperty("dbName[Property]").getExpr(propertyExpr));
                query.addProperty("return", findProperty("return[Property]").getExpr(propertyExpr));
                query.addProperty("tableSID", findProperty("tableSID[Property]").getExpr(propertyExpr));
                query.and(findProperty("dbName[Property]").getExpr(propertyExpr).getWhere());
                query.and(findProperty("return[Property]").getExpr(propertyExpr).getWhere());
                query.and(findProperty("tableSID[Property]").getExpr(propertyExpr).getWhere());

                ImOrderMap<ImMap<Object, Object>, ImMap<Object, Object>> result = query.execute(session);
                for (ImMap<Object, Object> valueEntry : result.values()) {
                    String column = (String) valueEntry.get("dbName");
                    String table = (String) valueEntry.get("tableSID");
                    String returnProperty = (String) valueEntry.get("return");
                    if (!table.isEmpty() && !column.isEmpty() && (returnProperty.equals("DATE") || returnProperty.equals("TIME") || returnProperty.equals("DATETIME"))) {
                        List<String> columns = tableColumnsMap.get(table);
                        if(columns == null)
                            columns = new ArrayList<>();
                        columns.add(column);
                        tableColumnsMap.put(table, columns);
                    }
                }

                KeyExpr tableKeyExpr = new KeyExpr("tableKey");

                ImRevMap<Object, KeyExpr> tableKeyKeys = MapFact.singletonRev((Object) "tableKey", tableKeyExpr);
                QueryBuilder<Object, Object> tableKeyQuery = new QueryBuilder<>(tableKeyKeys);
                tableKeyQuery.addProperty("classSID", findProperty("classSID[TableKey]").getExpr(tableKeyExpr));
                tableKeyQuery.addProperty("name", findProperty("name[TableKey]").getExpr(tableKeyExpr));
                tableKeyQuery.addProperty("sidTable", findProperty("sidTable[TableKey]").getExpr(tableKeyExpr));
                tableKeyQuery.and(findProperty("classSID[TableKey]").getExpr(tableKeyExpr).getWhere());
                tableKeyQuery.and(findProperty("name[TableKey]").getExpr(tableKeyExpr).getWhere());

                ImOrderMap<ImMap<Object, Object>, ImMap<Object, Object>> tableKeyResult = tableKeyQuery.execute(session);
                for (ImMap<Object, Object> valueEntry : tableKeyResult.values()) {
                    String classSID = (String) valueEntry.get("classSID");
                    String key = (String) valueEntry.get("name");
                    String table = (String) valueEntry.get("sidTable");
                    if (!key.isEmpty() && !table.isEmpty() && (classSID.equals("TIME") || classSID.equals("DATETIME"))) {
                        List<String> columns = tableColumnsMap.get(table);
                        if(columns == null)
                            columns = new ArrayList<>();
                        columns.add(key);
                        tableColumnsMap.put(table, columns);
                    }
                }

                int count = 1;
                for (Map.Entry<String, List<String>> entry : tableColumnsMap.entrySet()) {
                    String table = entry.getKey();
                    StringBuilder columns = new StringBuilder();
                    StringBuilder logColumns = new StringBuilder();
                    for (String column : entry.getValue()) {
                        columns.append(String.format("%s%s = %s + %s*INTERVAL '1 second'", columns.length() == 0 ? "" : ", ", column, column, seconds));
                        logColumns.append(String.format("%s%s", (logColumns.length() == 0) ? "" : ", ", column));
                    }
                    ServerLoggers.systemLogger.info(String.format("Changing dates %s/%s: table %s, columns %s", count, tableColumnsMap.size(), table, logColumns.toString()));
                    session.sql.executeDDL(String.format("UPDATE %s SET %s", table, columns.toString()));
                    count++;
                }

                session.apply(context);
            }

        } catch (ScriptingErrorLog.SemanticErrorException e) {
            e.printStackTrace();
        } finally {
            session.sql.popNoReadOnly(session.sql.getConnection().sql);
        }
    }
}