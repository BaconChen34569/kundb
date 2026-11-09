#include "mainwindow.h"
#include "connectiondialog.h"
#include "createtabledialog.h"
#include "modifytabledialog.h"
#include "dataview.h"
#include <QDockWidget>
#include <QMenuBar>
#include <QToolBar>
#include <QAction>
#include <QMessageBox>
#include <QTimer>
#include <QClipboard>
#include <QApplication>
#include <QHeaderView>
#include <QInputDialog>
#include <QPixmap>
#include <QPainter>
#include <QPainterPath>
#include <QStatusBar>
#include <QScrollBar>
#include <QTextEdit>
#include <QDialog>
#include <QVBoxLayout>
#include <QRegularExpression>
#include <QFormLayout>
#include <QLineEdit>
#include <QPlainTextEdit>
#include <QDialogButtonBox>
#include <QStyle>
#include <QEvent>
#include <QShowEvent>
#include <QResizeEvent>

MainWindow::MainWindow(QWidget* parent) : QMainWindow(parent) {
    setWindowTitle("KUN-DB 客户端");
    resize(1100, 720);

    // ---- worker 线程 ----
    worker_ = new ConnectionWorker();
    workerThread_ = new QThread(this);
    worker_->moveToThread(workerThread_);
    workerThread_->start();

    connect(this, &MainWindow::sigConnect, worker_, &ConnectionWorker::connectTo);
    connect(this, &MainWindow::sigDisconnect, worker_, &ConnectionWorker::disconnectFromServer);
    connect(this, &MainWindow::sigExecute, worker_, &ConnectionWorker::execute);
    connect(this, &MainWindow::sigPing, worker_, &ConnectionWorker::pingServer);
    connect(this, &MainWindow::sigLoadDatabases, worker_, &ConnectionWorker::loadDatabases);
    connect(this, &MainWindow::sigLoadTables, worker_, &ConnectionWorker::loadTables);
    connect(this, &MainWindow::sigLoadColumns, worker_, &ConnectionWorker::loadColumns);

    connect(worker_, &ConnectionWorker::connected, this, &MainWindow::onConnected);
    connect(worker_, &ConnectionWorker::disconnected, this, &MainWindow::onDisconnected);
    connect(worker_, &ConnectionWorker::resultReady, this, &MainWindow::onResultReady);
    connect(worker_, &ConnectionWorker::databasesReady, this, &MainWindow::onDatabasesReady);
    connect(worker_, &ConnectionWorker::tablesReady, this, &MainWindow::onTablesReady);
    connect(worker_, &ConnectionWorker::columnsReady, this, &MainWindow::onColumnsReady);
    connect(worker_, &ConnectionWorker::pingResult, this, &MainWindow::onPingResult);

    // ---- 控制台（默认右侧停靠，可像「数据库对象」一样拆解/浮动）----
    consoles_ = new QTabWidget(this);
    consoles_->setTabsClosable(true);
    connect(consoles_, &QTabWidget::tabCloseRequested, this, [this](int i) {
        QWidget* w = consoles_->widget(i);   // 允许全关（含最后一个「控制台 1」）；再执行 SQL / 连接时会自动新建
        consoles_->removeTab(i);
        if (w) w->deleteLater();
    });
    consoleDock_ = new QDockWidget("控制台", this);
    consoleDock_->setWidget(consoles_);
    consoleDock_->setFeatures(QDockWidget::DockWidgetClosable | QDockWidget::DockWidgetMovable | QDockWidget::DockWidgetFloatable);
    consoleDock_->setAllowedAreas(Qt::AllDockWidgetAreas);

    // 全局消息日志（底部可拆解停靠窗，不再内嵌在每个控制台里）
    msgLog_ = new QTextEdit(this);
    msgLog_->setReadOnly(true);
    msgLog_->setFont(QFont("Consolas", 10));
    msgLog_->append("就绪。输入 SQL 后按 Ctrl+Enter 执行；执行日志显示在底部「消息」停靠窗。");
    msgDock_ = new QDockWidget("消息", this);
    msgDock_->setWidget(msgLog_);
    msgDock_->setFeatures(QDockWidget::DockWidgetClosable | QDockWidget::DockWidgetMovable | QDockWidget::DockWidgetFloatable);
    msgDock_->setAllowedAreas(Qt::AllDockWidgetAreas);

    // 中央占位控件：QDockWidget 分隔条的拖拽依赖 centralWidget 存在（QTBUG-46306：无 central
    // 时分隔条拖不动/只能变大不能变小）。配合 rebalanceDocks()（窗口缩放/停靠窗开关/鼠标松开
    // 时触发）把它压回 0 尺寸——停靠窗铺满整个窗口，既无灰缝又可拖拽调宽。
    auto* central = new QWidget(this);
    central->setMinimumSize(0, 0);
    central->setSizePolicy(QSizePolicy::Ignored, QSizePolicy::Ignored);
    // 硬上限 0：往右拖分隔条收窄对象树时，多出的宽度不能被中央占位吸收（否则松手后
    // Qt 重布局会把空隙分回左边，分隔条「弹回去」）；只能直接给右侧控制台，拖动实时跟手
    central->setMaximumSize(0, 0);
    setCentralWidget(central);

    tree_ = new QTreeView(this);
    treeModel_ = new QStandardItemModel(this);
    treeModel_->setHorizontalHeaderLabels(QStringList() << "数据库对象");
    tree_->setModel(treeModel_);
    tree_->setHeaderHidden(true);   // 停靠窗标题已是「数据库对象」，隐藏列头避免重复
    tree_->setContextMenuPolicy(Qt::CustomContextMenu);
    tree_->setEditTriggers(QAbstractItemView::NoEditTriggers);  // 双击不改名，只展示
    tree_->setExpandsOnDoubleClick(true);   // 双击库/表即展开（表同时打开数据视图）
    tree_->setSelectionMode(QAbstractItemView::SingleSelection);
    connect(tree_, &QTreeView::expanded, this, &MainWindow::onItemExpanded);
    connect(tree_, &QTreeView::doubleClicked, this, &MainWindow::onItemDoubleClicked);
    connect(tree_, &QTreeView::customContextMenuRequested, this, &MainWindow::onTreeContextMenu);

    treeDock_ = new QDockWidget("数据库对象", this);
    treeDock_->setWidget(tree_);
    treeDock_->setFeatures(QDockWidget::DockWidgetClosable | QDockWidget::DockWidgetMovable | QDockWidget::DockWidgetFloatable);
    treeDock_->setAllowedAreas(Qt::LeftDockWidgetArea | Qt::RightDockWidgetArea);
    addDockWidget(Qt::LeftDockWidgetArea, treeDock_);

    // 三个停靠窗：左=数据库对象（整高）；右列=控制台（上）+ 消息（下）竖向拆分，共用右侧空间。
    // 消息并入同一右列（而非整窗底部区域）才能让它与控制台之间的竖向分隔条是原生可拖拽的。
    addDockWidget(Qt::RightDockWidgetArea, consoleDock_);
    splitDockWidget(consoleDock_, msgDock_, Qt::Vertical);   // 消息置于控制台下方，占右列
    resizeDocks({treeDock_, consoleDock_}, {220, 760}, Qt::Horizontal);   // 树固定宽度，右列弹性吃剩余
    resizeDocks({consoleDock_, msgDock_}, {480, 170}, Qt::Vertical);       // 控制台占大头，消息默认 170 高

    // ---- 菜单 / 工具栏 ----
    auto* fileMenu = menuBar()->addMenu("文件(&F)");
    auto* aConn = fileMenu->addAction("连接...");
    auto* aDisc = fileMenu->addAction("断开");
    fileMenu->addSeparator();
    fileMenu->addAction("退出", this, &QWidget::close);

    auto* qMenu = menuBar()->addMenu("查询(&Q)");
    auto* aNew = qMenu->addAction("新建查询台");
    auto* aRun = qMenu->addAction("执行当前语句  (Ctrl+Enter)");
    auto* aRunAll = qMenu->addAction("执行全部");
    auto* aRefresh = qMenu->addAction("刷新对象树");

    auto* tb = addToolBar("主工具栏");
    tb->addAction(aConn);
    tb->addAction(aDisc);
    tb->addSeparator();
    tb->addAction(aNew);
    tb->addAction(aRun);
    tb->addAction(aRunAll);
    tb->addAction(aRefresh);

    connect(aConn, &QAction::triggered, this, &MainWindow::onConnectAction);
    connect(aDisc, &QAction::triggered, this, [this]() { emit sigDisconnect(); });
    connect(aNew, &QAction::triggered, this, &MainWindow::onNewConsole);
    connect(aRun, &QAction::triggered, this, &MainWindow::runCurrent);
    connect(aRunAll, &QAction::triggered, this, &MainWindow::runAll);
    connect(aRefresh, &QAction::triggered, this, &MainWindow::refreshTree);

    // ---- 视图菜单：可关闭 / 重新打开各停靠窗（勾选状态与停靠窗可见性双向同步）----
    auto* viewMenu = menuBar()->addMenu("视图(&V)");
    auto addDockToggle = [this](QAction* a, QDockWidget* dock, Qt::DockWidgetArea area) {
        connect(a, &QAction::toggled, this, [this, dock, area](bool on) {
            if (syncing_) return;
            syncing_ = true;
            if (on) restoreDock(dock, area);   // 重新打开时归位默认区域，防止 Qt 记忆出「漂移布局」
            else dock->setVisible(false);
            syncing_ = false;
        });
        connect(dock, &QDockWidget::visibilityChanged, this, [this, a](bool v) {
            if (syncing_) return;
            syncing_ = true; a->setChecked(v); syncing_ = false;
            rebalanceDocks();   // 关掉一个停靠窗后，剩下的吃掉空出的空间
        });
    };
    auto* aTree = viewMenu->addAction("数据库对象");
    aTree->setCheckable(true); aTree->setChecked(true);
    addDockToggle(aTree, treeDock_, Qt::LeftDockWidgetArea);
    auto* aConsole = viewMenu->addAction("控制台");
    aConsole->setCheckable(true); aConsole->setChecked(true);
    addDockToggle(aConsole, consoleDock_, Qt::RightDockWidgetArea);
    auto* aTb = viewMenu->addAction("工具栏");
    aTb->setCheckable(true); aTb->setChecked(true);
    connect(aTb, &QAction::toggled, tb, &QToolBar::setVisible);
    viewMenu->addSeparator();
    auto* aMsg = viewMenu->addAction("消息");
    aMsg->setCheckable(true); aMsg->setChecked(true);
    addDockToggle(aMsg, msgDock_, Qt::BottomDockWidgetArea);

    // ---- 帮助菜单：自更新入口 ----
    auto* helpMenu = menuBar()->addMenu("帮助(&H)");
    auto* aCheckUpdate = helpMenu->addAction("检查更新...");
    connect(aCheckUpdate, &QAction::triggered, this, &MainWindow::onCheckUpdate);

    // ---- 状态栏 ----
    statusConn_ = new QLabel("未连接", this);
    statusInfo_ = new QLabel("", this);
    statusBar()->addPermanentWidget(statusConn_, 1);
    statusBar()->addPermanentWidget(statusInfo_, 1);

    // 周期探活
    auto* pingTimer = new QTimer(this);
    connect(pingTimer, &QTimer::timeout, this, &MainWindow::updatePing);
    pingTimer->start(30000);

    // 起始控制台
    newConsole("控制台 1");
    statusBar()->showMessage("提示：菜单「文件 > 连接...」连上 KUN-DB 后即可建库建表、跑 SQL。");
    qApp->installEventFilter(this);   // 捕获分隔条拖拽结束（松开鼠标）后重排停靠窗，压回中央占位。
                                      // 必须挂 qApp：拖拽时鼠标被分隔条句柄抓走，主窗口收不到松开事件
}

