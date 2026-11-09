#pragma once
#include <QWidget>
#include <QTabWidget>
#include <QSplitter>
#include <QTextEdit>
#include <QLabel>
#include "sqleditor.h"
#include "resultgrid.h"
#include "sqlresult.h"

// 一个「SQL 控制台」标签页：顶栏右侧显示当前库；两段式布局
// （SQL 编辑器 / 结果网格）。「消息」不再是本标签页的一部分，而是主窗口
// 底部一个可拆解的停靠窗（全局消息日志）；本类只通过信号把日志发出去。
class ConsoleTab : public QWidget {
    Q_OBJECT
public:
    explicit ConsoleTab(QWidget* parent = nullptr);

signals:
    // 向主窗口全局「消息」停靠窗追加一行（isError=true 时标红）
    void messageAppended(const QString& text, bool isError);

public:
    SqlEditor* editor() { return editor_; }
    void appendMessage(const QString& msg);
    void appendError(const QString& msg);   // 红色错误行
    // 新增一个结果网格标签页；Ok 类结果（USE/INSERT/DDL 等）只写消息日志，不建网格
    void addResultGrid(const SqlResult& res, int index);
    void setCurrentDb(const QString& db);   // 更新右上角「当前库」显示
    QString currentDb() const { return currentDb_; }
    QString status() const { return status_; }
    void setStatus(const QString& s) { status_ = s; }

private:
    void updateResultsVisibility();   // 无结果标签页时隐藏结果区，让编辑器占满控制台

    SqlEditor* editor_;
    QSplitter* split_;
    QTabWidget* results_;
    QLabel* dbLabel_;
    QString currentDb_;
    QString status_;
    int resultCounter_ = 0;   // 本控制台累计结果编号（仅结果集递增）
};
