#pragma once
#include <QDialog>
#include <QLineEdit>
#include <QTableWidget>
#include <QComboBox>

// 「新建表」可视化对话框：定义列名 / 类型 / 约束，生成 CREATE TABLE。
class CreateTableDialog : public QDialog {
    Q_OBJECT
public:
    explicit CreateTableDialog(QWidget* parent = nullptr, const QString& database = QString());

    QString createSql() const;

private:
    void addColumnRow(const QString& name = QString(), const QString& type = "INT",
                      bool notNull = false, bool pk = false, bool ai = false, const QString& def = QString());
    void onAccept();

    QLineEdit* dbEdit_;
    QLineEdit* nameEdit_;
    QTableWidget* grid_;
    static const QStringList kTypes_;
};