MainWindow::~MainWindow() {
    emit sigDisconnect();
    workerThread_->quit();
    workerThread_->wait();
    delete worker_;
}

// ===== 连接 =====
void MainWindow::onConnectAction() {
    auto* dlg = new ConnectionDialog(this);
    connDialog_ = dlg;
    connect(dlg, &ConnectionDialog::connectRequested, this, [this, dlg](const QString& h, int p, const QString& u, const QString& pw, const QString& db) {
        host_ = h; port_ = p; user_ = u; db_ = db; pendingTest_ = false;
        // 记住密码仅在本次会话
        dlg->setProperty("pwd", pw);
        dlg->setBusy(true);
        emit sigConnect(h, p, u, pw, db);
    });
    connect(dlg, &ConnectionDialog::testRequested, this, [this, dlg](const QString& h, int p, const QString& u, const QString& pw, const QString& db) {
        host_ = h; port_ = p; user_ = u; db_ = db; pendingTest_ = true;
        dlg->setProperty("pwd", pw);
        dlg->setBusy(true);
        emit sigConnect(h, p, u, pw, db);
    });
    connect(dlg, &QDialog::finished, dlg, &QDialog::deleteLater);
    connect(dlg, &QObject::destroyed, this, [this]() { if (connDialog_ == sender()) connDialog_ = nullptr; });
    dlg->exec();
}

