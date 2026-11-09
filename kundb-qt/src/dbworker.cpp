#include "dbworker.h"

void ConnectionWorker::connectTo(const QString& host, int port, const QString& user,
                                  const QString& pwd, const QString& db) {
    QString err;
    bool ok = conn_->connectTo(host, port, user, pwd, db, &err);
    emit connected(ok, ok ? QString() : err, conn_->serverVersion());
}

void ConnectionWorker::disconnectFromServer() {
    conn_->disconnect();
    emit disconnected();
}

void ConnectionWorker::execute(const QString& sql, qint64 token) {
    SqlResult res = conn_->execute(sql);
    emit resultReady(res, token);
}

void ConnectionWorker::pingServer() {
    emit pingResult(conn_->ping());
}

void ConnectionWorker::loadDatabases() {
    SqlResult res = conn_->execute("SHOW DATABASES");
    QStringList dbs;
    if (res.kind == SqlResult::ResultSet) {
        for (const auto& row : res.rows) {
            if (!row.isEmpty()) dbs.append(row.first());
        }
    }
    emit databasesReady(dbs);
}

void ConnectionWorker::loadTables(const QString& db) {
    // SHOW FULL TABLES FROM `db` -> 列: Table, Table_type
    SqlResult res = conn_->execute("SHOW FULL TABLES FROM `" + db + "`");
    QStringList tables, views;
    if (res.kind == SqlResult::ResultSet) {
        for (const auto& row : res.rows) {
            if (row.size() < 2) continue;
            QString name = row[0];
            QString type = row[1].toUpper();
            if (type.contains("VIEW")) views.append(name);
            else tables.append(name);
        }
    }
    emit tablesReady(db, tables, views);
}

void ConnectionWorker::loadColumns(const QString& db, const QString& table) {
    // SHOW FULL COLUMNS FROM `tbl` FROM `db` -> 列: Field, Type, ...
    SqlResult res = conn_->execute("SHOW FULL COLUMNS FROM `" + table + "` FROM `" + db + "`");
    QList<ColumnInfo> cols;
    if (res.kind == SqlResult::ResultSet) {
        for (const auto& row : res.rows) {
            ColumnInfo ci;
            if (row.size() > 0) ci.name = row[0];
            if (row.size() > 1) ci.typeName = row[1];
            if (row.size() > 4) {
                // Key 列（第 5 列）：PRI / UNI / MUL
                QString key = row[4].toUpper();
                if (key == "PRI") ci.flags |= 2;
                else if (key == "UNI") ci.flags |= 4;
            }
            if (row.size() > 3) {
                // Null 列（第 4 列）：YES / NO
                if (row[3].toUpper() == "NO") ci.flags |= 1;
            }
            cols.append(ci);
        }
    }
    emit columnsReady(db, table, cols);
}
