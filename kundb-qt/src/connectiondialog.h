#pragma once
#include <QDialog>
#include <QLineEdit>
#include <QPushButton>

// 连接配置对话框（数据源配置）
class ConnectionDialog : public QDialog {
    Q_OBJECT
public:
    explicit ConnectionDialog(QWidget* parent = nullptr);

    QString host() const { return hostEdit_->text().trimmed(); }
    int port() const { return portEdit_->text().toInt(); }
    QString user() const { return userEdit_->text().trimmed(); }
    QString password() const { return pwdEdit_->text(); }
    QString database() const { return dbEdit_->text().trimmed(); }

    void setHost(const QString& v) { hostEdit_->setText(v); }
    void setPort(int v) { portEdit_->setText(QString::number(v)); }
    void setUser(const QString& v) { userEdit_->setText(v); }
    void setPassword(const QString& v) { pwdEdit_->setText(v); }
    void setDatabase(const QString& v) { dbEdit_->setText(v); }

    // 连接/测试进行中禁用按钮（异步完成后由 MainWindow 恢复或关闭窗口）
    void setBusy(bool busy);

private slots:
    void onTest();
    void onConnect();

signals:
    void testRequested(const QString& host, int port, const QString& user, const QString& pwd, const QString& db);
    void connectRequested(const QString& host, int port, const QString& user, const QString& pwd, const QString& db);

private:
    QLineEdit* hostEdit_;
    QLineEdit* portEdit_;
    QLineEdit* userEdit_;
    QLineEdit* pwdEdit_;
    QLineEdit* dbEdit_;
    QPushButton* testBtn_;
    QPushButton* okBtn_;
};