void MainWindow::onConnected(bool ok, const QString& err, const QString& sv) {
    if (pendingTest_) {
        pendingTest_ = false;
        if (connDialog_) connDialog_->setBusy(false);
        if (ok) QMessageBox::information(this, "测试连接", "连接成功！服务器版本：" + sv);
        else QMessageBox::warning(this, "测试连接", "连接失败：" + err);
        emit sigDisconnect();
        return;
    }
    if (!ok) {
        if (connDialog_) connDialog_->setBusy(false);
        QMessageBox::warning(this, "连接失败", err);
        statusConn_->setText("未连接");
        return;
    }
    // 连接成功：自动关闭连接窗口
    if (connDialog_) { connDialog_->accept(); connDialog_ = nullptr; }
    connected_ = true;
    serverVersion_ = sv;
    statusConn_->setText(QString("%1:%2  %3@%4  [%5]")
                              .arg(host_).arg(port_).arg(user_).arg(db_.isEmpty() ? "(无默认库)" : db_)
                              .arg(sv));
    statusBar()->showMessage("已连接。正在加载数据库对象…");
    restoreDock(treeDock_, Qt::LeftDockWidgetArea);      // 重连时也归位，防止停靠布局漂移
    restoreDock(consoleDock_, Qt::RightDockWidgetArea);
    restoreDock(msgDock_, Qt::RightDockWidgetArea);      // 消息留在右列（restoreDock 内跳过 addDockWidget）
    updateCurrentDb(db_);   // 连接时选了默认库（或为空），同步到所有控制台右上角
    refreshTree();
    // 连接成功后静默查询一次更新状态（仅当发现新版本时自动弹窗，由 onUpdateStatusReady 处理）
    updateSilent_ = true;
    emit sigExecute("SHOW KUNDB UPDATE STATUS", kTokUpdateStatus);
    ConsoleTab* c = activeConsole();
    if (c) c->appendMessage(QString("已连接到 %1:%2，服务器版本 %3").arg(host_).arg(port_).arg(sv));
}

void MainWindow::onDisconnected() {
    bool wasConnected = connected_;
    connected_ = false;
    resetSessionState();
    if (wasConnected) {
        // 主动断开（菜单/工具栏「断开」或连接对话框测试完毕收尾）也要在「消息」里留痕
        appendGlobalMessage("数据库连接已断开。", true);
        statusBar()->showMessage("连接已断开。", 5000);
    }
}

void MainWindow::onPingResult(bool ok) {
    if (!connected_) return;
    if (ok) { statusInfo_->setText("在线"); return; }
    // 探活失败：连接实际已断（服务端退出/网络中断），按断开处理并给出提示
    statusInfo_->setText("连接已断开");
    connected_ = false;
    resetSessionState();
    appendGlobalMessage("数据库连接已断开（心跳探测失败，服务端可能已退出）。", true);
    statusBar()->showMessage("连接已断开。", 5000);
    statusConn_->setText("未连接");
}

// ---- 自动更新（KUN-DB 自更新）----

void MainWindow::onCheckUpdate() {
    if (!connected_) {
        QMessageBox::information(this, "检查更新", "请先连接到 KUN-DB 服务器后再检查更新。");
        return;
    }
    if (!updateDlg_) {
        updateDlg_ = new UpdateCheckerDialog(this);
        connect(updateDlg_, &UpdateCheckerDialog::updateRequested, this, [this] { onUpdateApply(false); });
        connect(updateDlg_, &UpdateCheckerDialog::customRequested, this, [this] { onUpdateApply(true); });
    }
    updateSilent_ = false;   // 手动检查：始终弹窗展示结果
    updateDlg_->setChecking();
    updateDlg_->show();
    updateDlg_->raise();
    updateDlg_->activateWindow();
    // 查询当前更新状态（服务端会在后台返回最新版本信息）
    emit sigExecute("SHOW KUNDB UPDATE STATUS", kTokUpdateStatus);
}

