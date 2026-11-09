#pragma once
#include <QDialog>
#include <QPushButton>
#include <QLabel>
#include <QTextEdit>

/**
 * 更新检查对话框：展示当前/最新版本、状态与更新说明，并提供
 * 「下载并更新」（半静默 /passive）与「自定义安装」（完整向导）两种升级方式。
 * 实际触发由 MainWindow 监听信号后向 KUN-DB 服务器发送
 * SHOW KUNDB UPDATE APPLY [FULL] 完成（升级逻辑在服务端）。
 */
class UpdateCheckerDialog : public QDialog {
    Q_OBJECT
public:
    explicit UpdateCheckerDialog(QWidget* parent = nullptr);

    void setChecking();
    void setStatus(const QString& current, const QString& latest,
                  const QString& status, bool available, bool downloaded,
                  const QString& changelog);
    void showApplying();

    QPushButton* updateButton() { return updateBtn_; }
    QPushButton* customButton() { return customBtn_; }

signals:
    void updateRequested();   // 快速更新（/passive）
    void customRequested();   // 自定义安装（完整向导）

private:
    QLabel* currentLabel_;
    QLabel* latestLabel_;
    QLabel* statusLabel_;
    QTextEdit* changelogEdit_;
    QPushButton* updateBtn_;
    QPushButton* customBtn_;
    QPushButton* laterBtn_;
};
