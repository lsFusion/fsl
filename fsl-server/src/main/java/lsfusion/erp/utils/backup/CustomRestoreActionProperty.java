package lsfusion.erp.utils.backup;

import com.google.common.base.Throwables;
import lsfusion.base.col.MapFact;
import lsfusion.base.col.SetFact;
import lsfusion.base.col.interfaces.immutable.*;
import lsfusion.base.col.interfaces.mutable.MExclMap;
import lsfusion.base.col.interfaces.mutable.mapvalue.GetValue;
import lsfusion.interop.Compare;
import lsfusion.interop.action.MessageClientAction;
import lsfusion.server.classes.*;
import lsfusion.server.data.KeyField;
import lsfusion.server.data.OperationOwner;
import lsfusion.server.data.SQLHandledException;
import lsfusion.server.data.SQLSession;
import lsfusion.server.data.expr.KeyExpr;
import lsfusion.server.data.query.Join;
import lsfusion.server.data.query.QueryBuilder;
import lsfusion.server.data.type.Type;
import lsfusion.server.data.where.Where;
import lsfusion.server.logics.DataObject;
import lsfusion.server.logics.NullValue;
import lsfusion.server.logics.ObjectValue;
import lsfusion.server.logics.linear.LCP;
import lsfusion.server.logics.linear.LP;
import lsfusion.server.logics.property.CalcProperty;
import lsfusion.server.logics.property.ClassPropertyInterface;
import lsfusion.server.logics.property.ExecutionContext;
import lsfusion.server.logics.property.StoredDataProperty;
import lsfusion.server.logics.scripted.ScriptingActionProperty;
import lsfusion.server.logics.scripted.ScriptingErrorLog;
import lsfusion.server.logics.scripted.ScriptingLogicsModule;
import lsfusion.server.session.DataSession;
import lsfusion.server.session.PropertyChange;
import lsfusion.server.session.SessionTableUsage;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.*;

import static lsfusion.base.BaseUtils.trimToNull;

public class CustomRestoreActionProperty extends ScriptingActionProperty {
    private final ClassPropertyInterface backupInterface;

    public CustomRestoreActionProperty(ScriptingLogicsModule LM, ValueClass... classes) throws ScriptingErrorLog.SemanticErrorException {
        super(LM, classes);

        Iterator<ClassPropertyInterface> i = interfaces.iterator();
        backupInterface = i.next();
    }

    public void executeCustom(ExecutionContext<ClassPropertyInterface> context) throws SQLException, SQLHandledException {
        DataObject backupObject = context.getDataKeyValue(backupInterface);
        String dbName = null;
        try {
            String fileBackup = (String) findProperty("file[Backup]").read(context, backupObject);
            Map<String, CustomRestoreTable> tables = getTables(context);
            if (new File(fileBackup).exists() && !tables.isEmpty()) {
                dbName = context.getDbManager().customRestoreDB(fileBackup, tables.keySet());
                importColumns(context, dbName, tables);
            } else {
                context.requestUserInteraction(new MessageClientAction("Backup File not found or no selected tables", "Error"));
            }
        } catch (Exception e) {
            throw Throwables.propagate(e);
        } finally {
            try {
                if (dbName != null)
                    context.getDbManager().dropDB(dbName);
            } catch (IOException ignored) {
            }
        }
    }

