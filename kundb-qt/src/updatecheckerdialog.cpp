#include "updatecheckerdialog.h"

#include <QFormLayout>
#include <QGroupBox>
#include <QLabel>
#include <QVBoxLayout>

UpdateCheckerDialog::UpdateCheckerDialog(QWidget* parent)
    : QDialog(parent) {
    setWindowTitle("KUN-DB 更新");
    setMinimumWidth(480);

    auto* form = new QFormLayout();
    currentLabel_ = new QLabel("—");
    latestLabel_ = new QLabel("—");
    statusLabel_ = new QLabel("—");
    form->addRow("当前版本", currentLabel_);
    form->addRow("最新版本", latestLabel_);
    form->addRow("状态", statusLabel_);

    changelogEdit_ = new QTextEdit();
    changelogEdit_->setReadOnly(true);
    changelogEdit_->setMinimumHeight(150);

    updateBtn_ = new QPushButton("下载并更新");
    customBtn_ = new QPushButton("自定义安装");
    laterBtn_ = new QPushButton("稍后");
    updateBtn_->setEnabled(false);
    customBtn_->setEnabled(false);

    auto* btnBox = new QHBoxLayout();
    btnBox->addWidget(updateBtn_);
    btnBox->addWidget(customBtn_);
    btnBox->addStretch(1);
    btnBox->addWidget(laterBtn_);

    auto* main = new QVBoxLayout(this);
    main->addLayout(form);
    main->addWidget(new QLabel("更新说明："));
    main->addWidget(changelogEdit_);
    main->addLayout(btnBox);

    connect(laterBtn_, &QPushButton::clicked, this, &QDialog::reject);
    connect(updateBtn_, &QPushButton::clicked, this, &UpdateCheckerDialog::updateRequested);
    connect(customBtn_, &QPushButton::clicked, this, &UpdateCheckerDialog::customRequested);
}

void UpdateCheckerDialog::setChecking() {
    currentLabel_->setText("—");
    latestLabel_->setText("—");
    statusLabel_->setText("正在检查更新…");
    changelogEdit_->clear();
    updateBtn_->setEnabled(false);
    customBtn_->setEnabled(false);
}

void UpdateCheckerDialog::setStatus(const QString& current, const QString& latest,
                                   const QString& status, bool available, bool downloaded,
                                   const QString& changelog) {
    currentLabel_->setText(current);
    latestLabel_->setText(latest);
    statusLabel_->setText(status);
    changelogEdit_->setPlainText(changelog);
    updateBtn_->setEnabled(available);
    customBtn_->setEnabled(available);
    updateBtn_->setText(downloaded ? "立即安装" : "下载并更新");
}

void UpdateCheckerDialog::showApplying() {
    statusLabel_->setText("正在应用更新，KUN-DB 将重启以完成升级…");
    updateBtn_->setEnabled(false);
    customBtn_->setEnabled(false);
}
