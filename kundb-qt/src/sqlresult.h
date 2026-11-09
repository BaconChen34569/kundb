#pragma once
#include <QString>
#include <QList>
#include <QMetaType>

// 一列的元信息（来自 Protocol::ColumnDefinition41）
struct ColumnInfo {
    QString name;     // 结果集列名
    QString table;    // 逻辑表名
    QString orgTable; // 物理表名
    int type = 0;     // MYSQL_TYPE_*
    int flags = 0;    // 1=NOT NULL, 2=PRI KEY, 4=UNIQUE
    QString typeName; // 可读类型名
};

// 一次 SQL 执行的结果：结果集 / OK（影响行数）/ 错误
struct SqlResult {
    enum Kind { ResultSet, Ok, Error } kind = Ok;
    QList<ColumnInfo> columns;
    QList<QList<QString>> rows;   // 文本协议：单元格一律是字符串
    QList<QList<bool>> nulls;     // 与 rows 同形：true 表示该格为 SQL NULL
    qlonglong affectedRows = 0;
    int errorCode = 0;
    QString errorMessage;
    QString info;        // OK 包的附加信息文本
    qint64 elapsedMs = 0;
};

Q_DECLARE_METATYPE(SqlResult)
Q_DECLARE_METATYPE(ColumnInfo)
Q_DECLARE_METATYPE(QList<ColumnInfo>)
