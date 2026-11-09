#pragma once
#include <QTcpSocket>
#include <QString>
#include "sqlresult.h"

// 自包含的 MySQL 线协议客户端（文本协议子集），不依赖任何第三方 MySQL 库。
// 线程模型：本对象的全部方法必须在【同一个线程】中调用（建议在专用 worker 线程内）。
// 它正是功能实现对照表 §四「类方式接口（SqlConnection/SqlCommand）」里要求的连接类。
class SqlConnection {
public:
    SqlConnection();
    ~SqlConnection();

    // 连接 + mysql_native_password 挑战应答鉴权。outError 返回失败原因。
    bool connectTo(const QString& host, int port,
                   const QString& user, const QString& password,
                   const QString& database, QString* outError = nullptr);

    void disconnect();
    bool isOpen() const;

    // 阻塞执行一条 SQL（COM_QUERY，文本协议）。
    SqlResult execute(const QString& sql);

    // 探活（COM_PING）。
    bool ping();

    QString serverVersion() const;

private:
    QTcpSocket* sock_ = nullptr;
    bool open_ = false;
    QString lastError_;
    QString serverVersion_;
    QByteArray scramble_;
    int seq_ = 0;

    bool readPacket(QByteArray& payload, QString* err);
    bool writePacket(const QByteArray& payload, QString* err);
    bool doHandshake(QString* err);
    bool sendAuth(const QString& user, const QString& password, QString* err);
    void parseResponse(const QByteArray& first, SqlResult& res, QString* err);
    void markClosed();
};
