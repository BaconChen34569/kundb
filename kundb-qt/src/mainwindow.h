#pragma once
#include <QMainWindow>
#include <QDockWidget>
#include <QTextEdit>
#include <QTreeView>
#include <QStandardItemModel>
#include <QTabWidget>
#include <QLabel>
#include <QThread>
#include <QElapsedTimer>
#include <QMap>
#include "dbworker.h"
#include "consoltab.h"
#include "updatecheckerdialog.h"
class DataView;   // 仅前向声明；完整定义在使用处 (.cpp) 引入，避免 QHash<QPointer> 类型信息问题

class ConnectionDialog;

class MainWindow : public QMainWindow {
    Q_OBJECT
public:
    explicit MainWindow(QWidget* parent = nullptr);
    ~MainWindow() override;

protected:
    void showEvent(QShowEvent* e) override;
    void resizeEvent(QResizeEvent* e) override;
    bool eventFilter(QObject* obj, QEvent* ev) override;

signals:
    void sigConnect(const QString& host, int port, const QString& user, const QString& pwd, const QString& db);
    void sigDisconnect();
    void sigExecute(const QString& sql, qint64 token);
    void sigPing();
    void sigLoadDatabases();
    void sigLoadTables(const QString& db);
    void sigLoadColumns(const QString& db, const QString& table);

private slots:
    void onConnectAction();
    void onConnected(bool ok, const QString& err, const QString& serverVersion);
    void onDisconnected();
    void onResultReady(const SqlResult& res, qint64 token);
    void onDatabasesReady(const QStringList& dbs);
    void onTablesReady(const QString& db, const QStringList& tables, const QStringList& views);
    void onColumnsReady(const QString& db, const QString& table, const QList<ColumnInfo>& cols);
    void onPingResult(bool ok);

    void onItemExpanded(const QModelIndex& idx);
    void onItemDoubleClicked(const QModelIndex& idx);
    void onTreeContextMenu(const QPoint& pos);

    void onNewConsole();
    void runCurrent();
    void runAll();
    void refreshTree();
    void updatePing();
    void onCreateTable(const QString& db);
    void onCreateView(const QString& db);
    void onModifyTable(const QString& db, const QString& tbl);   // 可视化修改表（ALTER TABLE）
    void tryOpenModifyTable(const QString& db, const QString& tbl, const QList<ColumnInfo>& cols);

    // ---- 自动更新（KUN-DB 自更新）----
    void onCheckUpdate();
    void onUpdateStatusReady(const SqlResult& res);
    void onUpdateApply(bool custom);   // custom=true 走完整安装向导，false 走 /passive 半静默

private:
    ConsoleTab* newConsole(const QString& title);
    ConsoleTab* activeConsole();
    void runStatements(const QStringList& sqls, ConsoleTab* tab);
    void populateSchema(const QString& db, QStandardItem* schemaItem);
    void addCompletionWords(const QStringList& ws);
    void applyCompletionWords();
    void openDataView(const QString& db, const QString& tbl);
    void updateCurrentDb(const QString& db);   // 更新全局当前库并同步到所有控制台
    void appendGlobalMessage(const QString& text, bool isError);  // 全局「消息」停靠窗追加一行
    void restoreDock(QDockWidget* dock, Qt::DockWidgetArea area);  // 重新显示停靠窗并归位默认区域（防位置漂移）
    void resetSessionState();                  // 断开后清理会话状态（树/缓存/当前库）
    void rebalanceDocks();                     // 停靠窗重排：中央占位压回 0，控制台弹性吃剩余空间
    QStandardItem* itemFromIndex(const QModelIndex& idx) const;

    QTreeView* tree_;
    QStandardItemModel* treeModel_;
    QTabWidget* consoles_;
    QLabel* statusConn_;
    QLabel* statusInfo_;

    ConnectionWorker* worker_;
    QThread* workerThread_;

    bool connected_ = false;
    bool pendingTest_ = false;
    bool msgSized_ = false;                // 首次显示后给消息窗 170px 初始高度（只做一次）
    int treeW_ = 0;                        // 用户认可的「数据库对象」宽度（拖拽结束采认，窗口缩放/最大化时强制保持）
    int msgH_ = 0;                         // 用户认可的「消息」高度（同上）——重排只认这两个记忆值，点空白处不再挤动布局
    QString host_, user_, db_;
    int port_ = 8020;
    QString serverVersion_;
    QString currentDb_;   // 当前使用中的数据库（USE 成功后更新，新控制台继承）
    QDockWidget* consoleDock_ = nullptr;   // 控制台停靠窗（可拆解/浮动，类似数据库对象）
    QDockWidget* msgDock_ = nullptr;       // 底部「消息」停靠窗（全局日志，可拆解/浮动）
    QTextEdit* msgLog_ = nullptr;          // 「消息」停靠窗内的日志控件
    bool syncing_ = false;                 // 停靠窗可见性 ↔ 菜单勾选 双向同步防递归

    qint64 tokenCounter_ = 0;
    int batchCounter_ = 0;
    struct ReqCtx {
        ConsoleTab* tab = nullptr;
        int batchId = 0;
        int indexInBatch = 0;
        int batchSize = 0;
        QString sql;
        bool isUse = false;      // 该语句是否为 USE <db>
        QString useDb;           // USE 的目标库名（成功后更新当前库显示）
        QElapsedTimer timer;
    };
    QHash<qint64, ReqCtx> reqs_;
    QHash<int, int> batchDone_;
    QHash<int, int> batchTotal_;
    QHash<int, QElapsedTimer> batchStart_;

    QMap<QString, QStandardItem*> schemaItems_;   // db -> 库节点
    QMap<QString, QStandardItem*> tableItems_;    // "db|table" -> 表节点

    ConnectionDialog* connDialog_ = nullptr;      // 连接对话框（连接成功后自动 accept）
    QStringList completionWords_;                 // 补全词表（库/表/视图/列名，随对象树增长）

    UpdateCheckerDialog* updateDlg_ = nullptr;    // 更新检查对话框（懒创建）
    bool updateSilent_ = false;                  // true=连接时静默检查（仅发现新版本才弹窗）
    static constexpr qint64 kTokUpdateCheck = -1001;   // 更新专用 token（负值避免与普通查询冲突）
    static constexpr qint64 kTokUpdateStatus = -1002;
    static constexpr qint64 kTokUpdateApply = -1003;
    QHash<qint64, DataView*> dataReqs_;           // 数据视图发起的执行请求（token -> 视图，视图销毁时自动清除）

    QDockWidget* treeDock_ = nullptr;             // 左侧「数据库对象」可关闭停靠窗
    QHash<QString, QList<ColumnInfo>> colCache_;  // "db|table" -> 已加载列元数据（修改表对话框用）
    QString pendingModify_;                       // 等待列加载后打开「修改表」的 "db|table"

    static const int ROLE_TYPE = Qt::UserRole;
    static const int ROLE_DB = Qt::UserRole + 1;
    static const int ROLE_TABLE = Qt::UserRole + 2;
    static const int ROLE_STATE = Qt::UserRole + 3;
    static const int ROLE_COL = Qt::UserRole + 4;  // 列名（仅列节点）
};
