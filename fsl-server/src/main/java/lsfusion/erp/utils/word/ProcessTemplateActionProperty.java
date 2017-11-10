package lsfusion.erp.utils.word;

import com.google.common.base.Throwables;
import lsfusion.base.col.MapFact;
import lsfusion.base.col.interfaces.immutable.ImMap;
import lsfusion.base.col.interfaces.immutable.ImOrderMap;
import lsfusion.base.col.interfaces.immutable.ImRevMap;
import lsfusion.interop.Compare;
import lsfusion.server.classes.ValueClass;
import lsfusion.server.data.SQLHandledException;
import lsfusion.server.data.expr.KeyExpr;
import lsfusion.server.data.query.QueryBuilder;
import lsfusion.server.logics.DataObject;
import lsfusion.server.logics.ObjectValue;
import lsfusion.server.logics.property.ClassPropertyInterface;
import lsfusion.server.logics.property.ExecutionContext;
import lsfusion.server.logics.scripted.ScriptingActionProperty;
import lsfusion.server.logics.scripted.ScriptingErrorLog;
import lsfusion.server.logics.scripted.ScriptingLogicsModule;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.usermodel.Range;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.xwpf.usermodel.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

public class ProcessTemplateActionProperty extends ScriptingActionProperty {
    public final ClassPropertyInterface templateInterface;

    public ProcessTemplateActionProperty(ScriptingLogicsModule LM, ValueClass... classes) throws ScriptingErrorLog.SemanticErrorException {
        super(LM, classes);

        Iterator<ClassPropertyInterface> i = interfaces.iterator();
        templateInterface = i.next();

    }

    public void executeCustom(ExecutionContext<ClassPropertyInterface> context) throws SQLException, SQLHandledException {

        try {

            DataObject templateObject = context.getDataKeyValue(templateInterface);

            if (templateObject != null) {

                ObjectValue fileObjectValue = findProperty("file[Template]").readClasses(context, templateObject);
                if (fileObjectValue instanceof DataObject) {

                    DataObject wordObject = (DataObject)fileObjectValue;
                    List<List<Object>> templateEntriesList = new ArrayList<>();

                    KeyExpr templateEntryExpr = new KeyExpr("TemplateEntry");
                    ImRevMap<Object, KeyExpr> templateEntryKeys = MapFact.singletonRev((Object) "TemplateEntry", templateEntryExpr);

                    QueryBuilder<Object, Object> templateEntryQuery = new QueryBuilder<>(templateEntryKeys);
                    templateEntryQuery.addProperty("keyTemplateEntry", findProperty("key[TemplateEntry]").getExpr(context.getModifier(), templateEntryExpr));
                    templateEntryQuery.addProperty("valueTemplateEntry", findProperty("value[TemplateEntry]").getExpr(context.getModifier(), templateEntryExpr));
                    templateEntryQuery.addProperty("isTableTemplateEntry", findProperty("isTable[TemplateEntry]").getExpr(context.getModifier(), templateEntryExpr));
                    templateEntryQuery.addProperty("firstRowTemplateEntry", findProperty("firstRow[TemplateEntry]").getExpr(context.getModifier(), templateEntryExpr));
                    templateEntryQuery.addProperty("columnSeparatorTemplateEntry", findProperty("columnSeparator[TemplateEntry]").getExpr(context.getModifier(), templateEntryExpr));
                    templateEntryQuery.addProperty("rowSeparatorTemplateEntry", findProperty("rowSeparator[TemplateEntry]").getExpr(context.getModifier(), templateEntryExpr));

                    templateEntryQuery.and(findProperty("template[TemplateEntry]").getExpr(context.getModifier(), templateEntryQuery.getMapExprs().get("TemplateEntry")).compare(templateObject.getExpr(), Compare.EQUALS));

                    ImOrderMap<ImMap<Object, Object>, ImMap<Object, Object>> templateEntryResult = templateEntryQuery.execute(context);

                    for (ImMap<Object, Object> templateEntry : templateEntryResult.values()) {

                        String keyTemplateEntry = (String) templateEntry.get("keyTemplateEntry");
                        String valueTemplateEntry = (String) templateEntry.get("valueTemplateEntry");
                        boolean isTableTemplateEntry = templateEntry.get("isTableTemplateEntry") != null;
                        Integer firstRowTemplateEntry = (Integer) templateEntry.get("firstRowTemplateEntry");
                        String columnSeparatorTemplateEntry = (String) templateEntry.get("columnSeparatorTemplateEntry");
                        String rowSeparatorTemplateEntry = (String) templateEntry.get("rowSeparatorTemplateEntry");

                        if (keyTemplateEntry != null && valueTemplateEntry != null)
                            templateEntriesList.add(Arrays.asList((Object) keyTemplateEntry, valueTemplateEntry.replace('\n', '\r'), 
                                                                  isTableTemplateEntry, firstRowTemplateEntry, columnSeparatorTemplateEntry, rowSeparatorTemplateEntry));
                    }

                    byte[] fileObject = (byte[]) fileObjectValue.getValue();
                    boolean isDocx = fileObject.length > 2 && fileObject[0] == 80 && fileObject[1] == 75;

                    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

                    if (isDocx) {
                        XWPFDocument document = new XWPFDocument(new ByteArrayInputStream((byte[]) wordObject.object));
                        List<XWPFParagraph> docParagraphs = document.getParagraphs();
                        for (List<Object> entry : templateEntriesList) {
                            String key = (String) entry.get(0);
                            String value = (String) entry.get(1);
                            boolean isTable = (boolean) entry.get(2);
                            Integer firstRow = (Integer) entry.get(3);
                            String columnSeparator = (String) entry.get(4);
                            String rowSeparator = (String) entry.get(5);
                            for (XWPFTable tbl : document.getTables()) {
                                replaceTableDataDocx(tbl, key, value, isTable, firstRow, columnSeparator, rowSeparator);
                            }
                            replaceRegularDataDocx(docParagraphs, key, value);
                        }
                        document.write(outputStream);
                    } else {
                        HWPFDocument document = new HWPFDocument(new POIFSFileSystem(new ByteArrayInputStream((byte[]) wordObject.object)));
                        Range range = document.getRange();
                        for (List<Object> entry : templateEntriesList) {
                            range.replaceText((String) entry.get(0), (String) entry.get(1));
                        }
                        document.write(outputStream);
                    }

                    findProperty("resultTemplate[]").change(outputStream.toByteArray(), context);
                }
            }
        } catch (IOException | ScriptingErrorLog.SemanticErrorException e) {
            throw Throwables.propagate(e);
        }
    }