    private Map<String, CustomRestoreTable> getTables(ExecutionContext context) throws ScriptingErrorLog.SemanticErrorException, SQLException, SQLHandledException {
        KeyExpr tableExpr = new KeyExpr("Table");
        ImRevMap<Object, KeyExpr> tableKeys = MapFact.<Object, KeyExpr>singletonRev("Table", tableExpr);
        QueryBuilder<Object, Object> tableQuery = new QueryBuilder<>(tableKeys);
        tableQuery.addProperty("sidTable", findProperty("sid[Table]").getExpr(context.getModifier(), tableExpr));
        tableQuery.addProperty("restoreObjectsTable", findProperty("restoreObjects[Table]").getExpr(context.getModifier(), tableExpr));
        tableQuery.and(findProperty("sid[Table]").getExpr(context.getModifier(), tableExpr).getWhere());
        tableQuery.and(findProperty("inCustomRestore[Table]").getExpr(context.getModifier(), tableExpr).getWhere());
        ImOrderMap<ImMap<Object, DataObject>, ImMap<Object, ObjectValue>> tableResult = tableQuery.executeClasses(context.getSession());
        Map<String, CustomRestoreTable> tables = new HashMap<>();
        Map<String, List<Object>> tableMap = new HashMap<>();
        for (int i = 0; i < tableResult.size(); i++) {
            DataObject tableObject = tableResult.getKey(i).get("Table");
            String sidTable = trimToNull((String) tableResult.getValue(i).get("sidTable").getValue());
            boolean restoreObjects = tableResult.getValue(i).get("restoreObjectsTable").getValue() != null;
            tableMap.put(sidTable, Arrays.asList(tableObject, restoreObjects));
        }

        for (Map.Entry<String, List<Object>> tableEntry : tableMap.entrySet()) {
            String sidTable = tableEntry.getKey();
            DataObject tableObject = (DataObject) tableEntry.getValue().get(0);
            boolean restoreObjects = (boolean) tableEntry.getValue().get(1);

            //columns
            KeyExpr tableColumnExpr = new KeyExpr("TableColumn");
            ImRevMap<Object, KeyExpr> tableColumnKeys = MapFact.<Object, KeyExpr>singletonRev("TableColumn", tableColumnExpr);
            QueryBuilder<Object, Object> tableColumnQuery = new QueryBuilder<>(tableColumnKeys);

            String[] exportNames = new String[]{"sidTableColumn", "canonicalNameTableColumn", "replaceOnlyNullTableColumn"};
            LCP[] exportProperties = findProperties("sid[TableColumn]", "canonicalName[TableColumn]", "replaceOnlyNull[TableColumn]");
            for (int j = 0; j < exportProperties.length; j++) {
                tableColumnQuery.addProperty(exportNames[j], exportProperties[j].getExpr(context.getModifier(), tableColumnExpr));
            }
            tableColumnQuery.and(findProperty("property[TableColumn]").getExpr(context.getModifier(), tableColumnExpr).getWhere());
            tableColumnQuery.and(findProperty("inCustomRestore[TableColumn]").getExpr(context.getModifier(), tableColumnExpr).getWhere());
            tableColumnQuery.and(findProperty("canonicalName[TableColumn]").getExpr(context.getModifier(), tableColumnExpr).getWhere());
            tableColumnQuery.and(findProperty("table[TableColumn]").getExpr(context.getModifier(), tableColumnExpr).compare(tableObject.getExpr(), Compare.EQUALS));
            ImOrderMap<ImMap<Object, Object>, ImMap<Object, Object>> tableColumnResult = tableColumnQuery.execute(context.getSession());
            for (ImMap<Object, Object> columnEntry : tableColumnResult.values()) {
                String sidTableColumn = trimToNull((String) columnEntry.get("sidTableColumn"));
                String canonicalNameTableColumn = trimToNull((String) columnEntry.get("canonicalNameTableColumn"));
                LP lpProperty = context.getBL().findProperty(canonicalNameTableColumn);
                if(lpProperty != null && lpProperty.property instanceof StoredDataProperty) {
                    CustomRestoreTable table = tables.get(sidTable);
                    if (table == null)
                        table = new CustomRestoreTable(restoreObjects);
                    if (columnEntry.get("replaceOnlyNullTableColumn") != null)
                        table.replaceOnlyNullSet.add(canonicalNameTableColumn);
                    table.sqlProperties.add(sidTableColumn);
                    table.lpProperties.add(lpProperty);
                    tables.put(sidTable, table);
                }
            }

            //keys
            KeyExpr tableKeyExpr = new KeyExpr("TableKey");
            ImRevMap<Object, KeyExpr> tableKeyKeys = MapFact.<Object, KeyExpr>singletonRev("TableKey", tableKeyExpr);
            QueryBuilder<Object, Object> tableKeyQuery = new QueryBuilder<>(tableKeyKeys);
            tableKeyQuery.addProperty("nameTableKey", findProperty("name[TableKey]").getExpr(context.getModifier(), tableKeyExpr));
            tableKeyQuery.addProperty("classSIDTableKey", findProperty("classSID[TableKey]").getExpr(context.getModifier(), tableKeyExpr));
            tableKeyQuery.and(findProperty("name[TableKey]").getExpr(context.getModifier(), tableKeyExpr).getWhere());
            tableKeyQuery.and(findProperty("table[TableKey]").getExpr(context.getModifier(), tableKeyExpr).compare(tableObject.getExpr(), Compare.EQUALS));
            ImOrderMap<ImMap<Object, Object>, ImMap<Object, Object>> tableKeyResult = tableKeyQuery.execute(context.getSession(), MapFact.singletonOrder((Object) "nameTableKey", false));
            for (ImMap<Object, Object> keyEntry : tableKeyResult.values()) {
                String nameTableKey = trimToNull((String) keyEntry.get("nameTableKey"));
                String classTableKey = trimToNull((String) keyEntry.get("classSIDTableKey"));
                CustomRestoreTable table = tables.get(sidTable);
                if (table == null)
                    table = new CustomRestoreTable(restoreObjects);
                table.keys.add(nameTableKey);
                table.classKeys.add(classTableKey);
                tables.put(sidTable, table);
            }
        }
        return tables;
    }

