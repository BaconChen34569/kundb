#pragma once
#include <QDialog>
#include <QLabel>
#include <QLineEdit>
#include <QTableWidget>
#include <QComboBox>
#include "sqlresult.h"

// 「修改表」可视化对话框：在现有列基础上 增 / 删 / 改列，生成
// ALTER TABLE `db`.`t` <动作1>, <动作2>...（服务端支持逗号多动作）。
// 动作映射：新列→ADD COLUMN；删除列→DROP COLUMN；改名→CHANGE COLUMN；
// 同名变更（类型/非空/默认值）→MODIFY COLUMN。
// 主键在此不可变更（服务端无 DROP PRIMARY KEY，避免生成不可回退的操作）。
class ModifyTableDialog : public QDialog {
    Q_OBJECT
public:
    ModifyTableDialog(QWidget* parent, const QString& database, const QString& table,
                      const QList<ColumnInfo>& cols);

    // 无任何变更时返回空串
    QString alterSql() const;

private:
    void addColumnRow(const QString& name, const QString& type, bool notNull, bool pk,
                      const QString& origName, const QString& origType, bool origNotNull);

    QLabel* dbTableLabel_;
    QTableWidget* grid_;
    QStringList origNames_;   // 构造时的原始列名（检测被删除的列 → DROP COLUMN）
    QString db_;
    QString tbl_;
    static const QStringList kTypes_;
};
