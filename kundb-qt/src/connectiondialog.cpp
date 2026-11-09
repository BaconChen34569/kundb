#include "connectiondialog.h"
#include <QFormLayout>
#include <QVBoxLayout>
#include <QHBoxLayout>
#include <QLabel>

ConnectionDialog::ConnectionDialog(QWidget* parent) : QDialog(parent) {
    setWindowTitle("连接到 KUN-DB");
    setMinimumWidth(380);

    hostEdit_ = new QLineEdit("127.0.0.1", this);
    portEdit_ = new QLineEdit("8020", this);
    userEdit_ = new QLineEdit("root", this);
    pwdEdit_  = new QLineEdit("123456", this);
    pwdEdit_->setEchoMode(QLineEdit::Password);
    dbEdit_   = new QLineEdit("", this);

    auto* form = new QFormLayout();
    form->addRow("主机(Host):", hostEdit_);
    form->addRow("端口(Port):", portEdit_);
    form->addRow("用户(User):", userEdit_);
    form->addRow("密码(Password):", pwdEdit_);
    form->addRow("默认库(Database):", dbEdit_);
    form->addRow(new QLabel("默认账号 root / 123456，端口 8020", this), (QWidget*)nullptr);
    form->labelForField(dbEdit_)->setMinimumWidth(120);

    testBtn_ = new QPushButton("测试连接", this);
    okBtn_   = new QPushButton("连接", this);
    okBtn_->setDefault(true);
    auto* cancel = new QPushButton("取消", this);

    auto* btnRow = new QHBoxLayout();
    btnRow->addWidget(testBtn_);
    btnRow->addStretch(1);
    btnRow->addWidget(okBtn_);
    btnRow->addWidget(cancel);

    auto* vbox = new QVBoxLayout(this);
    vbox->addLayout(form);
    vbox->addLayout(btnRow);

    connect(testBtn_, &QPushButton::clicked, this, &ConnectionDialog::onTest);
    connect(okBtn_, &QPushButton::clicked, this, &ConnectionDialog::onConnect);
    connect(cancel, &QPushButton::clicked, this, &QDialog::reject);
}

void ConnectionDialog::onTest() {
    emit testRequested(host(), port(), user(), password(), database());
}

void ConnectionDialog::setBusy(bool busy) {
    testBtn_->setEnabled(!busy);
    okBtn_->setEnabled(!busy);
    okBtn_->setText(busy ? "连接中…" : "连接");
}

void ConnectionDialog::onConnect() {
    emit connectRequested(host(), port(), user(), password(), database());
}