void MainWindow::onUpdateStatusReady(const SqlResult& res) {
    if (res.rows.isEmpty() || res.columns.isEmpty()) {
        // 无数据：仅手动检查场景回填提示，静默（连接自动检查）场景不打扰用户
        if (!updateSilent_ && updateDlg_) {
            updateDlg_->setStatus("—", "—", "无更新信息（服务端尚未完成检查）", false, false, "");
        }
        return;
    }
    // 按需懒创建对话框并连接信号（静默检查发现更新时也会走到这里）
    if (!updateDlg_) {
        updateDlg_ = new UpdateCheckerDialog(this);
        connect(updateDlg_, &UpdateCheckerDialog::updateRequested, this, [this] { onUpdateApply(false); });
        connect(updateDlg_, &UpdateCheckerDialog::customRequested, this, [this] { onUpdateApply(true); });
    }
    // 按列名取索引，避免顺序依赖（columns 为 QList<ColumnInfo>，按 name 比较）
    auto idxOf = [&](const QString& name) -> int {
        for (int i = 0; i < res.columns.size(); ++i) {
            if (res.columns.at(i).name.compare(name, Qt::CaseInsensitive) == 0) {
                return i;
            }
        }
        return -1;
    };
    const QStringList& row = res.rows.first();
    auto cell = [&](const QString& name) -> QString {
        int i = idxOf(name);
        return i >= 0 && i < row.size() ? row.at(i) : QString();
    };
    QString current = cell("current_version");
    QString latest = cell("latest_version");
    QString status = cell("status");
    bool available = cell("update_available").compare("true", Qt::CaseInsensitive) == 0;
    bool downloaded = cell("downloaded").compare("true", Qt::CaseInsensitive) == 0;
    QString changelog = cell("changelog");
    if (changelog.isEmpty()) {
        changelog = available ? ("新版本 " + latest + " 已发布。") : "当前已是最新版本。";
    }

    QString statusText;
    if (available) {
        statusText = downloaded ? "已下载，可立即安装" : "发现新版本，可下载并更新";
    } else if (status.compare("ERROR", Qt::CaseInsensitive) == 0) {
        statusText = "检查失败：" + cell("error");
    } else {
        statusText = "已是最新版本";
    }
    updateDlg_->setStatus(current, latest, statusText, available, downloaded, changelog);

    // 弹窗策略：手动检查始终展示；静默（连接时）检查仅当确有新版本才弹窗
    if (!updateSilent_ || available) {
        updateDlg_->show();
        updateDlg_->raise();
        updateDlg_->activateWindow();
    }
}

void MainWindow::onUpdateApply(bool custom) {
    if (!connected_) {
        QMessageBox::information(this, "更新", "请先连接到 KUN-DB 服务器。");
        return;
    }
    if (updateDlg_) updateDlg_->showApplying();
    // 触发服务端应用更新：服务端会停止自身并由 MSI 完成升级（进程随后退出）
    QString sql = custom ? "SHOW KUNDB UPDATE APPLY FULL" : "SHOW KUNDB UPDATE APPLY";
    emit sigExecute(sql, kTokUpdateApply);
}

// ===== 对象树 =====
// ===== 对象树图标（程序化绘制，免外部资源） =====
namespace {
    // 数据库（圆柱体，蓝）
    QIcon iconSchema() {
        QPixmap pm(16, 16);
        pm.fill(Qt::transparent);
        QPainter p(&pm);
        p.setRenderHint(QPainter::Antialiasing);
        p.setPen(QPen(QColor(0x1B, 0x54, 0x9E), 1.2));
        p.setBrush(QColor(0x2E, 0x6D, 0xB4));
        p.drawEllipse(QPointF(8, 4.5), 5.5, 2.8);
        p.drawRect(QRectF(2.5, 4.5, 11, 7));
        p.drawEllipse(QPointF(8, 11.5), 5.5, 2.8);
        p.setPen(Qt::NoPen);
        p.setBrush(QColor(0x5A, 0x93, 0xD4));
        p.drawEllipse(QPointF(8, 4.5), 5.5, 2.8);
        return QIcon(pm);
    }
    // 表（网格，绿）
    QIcon iconTable() {
        QPixmap pm(16, 16);
        pm.fill(Qt::transparent);
        QPainter p(&pm);
        p.setRenderHint(QPainter::Antialiasing);
        p.setPen(QPen(QColor(0x1E, 0x5C, 0x36), 1));
        p.setBrush(QColor(0x4C, 0xAF, 0x7C));
        p.drawRoundedRect(1.5, 2.5, 13, 11, 1.5, 1.5);
        p.setPen(QPen(QColor(0xE8, 0xF5, 0xE9), 1));
        p.drawLine(1.5, 6, 14.5, 6);
        p.drawLine(1.5, 9.5, 14.5, 9.5);
        p.drawLine(7, 6, 7, 13.5);
        p.drawLine(11, 6, 11, 13.5);
        return QIcon(pm);
    }
    // 视图（表格 + 小眼睛，紫）
    QIcon iconView() {
        QPixmap pm(16, 16);
        pm.fill(Qt::transparent);
        QPainter p(&pm);
        p.setRenderHint(QPainter::Antialiasing);
        p.setPen(QPen(QColor(0x4A, 0x23, 0x63), 1));
        p.setBrush(QColor(0x9B, 0x59, 0xB6));
        p.drawRoundedRect(1.5, 2.5, 13, 11, 1.5, 1.5);
        p.setPen(QPen(QColor(0xF3, 0xE5, 0xF5), 1));
        p.drawLine(1.5, 6, 14.5, 6);
        p.drawLine(1.5, 9.5, 14.5, 9.5);
        // 眼睛
        p.setPen(QPen(QColor(0x4A, 0x23, 0x63), 1.1));
        p.setBrush(QColor(0xFF, 0xFF, 0xFF));
        QPainterPath eye;
        eye.moveTo(5, 8);
        eye.quadTo(8, 5.2, 11, 8);
        eye.quadTo(8, 10.8, 5, 8);
        p.drawPath(eye);
        p.setBrush(QColor(0x4A, 0x23, 0x63));
        p.drawEllipse(QPointF(8, 8), 1.1, 1.1);
        return QIcon(pm);
    }
    // 列（灰色小标签；主键加钥匙点）
    QIcon iconColumn(bool pk) {
        QPixmap pm(16, 16);
        pm.fill(Qt::transparent);
        QPainter p(&pm);
        p.setRenderHint(QPainter::Antialiasing);
        p.setPen(QPen(QColor(0x5A, 0x62, 0x68), 1));
        p.setBrush(QColor(0xAE, 0xB6, 0xBD));
        p.drawRoundedRect(2.5, 3, 11, 10, 2, 2);
        p.setPen(QPen(QColor(0xEC, 0xF0, 0xF1), 1.1));
        p.drawLine(5, 6.5, 11, 6.5);
        p.drawLine(5, 9.5, 11, 9.5);
        if (pk) {
            p.setPen(Qt::NoPen);
            p.setBrush(QColor(0xE6, 0xB0, 0x22)); // 金色钥匙点
            p.drawEllipse(QPointF(11.5, 11.5), 3, 3);
            p.setBrush(Qt::white);
            p.drawEllipse(QPointF(11.5, 11.5), 1.1, 1.1);
        }
        return QIcon(pm);
    }
} // namespace