    private void importColumns(final ExecutionContext context, String dbName, Map<String, CustomRestoreTable> tables) {
        try {
            for (final Map.Entry<String, CustomRestoreTable> tableEntry : tables.entrySet()) {
                String tableName = tableEntry.getKey();
                CustomRestoreTable table = tableEntry.getValue();

                List<List<List<Object>>> data = context.getDbManager().readCustomRestoredColumns(dbName, tableName, table.keys, table.sqlProperties);
                List<List<Object>> keys = data.get(0);
                List<List<Object>> columns = data.get(1);

                if(!keys.isEmpty() || !columns.isEmpty()) {
                    //step1: props, mRows
                    final ImOrderSet<LP> props = SetFact.fromJavaOrderSet(table.lpProperties);
                    MExclMap<ImMap<KeyField, DataObject>, ImMap<LP, ObjectValue>> mRows = MapFact.mExclMap();

                    for (int i = 0; i < columns.size(); i++) {
                        List<Object> keysEntry = keys.get(i);
                        final List<Object> columnsEntry = columns.get(i);

                        //step2: exclAdd
                        ImMap<KeyField, DataObject> keysMap = MapFact.EMPTY();
                        for (int k = 0; k < keysEntry.size(); k++) {
                            ValueClass valueClass = getKeyClass(context, table.classKeys.get(k));
                            DataObject keyObject = context.getSession().getDataObject(valueClass, keysEntry.get(k));
                            if (keyObject.objectClass instanceof UnknownClass && valueClass instanceof ConcreteCustomClass && table.restoreObjects) {
                                keyObject = context.getSession().addObject((ConcreteCustomClass) valueClass, keyObject);
                                keyObject.object = keysEntry.get(k);
                            }
                            keysMap = keysMap.addExcl(new KeyField("key" + k, valueClass instanceof CustomClass ? LongClass.instance : (Type) valueClass), keyObject);
                        }

                        mRows.exclAdd(keysMap, props.getSet().mapValues(new GetValue<ObjectValue, LP>() {
                            public ObjectValue getMapValue(LP prop) {
                                try {
                                    Object object = columnsEntry.get(props.indexOf(prop));
                                    if (object == null) return NullValue.instance;
                                    ValueClass classValue = ((StoredDataProperty) prop.property).value;
                                    if (classValue instanceof CustomClass) {
                                        //TODO: убрать new Long, когда все базы перейдут на LONG
                                        return context.getSession().getDataObject(((StoredDataProperty) prop.property).value, new Long((Integer) object));
                                    } else if (classValue instanceof LogicalClass) {
                                        return getBooleanObject(object);
                                    } else if (object instanceof String)
                                        return new DataObject(((String) object).trim());
                                    else if (object instanceof Integer)
                                        return new DataObject((Integer) object);
                                    else if (object instanceof Long)
                                        return new DataObject((Long) object);
                                    else if (object instanceof BigDecimal)
                                        return new DataObject((BigDecimal) object, (NumericClass) classValue);
                                    else if (object instanceof Date)
                                        return new DataObject(object, DateClass.instance);
                                    else if (object instanceof Time)
                                        return new DataObject(object, TimeClass.instance);
                                    else if (object instanceof Timestamp)
                                        return new DataObject(object, DateTimeClass.instance);
                                    else
                                        return new DataObject(String.valueOf(object));
                                } catch (SQLException | SQLHandledException e) {
                                    return null;
                                }
                            }
                        }));
                    }

                    //step3: writeRows
                    String result = writeRows(context, props, mRows, keys.get(0), table.replaceOnlyNullSet);
                    if (result != null)
                        context.requestUserInteraction(new MessageClientAction(result, "Error restoring table " + tableName));
                }
            }
        } catch (Exception e) {
            throw Throwables.propagate(e);
        }
    }

