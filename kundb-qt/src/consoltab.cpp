#include "consoltab.h"
#include <QVBoxLayout>
#include <QHBoxLayout>
#include <QScrollBar>
#include <QDateTime>

ConsoleTab::ConsoleTab(QWidget* parent) : QWidget(parent) {
    auto* vbox = new QVBoxLayout(this);
    vbox->setContentsMargins(0, 0, 0, 0);
    vbox->setSpacing(0);

    // ---- 顶栏：右侧显示当前使用的数据库（随 USE 实时更新）----
    auto* topbar = new QHBoxLayout();
    topbar->setContentsMargins(0, 2, 0, 2);
    topbar->addStretch();
    dbLabel_ = new QLabel("当前库：(无)", this);
    QFont hf = dbLabel_->font();
    hf.setBold(true);
    dbLabel_->setFont(hf);
    dbLabel_->setStyleSheet("color:#1B549E; padding:0 8px;");
    topbar->addWidget(dbLabel_);
    vbox->addLayout(topbar);

    // ---- 两段式：SQL 编辑器 / 结果网格（消息已独立为底部「消息」停靠窗）----
    split_ = new QSplitter(Qt::Vertical, this);
    split_->setHandleWidth(2);              // 分隔条收窄，无多余空隙
    split_->setChildrenCollapsible(false);
    editor_ = new SqlEditor(this);
    results_ = new QTabWidget(this);
    results_->setDocumentMode(true);
    results_->setTabsClosable(true);
    split_->addWidget(editor_);
    split_->addWidget(results_);
    split_->setStretchFactor(0, 1);
    split_->setStretchFactor(1, 3);
    QList<int> sizes;
    sizes << 300 << 500;
    split_->setSizes(sizes);

    // 关闭结果标签：直接销毁该网格；关掉最后一个后隐藏结果区，让编辑器占满控制台
    connect(results_, &QTabWidget::tabCloseRequested, this, [this](int i) {
        QWidget* w = results_->widget(i);
        results_->removeTab(i);
        if (w) w->deleteLater();
        updateResultsVisibility();
    });

    vbox->addWidget(split_);
    updateResultsVisibility();   // 初始无结果 → 仅显示编辑器，占满整个控制台高度
}

// 没有结果标签页时隐藏结果区与分隔条，使 SQL 编辑器占满控制台剩余空间
void ConsoleTab::updateResultsVisibility() {
    bool has = results_->count() > 0;
    results_->setVisible(has);
    if (QSplitterHandle* h = split_->handle(1)) h->setVisible(has);
}

void ConsoleTab::setCurrentDb(const QString& db) {
    currentDb_ = db;
    dbLabel_->setText(db.isEmpty() ? "当前库：(无)" : QString("当前库：%1").arg(db));
}

// 日志转发到主窗口全局「消息」停靠窗（带时间戳）
void ConsoleTab::appendMessage(const QString& msg) {
    QString ts = QDateTime::currentDateTime().toString("hh:mm:ss");
    emit messageAppended(QString("[%1] %2").arg(ts, msg), false);
}

void ConsoleTab::appendError(const QString& msg) {
    QString ts = QDateTime::currentDateTime().toString("hh:mm:ss");
    emit messageAppended(QString("[%1] %2").arg(ts, msg), true);
}

void ConsoleTab::addResultGrid(const SqlResult& res, int index) {
    if (res.kind == SqlResult::Error) {
        appendError(QString("第 %1 条语句执行失败 [errno %2] %3")
                        .arg(index).arg(res.errorCode).arg(res.errorMessage));
        return;
    }
    if (res.kind == SqlResult::Ok) {
        // Ok 类结果（USE/INSERT/UPDATE/DDL 等）：不建空网格，写消息日志即可
        appendMessage(QString("第 %1 条语句执行成功，影响行数 %2，耗时 %3 ms")
                          .arg(index).arg(res.affectedRows).arg(res.elapsedMs));
        return;
    }
    int n = ++resultCounter_;
    auto* g = new ResultGrid(this);
    g->showResult(res);
    int idx = results_->addTab(g, QString("结果 %1").arg(n));
    results_->setCurrentIndex(idx);
    // 同步行数提示到消息
    appendMessage(QString("结果 %1：%2 行 × %3 列，耗时 %4 ms")
                      .arg(n).arg(res.rows.size()).arg(res.columns.size()).arg(res.elapsedMs));
    updateResultsVisibility();
}
