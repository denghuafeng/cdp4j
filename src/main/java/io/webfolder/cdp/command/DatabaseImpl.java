/**
 * cdp4j Commercial License
 *
 * Copyright 2017, 2019 WebFolder OÜ
 *
 * Permission  is hereby  granted,  to "____" obtaining  a  copy of  this software  and
 * associated  documentation files  (the "Software"), to deal in  the Software  without
 * restriction, including without limitation  the rights  to use, copy, modify,  merge,
 * publish, distribute  and sublicense  of the Software,  and to permit persons to whom
 * the Software is furnished to do so, subject to the following conditions:
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR  IMPLIED,
 * INCLUDING  BUT NOT  LIMITED  TO THE  WARRANTIES  OF  MERCHANTABILITY, FITNESS  FOR A
 * PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL  THE AUTHORS  OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
 * CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE
 * OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package io.webfolder.cdp.command;

import java.util.List;

import com.google.gson.reflect.TypeToken;

import io.webfolder.cdp.session.SessionInvocationHandler;
import io.webfolder.cdp.type.database.ExecuteSQLResult;

public class DatabaseImpl implements Database {

    private static final String[] EMPTY_ARGS = new String[] {};
    private static final Object[] EMPTY_VALUES = new Object[] {};
    private static final TypeToken<List<String>> GET_DATABASE_TABLE_NAMES = new TypeToken<List<String>>() {
    };
    private final SessionInvocationHandler handler;

    public DatabaseImpl(SessionInvocationHandler handler) {
        this.handler = handler;
    }

    @Override
    public void disable() {
        handler.invoke("Database", "disable", "Database.disable", null, void.class, null, true, false, true, EMPTY_ARGS,
                EMPTY_VALUES);
    }

    @Override
    public void enable() {
        handler.invoke("Database", "enable", "Database.enable", null, void.class, null, true, true, false, EMPTY_ARGS,
                EMPTY_VALUES);
    }

    @Override
    public ExecuteSQLResult executeSQL(String databaseId, String query) {
        return (ExecuteSQLResult) handler.invoke("Database", "executeSQL", "Database.executeSQL", null,
                ExecuteSQLResult.class, null, false, false, false, new String[] { "databaseId", "query" },
                new Object[] { databaseId, query });
    }

    @Override
    @java.lang.SuppressWarnings("unchecked")
    public List<String> getDatabaseTableNames(String databaseId) {
        return (List<String>) handler.invoke("Database", "getDatabaseTableNames", "Database.getDatabaseTableNames",
                "tableNames", List.class, GET_DATABASE_TABLE_NAMES.getType(), false, false, false,
                new String[] { "databaseId" }, new Object[] { databaseId });
    }
}