QStandardItem* MainWindow::itemFromIndex(const QModelIndex& idx) const {
    return treeModel_->itemFromIndex(idx);
}

void MainWindow::refreshTree() {
    if (!connected_) return;
    treeModel_->clear();
    treeModel_->setHorizontalHeaderLabels(QStringList() << "数据库对象");
    schemaItems_.clear();
    tableItems_.clear();
    colCache_.clear();
    pendingModify_.clear();
    emit sigLoadDatabases();
}
// ===== 补全词表（库/表/视图/列名随对象树加载增长） =====
void MainWindow::addCompletionWords(const QStringList& ws) {
    bool changed = false;
    for (const QString& w : ws) {
        if (!w.isEmpty() && !completionWords_.contains(w, Qt::CaseInsensitive)) {
            completionWords_.append(w);
            changed = true;
        }
    }
    if (changed) applyCompletionWords();
}

void MainWindow::applyCompletionWords() {
    for (int i = 0; i < consoles_->count(); i++) {
        if (auto* c = qobject_cast<ConsoleTab*>(consoles_->widget(i)))
            c->editor()->setCompletionWords(completionWords_);
    }
}

void MainWindow::onDatabasesReady(const QStringList& dbs) {
    for (const QString& db : dbs) {
        auto* it = new QStandardItem(iconSchema(), db);
        it->setData("schema", ROLE_TYPE);
        it->setData(db, ROLE_DB);
        it->setData(0, ROLE_STATE); // 未加载（用户展开后再拉取表/视图）
        // 懒加载占位子节点：QTreeView 只对有子节点的项显示展开箭头，
        // 没有它用户根本无从展开，表/视图永远加载不出来
        auto* ph = new QStandardItem("");
        ph->setData("dummy", ROLE_TYPE);
        it->appendRow(ph);
        treeModel_->appendRow(it);
        schemaItems_[db] = it;
    }
    if (dbs.isEmpty()) statusBar()->showMessage("连接成功，但没有可用数据库。");
    addCompletionWords(dbs);
}

void MainWindow::onItemExpanded(const QModelIndex& idx) {
    QStandardItem* it = itemFromIndex(idx);
    if (!it) return;
    QString type = it->data(ROLE_TYPE).toString();
    int state = it->data(ROLE_STATE).toInt();
    if (type == "schema" && state == 0) {
        it->removeRows(0, it->rowCount()); // 清掉占位子节点
        it->setData(1, ROLE_STATE); // pending
        emit sigLoadTables(it->data(ROLE_DB).toString());
    } else if ((type == "table" || type == "view") && state == 0) {
        it->removeRows(0, it->rowCount()); // 清掉占位子节点
        it->setData(1, ROLE_STATE);
        emit sigLoadColumns(it->data(ROLE_DB).toString(), it->data(ROLE_TABLE).toString());
    }
}

void MainWindow::onTablesReady(const QString& db, const QStringList& tables, const QStringList& views) {
    QStandardItem* schema = schemaItems_.value(db);
    if (!schema) return;
    if (schema->data(ROLE_STATE).toInt() == 2) return; // 已加载
    schema->removeRows(0, schema->rowCount());         // 移除懒加载占位节点
    // 表与视图直接挂在库节点下（不再套「表 / 视图」二级分组文件夹，避免每个库都重复一层、显得累赘）
    for (const QString& t : tables) {
        auto* ti = new QStandardItem(iconTable(), t);
        ti->setData("table", ROLE_TYPE);
        ti->setData(db, ROLE_DB);
        ti->setData(t, ROLE_TABLE);
        ti->setData(0, ROLE_STATE);
        auto* tph = new QStandardItem("");          // 列懒加载占位
        tph->setData("dummy", ROLE_TYPE);
        ti->appendRow(tph);
        schema->appendRow(ti);
        tableItems_[db + "|" + t] = ti;
    }
    for (const QString& v : views) {
        auto* vi = new QStandardItem(iconView(), v);
        vi->setData("view", ROLE_TYPE);
        vi->setData(db, ROLE_DB);
        vi->setData(v, ROLE_TABLE);
        vi->setData(0, ROLE_STATE);
        auto* vph = new QStandardItem("");          // 列懒加载占位
        vph->setData("dummy", ROLE_TYPE);
        vi->appendRow(vph);
        schema->appendRow(vi);
        tableItems_[db + "|" + v] = vi;
    }
    schema->setData(2, ROLE_STATE); // loaded
    addCompletionWords(tables + views);
}

void MainWindow::onColumnsReady(const QString& db, const QString& table, const QList<ColumnInfo>& cols) {
    colCache_[db + "|" + table] = cols;   // 缓存列元数据（「修改表」对话框直接取用）
    QStandardItem* t = tableItems_.value(db + "|" + table);
    if (!t || t->data(ROLE_STATE).toInt() == 2) {
        tryOpenModifyTable(db, table, cols);   // 列已加载过：可能是在等这次加载
        return;
    }
    t->removeRows(0, t->rowCount());   // 移除懒加载占位节点
    for (const ColumnInfo& c : cols) {
        auto* ci = new QStandardItem(iconColumn(c.flags & 2), c.name);  // 只显示列名；主键用图标金钥匙标识
        ci->setData("column", ROLE_TYPE);
        ci->setData(db, ROLE_DB);
        ci->setData(table, ROLE_TABLE);
        ci->setData(c.name, ROLE_COL);
        t->appendRow(ci);
    }
    t->setData(2, ROLE_STATE);
    tryOpenModifyTable(db, table, cols);
    QStringList names;
    for (const ColumnInfo& c : cols) names << c.name;
    addCompletionWords(names);
}

