package lsfusion.erp.utils;

import com.google.common.base.Throwables;
import lsfusion.server.classes.ValueClass;
import lsfusion.server.data.SQLHandledException;
import lsfusion.server.logics.DataObject;
import lsfusion.server.logics.ThreadUtils;
import lsfusion.server.logics.property.ClassPropertyInterface;
import lsfusion.server.logics.property.ExecutionContext;
import lsfusion.server.logics.scripted.ScriptingActionProperty;
import lsfusion.server.logics.scripted.ScriptingErrorLog;
import lsfusion.server.logics.scripted.ScriptingLogicsModule;

import java.sql.SQLException;
import java.util.Iterator;

public class CancelActiveJavaThreadActionProperty extends ScriptingActionProperty {
    private final ClassPropertyInterface integerInterface;

    public CancelActiveJavaThreadActionProperty(ScriptingLogicsModule LM, ValueClass... classes) {
        super(LM, classes);

        Iterator<ClassPropertyInterface> i = interfaces.iterator();
        integerInterface = i.next();
    }

    @Override
    protected void executeCustom(ExecutionContext<ClassPropertyInterface> context) throws SQLException {

        try {
            getActiveThreadsFromDatabase(context);
        } catch (ScriptingErrorLog.SemanticErrorException e) {
            throw Throwables.propagate(e);
        } catch (SQLHandledException e) {
            throw Throwables.propagate(e);
        }

    }


    private void getActiveThreadsFromDatabase(ExecutionContext context) throws ScriptingErrorLog.SemanticErrorException, SQLException, SQLHandledException {

        DataObject currentObject = context.getDataKeyValue(integerInterface);
        Integer id = (Integer) findProperty("idActiveJavaThread[INTEGER]").read(context, currentObject);
        Thread thread = ThreadUtils.getThreadById(id);
        if(thread != null) {
            thread.stop();
        }
        findAction("getActiveJavaThreadsAction[]").execute(context);
    }
}
                     