#pragma once
#include <QObject>
#include <QThread>
#include "sqlconnection.h"
#include "sqlresult.h"

// ConnectionWorker：持有一个 SqlConnection，运行在专用线程上，
// 通过信号/槽把阻塞式的网络 I/O 转成异步调用，避免界面卡顿。
class ConnectionWorker : public QObject {
    Q_OBJECT
public:
    explicit ConnectionWorker(QObject* parent = nullptr) : QObject(parent), conn_(new SqlConnection()) {}
    ~ConnectionWorker() override { delete conn_; }

    SqlConnection* raw() { return conn_; }

public slots:
    void connectTo(const QString& host, int port, const QString& user, const QString& pwd, const QString& db);
    void disconnectFromServer();
    void execute(const QString& sql, qint64 token);
    void pingServer();
    void loadDatabases();
    void loadTables(const QString& db);
    void loadColumns(const QString& db, const QString& table);

signals:
    void connected(bool ok, const QString& err, const QString& serverVersion);
    void disconnected();
    void resultReady(const SqlResult& res, qint64 token);
    void databasesReady(const QStringList& dbs);
    void tablesReady(const QString& db, const QStringList& tables, const QStringList& views);
    void columnsReady(const QString& db, const QString& table, const QList<ColumnInfo>& cols);
    void pingResult(bool ok);

private:
    SqlConnection* conn_;
};