void MainWindow::onItemDoubleClicked(const QModelIndex& idx) {
    QStandardItem* it = itemFromIndex(idx);
    if (!it) return;
    QString type = it->data(ROLE_TYPE).toString();
    if (type != "table" && type != "view") return;
    openDataView(it->data(ROLE_DB).toString(), it->data(ROLE_TABLE).toString());
}

void MainWindow::onTreeContextMenu(const QPoint& pos) {
    QModelIndex idx = tree_->indexAt(pos);
    if (!idx.isValid()) return;
    QStandardItem* it = itemFromIndex(idx);
    if (!it) return;
    QString type = it->data(ROLE_TYPE).toString();
    QString db = it->data(ROLE_DB).toString();
    QString tbl = it->data(ROLE_TABLE).toString();

    QMenu menu(this);
    QAction* chosen = nullptr;

    if (type == "schema") {
        QAction* aNew = menu.addAction("新建表...");
        QAction* aNewView = menu.addAction("新建视图...");
        QAction* aRefresh = menu.addAction("刷新");
        QAction* aQuery = menu.addAction("新建查询台");
        chosen = menu.exec(tree_->viewport()->mapToGlobal(pos));
        if (chosen == aNew) onCreateTable(db);
        else if (chosen == aNewView) onCreateView(db);
        else if (chosen == aRefresh) refreshTree();
        else if (chosen == aQuery) onNewConsole();
    } else if (type == "table" || type == "view") {
        bool isView = (type == "view");
        QAction* aData = menu.addAction("查看 / 编辑数据");
        QAction* aMod = isView ? nullptr : menu.addAction("修改表...");
        QAction* aGen = menu.addAction("生成 SELECT 语句");
        QAction* aCopy = menu.addAction(isView ? "复制视图名" : "复制表名");
        QAction* aDrop = menu.addAction(isView ? "删除视图..." : "删除表...");
        chosen = menu.exec(tree_->viewport()->mapToGlobal(pos));
        if (chosen == aData) openDataView(db, tbl);
        else if (aMod && chosen == aMod) onModifyTable(db, tbl);
        else if (chosen == aGen) {
            ConsoleTab* c = activeConsole();
            c->editor()->appendPlainText(QString("\nSELECT * FROM `%1`.`%2` LIMIT 500;").arg(db, tbl));
            consoles_->setCurrentWidget(c);
        } else if (chosen == aCopy) QApplication::clipboard()->setText(tbl);
        else if (chosen == aDrop) {
            QString what = isView ? "视图" : "表";
            if (QMessageBox::question(this, isView ? "删除视图" : "删除表",
                    QString("确定删除%1 `%2`.`%3`？此操作不可恢复。").arg(what, db, tbl)) == QMessageBox::Yes) {
                if (isView) {
                    // 服务端 DROP VIEW 依赖当前库，先切库再删
                    runStatements(QStringList() << QString("USE `%1`").arg(db)
                                               << QString("DROP VIEW `%1`").arg(tbl),
                                  activeConsole());
                } else {
                    runStatements(QStringList() << QString("DROP TABLE `%1`.`%2`").arg(db, tbl),
                                  activeConsole());
                }
                refreshTree();
            }
        }
    } else if (type == "column") {
        QString col = it->data(ROLE_COL).toString();
        QAction* aWhere = menu.addAction(QString("生成 WHERE `%1` =").arg(col));
        QAction* aSel = menu.addAction(QString("生成 SELECT `%1`").arg(col));
        chosen = menu.exec(tree_->viewport()->mapToGlobal(pos));
        if (chosen) {
            ConsoleTab* c = activeConsole();
            QString snippet = (chosen == aWhere)
                ? QString("\nWHERE `%1`.`%2` = ;").arg(tbl, col)
                : QString("\nSELECT `%1`.`%2` FROM `%3`.`%4` LIMIT 500;").arg(tbl, col, db, tbl);
            c->editor()->appendPlainText(snippet);
            consoles_->setCurrentWidget(c);
        }
    }
}

void MainWindow::openDataView(const QString& db, const QString& tbl) {
    auto* dv = new DataView(this);
    consoles_->addTab(dv, "数据: " + db + "." + tbl);
    consoles_->setCurrentWidget(dv);
    // 视图被关闭销毁后，清掉所有指向它的待处理请求，避免悬空指针
    connect(dv, &QObject::destroyed, this, [this, dv]() {
        for (auto it = dataReqs_.begin(); it != dataReqs_.end(); ) {
            if (it.value() == dv) it = dataReqs_.erase(it);
            else ++it;
        }
    });
    connect(dv, &DataView::needExecute, this, [this, dv](const QString& sql) {
        qint64 tok = ++tokenCounter_;
        dataReqs_[tok] = dv;
        emit sigExecute(sql, tok);
    });
    dv->openTable(db, tbl, 500);
}

void MainWindow::onCreateTable(const QString& db) {
    CreateTableDialog dlg(this, db);
    if (dlg.exec() == QDialog::Accepted) {
        QString sql = dlg.createSql();
        runStatements(QStringList() << sql, activeConsole());
        refreshTree();
    }
}

// 可视化新建视图：输入视图名 + SELECT，先切库再 CREATE VIEW
void MainWindow::onCreateView(const QString& db) {
    QDialog dlg(this);
    dlg.setWindowTitle(QString("新建视图 - %1").arg(db));
    dlg.resize(460, 300);
    auto* lay = new QVBoxLayout(&dlg);
    auto* form = new QFormLayout();
    auto* edName = new QLineEdit(&dlg);
    auto* edSql = new QPlainTextEdit(&dlg);
    edSql->setPlaceholderText("SELECT ...");
    form->addRow("视图名：", edName);
    form->addRow("SELECT 语句：", edSql);
    lay->addLayout(form);
    auto* btns = new QDialogButtonBox(QDialogButtonBox::Ok | QDialogButtonBox::Cancel);
    connect(btns, &QDialogButtonBox::accepted, &dlg, &QDialog::accept);
    connect(btns, &QDialogButtonBox::rejected, &dlg, &QDialog::reject);
    lay->addWidget(btns);
    if (dlg.exec() != QDialog::Accepted) return;
    QString name = edName->text().trimmed();
    QString sel = edSql->toPlainText().trimmed();
    while (sel.endsWith(';')) sel.chop(1);
    if (name.isEmpty() || sel.isEmpty()) {
        QMessageBox::warning(this, "新建视图", "视图名和 SELECT 语句均不能为空。");
        return;
    }
    runStatements(QStringList() << QString("USE `%1`").arg(db)
                               << QString("CREATE VIEW `%1` AS %2").arg(name, sel),
                  activeConsole());
    refreshTree();
}

