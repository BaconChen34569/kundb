#pragma once
#include <QString>
#include "sqlresult.h"
#include "sqlconnection.h"

// SqlCommand：在已建立的 SqlConnection 上执行命令并迭代结果（类比 QSqlQuery）。
// 它把「连接」与「命令」两个概念拆开，正是功能实现对照表 §四 要求的类方式接口。
class SqlCommand {
public:
    explicit SqlCommand(SqlConnection* conn) : conn_(conn) {}

    // 执行一条 SQL，成功（含返回结果集/OK）返回 true；网络或协议失败返回 false。
    bool exec(const QString& sql) {
        if (!conn_) { res_.kind = SqlResult::Error; res_.errorMessage = "未绑定连接"; return false; }
        res_ = conn_->execute(sql);
        return res_.kind != SqlResult::Error;
    }

    const SqlResult& result() const { return res_; }

    bool isResultSet() const { return res_.kind == SqlResult::ResultSet; }
    bool isOk() const { return res_.kind == SqlResult::Ok; }
    bool isError() const { return res_.kind == SqlResult::Error; }

    int columnCount() const { return res_.columns.size(); }
    int rowCount() const { return res_.rows.size(); }

    QString columnName(int i) const { return i >= 0 && i < res_.columns.size() ? res_.columns[i].name : QString(); }
    QString columnTypeName(int i) const { return i >= 0 && i < res_.columns.size() ? res_.columns[i].typeName : QString(); }
    int columnFlags(int i) const { return i >= 0 && i < res_.columns.size() ? res_.columns[i].flags : 0; }

    QString value(int row, int col) const {
        if (row < 0 || row >= res_.rows.size()) return QString();
        if (col < 0 || col >= res_.rows[row].size()) return QString();
        return res_.rows[row][col];
    }
    bool isNull(int row, int col) const {
        if (row < 0 || row >= res_.nulls.size()) return true;
        if (col < 0 || col >= res_.nulls[row].size()) return true;
        return res_.nulls[row][col];
    }

    qlonglong affectedRows() const { return res_.affectedRows; }
    int errorCode() const { return res_.errorCode; }
    QString error() const { return res_.errorMessage; }

private:
    SqlConnection* conn_;
    SqlResult res_;
};