    private String writeRows(ExecutionContext context, ImOrderSet<LP> props, MExclMap<ImMap<KeyField, DataObject>, ImMap<LP, ObjectValue>> mRows,
                             List<Object> keys, Set<String> replaceOnlyNullSet)
            throws SQLException, SQLHandledException, ScriptingErrorLog.SemanticErrorException {

        ImOrderSet<KeyField> keySet = SetFact.EMPTYORDER();
        for(int i = 0; i < keys.size(); i++) {
            keySet = keySet.addOrderExcl(new KeyField("key" + i, getKeyType(keys.get(i))));
        }
        SessionTableUsage<KeyField, LP> importTable = new SessionTableUsage("custrest", keySet/*SetFact.singletonOrder("key")*/, props, new Type.Getter<KeyField>() {
            public Type getType(KeyField key) {
                return key.type;
            }
        }, new Type.Getter<LP>() {
            @Override
            public Type getType(LP key) {
                return key.property.getType();
            }
        });
        DataSession session = context.getSession();
        OperationOwner owner = session.getOwner();
        SQLSession sql = session.sql;
        importTable.writeRows(sql, mRows.immutable(), owner);

        ImRevMap<KeyField, KeyExpr> mapKeys = importTable.getMapKeys();
        Join<LP> importJoin = importTable.join(mapKeys);
        try {
            for (LP lcp : props) {
                ImMap<Object, Object> values = MapFact.EMPTY();
                for (int i = 0; i < mapKeys.values().size(); i++) {
                    values = values.addExcl(lcp.listInterfaces.get(i), mapKeys.values().get(i));
                }
                Where where = importJoin.getWhere();
                if (replaceOnlyNullSet.contains(lcp.property.getSID())) {
                    where = where.and(((CalcProperty)lcp.property).getExpr(values).getWhere().not());
                }
                PropertyChange propChange = new PropertyChange(values.toRevMap(), importJoin.getExpr(lcp), where);
                context.getEnv().change((CalcProperty) lcp.property, propChange);
            }
        } finally {
            importTable.drop(sql, owner);
        }
        return session.applyMessage(context);
    }

    private DataObject getBooleanObject(Object value) {
        return value instanceof Boolean ? new DataObject((Boolean) value) : value instanceof Integer ? new DataObject(((Integer) value) != 0) : new DataObject(String.valueOf(value).equalsIgnoreCase("true"));
    }

    private Type getKeyType(Object key) {
        Type keyType;
        if(key instanceof Date) {
            keyType = DateClass.instance;
        } else if(key instanceof Time) {
            keyType = TimeClass.instance;
        } else if(key instanceof Timestamp) {
            keyType = DateTimeClass.instance;
        } else if(key instanceof Integer) {
            keyType = IntegerClass.instance;
        } else {
            keyType = LongClass.instance;
        }
        return keyType;
    }

    private ValueClass getKeyClass(ExecutionContext context, String key) {
        ValueClass valueClass;
        switch (key) {
            case "DATE":
                valueClass = DateClass.instance;
                break;
            case "TIME":
                valueClass = TimeClass.instance;
                break;
            case "DATETIME":
                valueClass = DateTimeClass.instance;
                break;
            case "INTEGER":
                valueClass = IntegerClass.instance;
                break;
            default:
                valueClass = context.getBL().findClass(key.replace("_", "."));
                break;
        }
        return valueClass;
    }
}