// 可视化修改表：需要列元数据，未加载时先异步拉取（pendingModify_ 挂起，onColumnsReady 回调打开）
void MainWindow::onModifyTable(const QString& db, const QString& tbl) {
    QString key = db + "|" + tbl;
    pendingModify_ = key;   // 先登记意图：tryOpenModifyTable 以此判断是否为本次挂起请求
    if (colCache_.contains(key)) {
        tryOpenModifyTable(db, tbl, colCache_.value(key));   // 列已缓存（表展开过）：直接打开
        return;
    }
    statusBar()->showMessage(QString("正在加载 %1.%2 的列信息…").arg(db, tbl));
    emit sigLoadColumns(db, tbl);
}

void MainWindow::tryOpenModifyTable(const QString& db, const QString& tbl, const QList<ColumnInfo>& cols) {
    if (pendingModify_ != db + "|" + tbl) return;
    pendingModify_.clear();
    ModifyTableDialog dlg(this, db, tbl, cols);
    if (dlg.exec() == QDialog::Accepted) {
        QString sql = dlg.alterSql();
        if (!sql.isEmpty()) {
            runStatements(QStringList() << sql, activeConsole());
            refreshTree();
        }
    }
}

// ===== 控制台与执行 =====
ConsoleTab* MainWindow::newConsole(const QString& title) {
    auto* c = new ConsoleTab(this);
    c->setCurrentDb(currentDb_);   // 新控制台继承当前库
    // 控制台里的执行日志转发到全局「消息」停靠窗
    connect(c, &ConsoleTab::messageAppended, this, &MainWindow::appendGlobalMessage);
    consoles_->addTab(c, title);
    consoles_->setCurrentWidget(c);
    connect(c->editor(), &SqlEditor::executeRequested, this, [this, c]() { runStatements(QStringList() << c->editor()->currentStatement(), c); });
    return c;
}

ConsoleTab* MainWindow::activeConsole() {
    ConsoleTab* c = qobject_cast<ConsoleTab*>(consoles_->currentWidget());
    if (!c) c = newConsole("控制台");
    return c;
}

void MainWindow::onNewConsole() {
    newConsole(QString("控制台 %1").arg(consoles_->count() + 1));
}

void MainWindow::updateCurrentDb(const QString& db) {
    currentDb_ = db;
    for (int i = 0; i < consoles_->count(); i++) {
        if (auto* c = qobject_cast<ConsoleTab*>(consoles_->widget(i)))
            c->setCurrentDb(db);
    }
}

void MainWindow::appendGlobalMessage(const QString& text, bool isError) {
    if (isError) {
        restoreDock(msgDock_, Qt::RightDockWidgetArea);   // 出错时自动弹出消息停靠窗（保持在右列底部），避免漏看失败
        msgLog_->append(QString("<span style=\"color:#c0392b;font-weight:bold;\">%1</span>")
                            .arg(text.toHtmlEscaped()));
    } else {
        msgLog_->append(text.toHtmlEscaped());   // append 末尾追加新段落；转义避免被当 HTML
    }
    QScrollBar* sb = msgLog_->verticalScrollBar();
    sb->setValue(sb->maximum());
}

void MainWindow::restoreDock(QDockWidget* dock, Qt::DockWidgetArea area) {
    // 重新显示前归位默认区域（浮动状态除外——用户特意拖出去的就保持浮动）。
    // 消息窗跳过 addDockWidget：它本就在右列控制台下方，强行 addDockWidget 会把它挪走破坏布局
    if (!dock->isFloating() && dock != msgDock_) addDockWidget(area, dock);
    dock->show();
    QTimer::singleShot(0, this, &MainWindow::rebalanceDocks);   // 显示后再重排，消除中央占位灰缝
}

void MainWindow::rebalanceDocks() {
    if (!isVisible()) return;
    const int sep = style()->pixelMetric(QStyle::PM_DockWidgetSeparatorExtent) * 2;
    // 水平：对象树强制保持记忆宽度 treeW_，控制台吃剩余 → 最大化/拉伸窗口比例不再漂移。
    // （直接采认 treeDock_ 当前宽度会把 Qt 重分布后的漂移值锁死）
    if (treeW_ > 0 && treeDock_->isVisible() && consoleDock_->isVisible()
            && !treeDock_->isFloating() && !consoleDock_->isFloating()) {
        int consoleW = qMax(320, width() - treeW_ - sep);
        if (qAbs(treeDock_->width() - treeW_) > 2 || qAbs(consoleDock_->width() - consoleW) > 2)
            resizeDocks({treeDock_, consoleDock_}, {treeW_, consoleW}, Qt::Horizontal);
    }
    // 垂直（右列内）：消息强制保持记忆高度 msgH_，控制台吃剩余。
    // 只在窗口缩放/停靠开关时执行；点空白处的松开事件只「采认」当前值、不会挤动布局
    if (msgH_ > 0 && consoleDock_->isVisible() && msgDock_->isVisible()
            && !consoleDock_->isFloating() && !msgDock_->isFloating()) {
        int columnH = (treeDock_->isVisible() && !treeDock_->isFloating())
                          ? treeDock_->height()   // 左区整高 = 停靠区实际高（勿用窗口总高，含菜单/工具/状态栏）
                          : height();
        int consoleH = qMax(200, columnH - msgH_ - sep);
        if (qAbs(msgDock_->height() - msgH_) > 2 || qAbs(consoleDock_->height() - consoleH) > 2)
            resizeDocks({consoleDock_, msgDock_}, {consoleH, msgH_}, Qt::Vertical);
    }
}