    private void replaceTableDataDocx(XWPFTable tbl, String key, String value, boolean isTable, Integer firstRow, String columnSeparator, String rowSeparator) {
        if(isTable) {
            XWPFTableRow row = tbl.getRow(firstRow);
            XWPFTableCell cell = row.getCell(0);
            String text = cell.getText();
            if (text != null && text.contains(key)) {
                String[] tableRows = value.split(rowSeparator);
                int i = firstRow;
                for (String tableRow : tableRows) {
                    if (i == firstRow) {
                        XWPFTableRow newRow = tbl.getRow(i);
                        int j = 0;
                        for (String tableCell : tableRow.split(columnSeparator)) {
                            XWPFTableCell newCell = newRow.getTableICells().size() > j ? newRow.getCell(j) : newRow.createCell();
                            if (newCell.getText().isEmpty())
                                newCell.setText(tableCell);
                            else {
                                newCell.getParagraphs().get(0).getRuns().get(0).setText(tableCell, 0);
                            }
                            j++;
                        }
                    } else {
                        XWPFTableRow newRow = tbl.createRow();
                        int j = 0;
                        for (String tableCell : tableRow.split(columnSeparator)) {
                            XWPFTableCell newCell = newRow.getTableICells().size() > j ? newRow.getCell(j) : newRow.createCell();
                            newCell.setText(tableCell);
                            j++;
                        }
                    }
                    i++;
                }
            }
        } else {
            for (XWPFTableRow row : tbl.getRows()) {
                for (XWPFTableCell cell : row.getTableCells()) {
                    for (XWPFParagraph p : cell.getParagraphs()) {
                        for (XWPFRun r : p.getRuns()) {
                            String text = r.getText(0);
                            if (text != null && text.contains(key)) {
                                text = text.replace(key, value);
                                r.setText(text, 0);
                            }
                        }
                    }
                }
            }
        }
    }

    private void replaceRegularDataDocx(List<XWPFParagraph> docParagraphs, String key, String value) {
        for (XWPFParagraph p : docParagraphs) {
            List<XWPFRun> runs = p.getRuns();
            for (int i = runs.size() - 1; i >= 0; i--) {
                String text = runs.get(i).getText(0);
                if (text != null && text.contains(key)) {
                    text = text.replace(key, value);
                    String[] splitted = text.split("\r");
                    for (int j = 0; j < splitted.length; j++) {
                        if(j == 0)
                            runs.get(i).setText(splitted[j], 0);
                        else
                            runs.get(i).setText(splitted[j]);
                        if(j < (splitted.length - 1))
                            runs.get(i).addBreak();
                    }
                }
            }
        }
    }
}