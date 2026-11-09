// 无界面协议自测：直接复用 SqlConnection（与 GUI 同一套线协议实现）连真实 KUN-DB 服务端，
// 验证握手 / 鉴权 / 元数据查询 / 建表 / 增删改查 / 结果集解析 / 探活 全部正确。
#include <QCoreApplication>
#include "sqlconnection.h"
#include <QStringList>
#include <QDebug>
#include <QFile>
#include <QTextStream>

// 把 qDebug 同时落到文件，便于无控制台环境下回看（GUI 测试机无控制台子系统）
static QFile g_logFile("protocol_test.log");
static void myMsgHandler(QtMsgType, const QMessageLogContext&, const QString& msg) {
    QTextStream(&g_logFile) << msg << "\n";
    g_logFile.flush();
}

static void dumpResult(const QString& sql, const SqlResult& r) {
    qDebug().noquote() << "  >> " << sql;
    if (r.kind == SqlResult::Error) {
        qDebug().noquote() << "     [ERROR] errno" << r.errorCode << r.errorMessage;
        return;
    }
    if (r.kind == SqlResult::Ok) {
        qDebug().noquote() << "     [OK] affected=" << r.affectedRows << "info=" << r.info
                           << "elapsed=" << r.elapsedMs << "ms";
        return;
    }
    qDebug().noquote() << "     [RESULT] cols=" << r.columns.size() << "rows=" << r.rows.size()
                       << "elapsed=" << r.elapsedMs << "ms";
    QString header;
    for (const auto& c : r.columns) header += c.name + "(" + c.typeName + ")  ";
    qDebug().noquote() << "     | " << header;
    for (const auto& row : r.rows) {
        QString line;
        for (int i = 0; i < row.size(); i++) {
            line += (r.nulls.isEmpty() ? row[i] : (r.nulls[r.rows.indexOf(row)][i] ? "NULL" : row[i])) + "  ";
        }
        qDebug().noquote() << "     | " << line;
    }
}

int main(int argc, char** argv) {
    QCoreApplication app(argc, argv);
    g_logFile.open(QIODevice::WriteOnly | QIODevice::Text | QIODevice::Truncate);
    qInstallMessageHandler(myMsgHandler);
    SqlConnection conn;
    QString err;
    bool ok = conn.connectTo("127.0.0.1", 8020, "root", "123456", "", &err);
    qDebug().noquote() << "CONNECT:" << ok << "version=" << conn.serverVersion() << "err=" << err;
    if (!ok) return 2;

    QStringList sqls = {
        "SHOW DATABASES",
        "CREATE DATABASE IF NOT EXISTS qttest",
        "USE qttest",
        "DROP TABLE IF EXISTS qttest.t",
        "CREATE TABLE qttest.t (id INT PRIMARY KEY, name VARCHAR(32) NOT NULL, score INT)",
        "INSERT INTO qttest.t VALUES (1,'alice',90),(2,'bob',85),(3,'carol',95)",
        "SELECT id,name,score FROM qttest.t ORDER BY score DESC",
        "SELECT COUNT(*) FROM qttest.t",
        "SHOW FULL TABLES FROM qttest",
        "SHOW FULL COLUMNS FROM t FROM qttest",
        "UPDATE qttest.t SET score=score+1 WHERE id=1",
        "DELETE FROM qttest.t WHERE id=3",
        "SELECT id,name,score FROM qttest.t",
        "SELECT * FROM qttest.t LIMIT 0"
    };
    for (const QString& sql : sqls) dumpResult(sql, conn.execute(sql));

    qDebug().noquote() << "PING:" << conn.ping();
    // 清理
    conn.execute("DROP TABLE IF EXISTS qttest.t");
    conn.execute("DROP DATABASE IF EXISTS qttest");
    qDebug().noquote() << "PROTOCOL TEST DONE";
    return 0;
}