void MainWindow::resetSessionState() {
    updateCurrentDb(QString());
    treeModel_->clear();
    treeModel_->setHorizontalHeaderLabels(QStringList() << "数据库对象");
    schemaItems_.clear();
    tableItems_.clear();
    colCache_.clear();
    pendingModify_.clear();
}

void MainWindow::runCurrent() {
    ConsoleTab* c = activeConsole();
    if (!c) return;
    QString sel = c->editor()->textCursor().selectedText();
    sel.replace("\u2029", "\n").replace("\u2028", "\n");
    if (!sel.trimmed().isEmpty()) { runStatements(QStringList() << sel.trimmed(), c); return; }
    QString cur = c->editor()->currentStatement();
    if (cur.isEmpty()) return;
    runStatements(QStringList() << cur, c);
}

void MainWindow::runAll() {
    ConsoleTab* c = activeConsole();
    if (!c) return;
    QStringList sqls = c->editor()->allStatements();
    if (sqls.isEmpty()) return;
    runStatements(sqls, c);
}

void MainWindow::runStatements(const QStringList& sqls, ConsoleTab* tab) {
    if (!connected_) { QMessageBox::warning(this, "未连接", "请先通过「文件 > 连接...」连上 KUN-DB。"); return; }
    consoleDock_->show();   // 执行时确保控制台停靠窗可见，便于看到结果
    int batch = ++batchCounter_;
    batchTotal_[batch] = sqls.size();
    batchDone_[batch] = 0;
    batchStart_[batch].start();
    for (int i = 0; i < sqls.size(); i++) {
        const QString& sql = sqls[i];
        qint64 tok = ++tokenCounter_;
        ReqCtx ctx;
        ctx.tab = tab; ctx.batchId = batch; ctx.indexInBatch = i + 1;
        ctx.batchSize = sqls.size(); ctx.sql = sql; ctx.timer.start();
        // 识别 USE <db>：执行成功后更新「当前库」显示
        static const QRegularExpression useRe(
            "^\\s*use\\s+`?([^`\\s;]+)`?\\s*;?\\s*$",
            QRegularExpression::CaseInsensitiveOption);
        auto m = useRe.match(sql);
        if (m.hasMatch()) { ctx.isUse = true; ctx.useDb = m.captured(1); }
        reqs_[tok] = ctx;
        tab->appendMessage(QString("执行 (#%1/%2): %3").arg(i + 1).arg(sqls.size()).arg(sql.left(200)));
        emit sigExecute(sql, tok);
    }
    statusInfo_->setText("执行中…");
}

void MainWindow::onResultReady(const SqlResult& res, qint64 token) {
    // 自更新命令的结果路由到专用处理函数
    if (token == kTokUpdateStatus) { onUpdateStatusReady(res); return; }
    if (token == kTokUpdateApply) {
        appendGlobalMessage("已向服务器请求应用更新，KUN-DB 将重启以完成升级。", false);
        return;
    }

    // 数据视图（可视化编辑）发起的请求优先路由回去
    if (DataView* dv = dataReqs_.value(token)) {
        dataReqs_.remove(token);
        dv->onStatementResult(res);
        return;
    }
    ReqCtx ctx = reqs_.take(token);
    if (!ctx.tab) return;
    if (ctx.isUse && res.kind == SqlResult::Ok) {
        // USE 成功：更新当前库显示（所有控制台共享同一条连接）
        updateCurrentDb(ctx.useDb);
        ctx.tab->appendMessage(QString("当前数据库已切换为 %1").arg(ctx.useDb));
    }
    ctx.tab->addResultGrid(res, ctx.indexInBatch);
    if (res.kind == SqlResult::Error) {
        statusBar()->showMessage(QString("执行失败 [errno %1] %2").arg(res.errorCode).arg(res.errorMessage), 8000);
    }
    int b = ctx.batchId;
    batchDone_[b]++;
    if (batchDone_[b] == batchTotal_[b]) {
        qint64 ms = batchStart_[b].elapsed();
        ctx.tab->appendMessage(QString("批处理完成：共 %1 条语句，总耗时 %2 ms").arg(batchTotal_[b]).arg(ms));
        statusInfo_->setText(QString("上次执行 %1 ms").arg(ms));
        batchTotal_.remove(b); batchDone_.remove(b); batchStart_.remove(b);
    }
}

void MainWindow::updatePing() {
    if (connected_) emit sigPing();
}

void MainWindow::showEvent(QShowEvent* e) {
    QMainWindow::showEvent(e);
    if (!msgSized_) {
        msgSized_ = true;
        if (msgDock_->isVisible()) {
            resizeDocks({msgDock_}, {170}, Qt::Vertical);   // 消息窗初始 170px，控制台占大头
            msgH_ = 170;
        }
        // 默认左右比例仿 DataGrip：对象树约占窗口宽度 1/5，右侧吃剩余
        //（构造函数里的 resizeDocks 总和不足窗口宽会被 Qt 等比放大，比例失真，这里精确设定）
        treeW_ = qMax(200, width() / 5);
        resizeDocks({treeDock_}, {treeW_}, Qt::Horizontal);
    }
    rebalanceDocks();
}

void MainWindow::resizeEvent(QResizeEvent* e) {
    QMainWindow::resizeEvent(e);
    rebalanceDocks();   // 窗口拉伸/最大化时控制台跟随伸缩，不让中央占位留出灰缝
}

bool MainWindow::eventFilter(QObject* obj, QEvent* ev) {
    // 停靠窗分隔条拖动没有信号可监听：拖拽是实时生效的，松开鼠标时把当前尺寸「采认」为
    // 用户认可的值（treeW_/msgH_），再重排一次兜底。平时点击空白处采认的是原值，布局不动
    if (ev->type() == QEvent::MouseButtonRelease) {
        if (treeDock_->isVisible() && !treeDock_->isFloating()) treeW_ = treeDock_->width();
        if (msgDock_->isVisible() && !msgDock_->isFloating()) msgH_ = msgDock_->height();
        rebalanceDocks();
    }
    return QMainWindow::eventFilter(obj, ev);
}
