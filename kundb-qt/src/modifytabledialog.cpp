#include "modifytabledialog.h"
#include <QVBoxLayout>
#include <QHBoxLayout>
#include <QPushButton>
#include <QHeaderView>
#include <QMessageBox>

// 行内私有数据角色：存原始列定义，供生成 ALTER 时做差异比较
static const int ROLE_ORIG_NAME  = Qt::UserRole;      // 原列名（新行为空）
static const int ROLE_ORIG_TYPE  = Qt::UserRole + 1;  // 原类型
static const int ROLE_ORIG_NN    = Qt::UserRole + 2;  // 原 NOT NULL

const QStringList ModifyTableDialog::kTypes_ = {
    "INT", "BIGINT", "VARCHAR(255)", "CHAR(1)", "TEXT", "BOOLEAN",
    "DOUBLE", "FLOAT", "DECIMAL(10,2)", "DATE", "DATETIME", "TIME", "TIMESTAMP"
};

ModifyTableDialog::ModifyTableDialog(QWidget* parent, const QString& database,
                                     const QString& table, const QList<ColumnInfo>& cols)
    : QDialog(parent), db_(database), tbl_(table) {
    for (const ColumnInfo& c : cols) origNames_ << c.name;   // 供 ALTER 生成时检测被删除的列
    setWindowTitle(QString("修改表 - %1.%2").arg(database, table));
    resize(640, 460);

    dbTableLabel_ = new QLabel(QString("数据库：<b>%1</b>　表名：<b>%2</b>").arg(database, table), this);

    grid_ = new QTableWidget(0, 5, this);
    grid_->setHorizontalHeaderLabels(QStringList() << "列名" << "类型" << "非空" << "主键" << "默认值");
    grid_->horizontalHeader()->setStretchLastSection(true);
    grid_->verticalHeader()->setVisible(false);

    for (const ColumnInfo& c : cols) {
        QString t = c.typeName.toUpper();
        if (t.startsWith("VARCHAR") && !t.contains('(')) t = "VARCHAR(255)";
        if (t.startsWith("CHAR") && !t.contains('(')) t = "CHAR(1)";
        if (t.startsWith("DECIMAL") && !t.contains('(')) t = "DECIMAL(10,2)";
        addColumnRow(c.name, t, c.flags & 1, c.flags & 2, c.name, t, c.flags & 1);
    }

    auto* addBtn = new QPushButton("+ 添加列", this);
    auto* delBtn = new QPushButton("- 删除选中列", this);
    connect(addBtn, &QPushButton::clicked, this, [this]() {
        addColumnRow(QString(), "INT", false, false, QString(), "INT", false);
    });
    connect(delBtn, &QPushButton::clicked, this, [this]() {
        QList<QTableWidgetItem*> sel = grid_->selectedItems();
        QSet<int> rows;
        for (QTableWidgetItem* it : sel) rows.insert(it->row());
        for (int r : rows) if (grid_->rowCount() > 1) grid_->removeRow(r);
    });

    auto* ok = new QPushButton("确定", this);
    ok->setDefault(true);
    auto* cancel = new QPushButton("取消", this);
    connect(ok, &QPushButton::clicked, this, [this]() {
        if (alterSql().isEmpty()) {
            QMessageBox::information(this, "修改表", "没有任何变更。");
            return;
        }
        accept();
    });
    connect(cancel, &QPushButton::clicked, this, &QDialog::reject);

    auto* btnRow = new QHBoxLayout();
    btnRow->addWidget(addBtn); btnRow->addWidget(delBtn); btnRow->addStretch();

    auto* dlgBtns = new QHBoxLayout();
    dlgBtns->addStretch(); dlgBtns->addWidget(ok); dlgBtns->addWidget(cancel);

    auto* vbox = new QVBoxLayout(this);
    vbox->addWidget(dbTableLabel_);
    vbox->addWidget(grid_);
    vbox->addLayout(btnRow);
    vbox->addLayout(dlgBtns);
}

void ModifyTableDialog::addColumnRow(const QString& name, const QString& type, bool notNull,
                                     bool pk, const QString& origName, const QString& origType,
                                     bool origNotNull) {
    int r = grid_->rowCount();
    grid_->insertRow(r);
    auto* nameItem = new QTableWidgetItem(name);
    nameItem->setData(ROLE_ORIG_NAME, origName);
    nameItem->setData(ROLE_ORIG_TYPE, origType);
    nameItem->setData(ROLE_ORIG_NN, origNotNull);
    grid_->setItem(r, 0, nameItem);
    auto* typeCombo = new QComboBox(this);
    typeCombo->addItems(kTypes_);
    int idx = kTypes_.indexOf(type);
    typeCombo->setCurrentIndex(idx < 0 ? 0 : idx);
    grid_->setCellWidget(r, 1, typeCombo);
    auto* nn = new QTableWidgetItem();
    nn->setCheckState(notNull ? Qt::Checked : Qt::Unchecked);
    nn->setFlags(Qt::ItemIsEnabled | Qt::ItemIsUserCheckable);
    grid_->setItem(r, 2, nn);
    auto* pkc = new QTableWidgetItem();
    pkc->setCheckState(pk ? Qt::Checked : Qt::Unchecked);
    pkc->setFlags(Qt::ItemIsEnabled);   // 只读：主键不可在修改表中变更
    pkc->setToolTip("主键不可在此修改（服务端不支持 DROP PRIMARY KEY）");
    grid_->setItem(r, 3, pkc);
    auto* defItem = new QTableWidgetItem();
    defItem->setToolTip("留空表示不指定 DEFAULT；注意 MODIFY 为覆盖式定义");
    grid_->setItem(r, 4, defItem);
}

QString ModifyTableDialog::alterSql() const {
    QStringList actions;
    QSet<QString> kept;   // 本次仍存在的原列名

    for (int r = 0; r < grid_->rowCount(); r++) {
        auto* nameItem = grid_->item(r, 0);
        if (!nameItem) continue;
        QString col = nameItem->text().trimmed();
        QString orig = nameItem->data(ROLE_ORIG_NAME).toString();
        if (col.isEmpty() && orig.isEmpty()) continue;   // 全空行跳过
        auto* typeCombo = qobject_cast<QComboBox*>(grid_->cellWidget(r, 1));
        QString type = typeCombo ? typeCombo->currentText().trimmed() : "INT";
        bool nn = grid_->item(r, 2) && grid_->item(r, 2)->checkState() == Qt::Checked;
        QString def = grid_->item(r, 4) ? grid_->item(r, 4)->text().trimmed() : QString();

        QString colDef = QString("`%1` %2").arg(col, type);
        if (nn) colDef += " NOT NULL";
        if (!def.isEmpty()) {
            bool num = false; def.toLongLong(&num);
            if (num) colDef += " DEFAULT " + def;
            else colDef += " DEFAULT '" + def.replace("'", "''") + "'";
        }

        if (orig.isEmpty()) {
            actions << ("ADD COLUMN " + colDef);          // 新增列
        } else {
            kept.insert(orig);
            bool typeChanged = nameItem->data(ROLE_ORIG_TYPE).toString().compare(type, Qt::CaseInsensitive) != 0;
            bool nnChanged = nameItem->data(ROLE_ORIG_NN).toBool() != nn;
            bool defChanged = !def.isEmpty();
            if (col != orig) {
                actions << QString("CHANGE COLUMN `%1` %2").arg(orig, colDef);   // 改名（可同时改类型）
            } else if (typeChanged || nnChanged || defChanged) {
                actions << ("MODIFY COLUMN " + colDef);   // 同名变更
            }
        }
    }

    // 构造时的原始列集合 - 仍保留的 = 被删除的 → DROP COLUMN
    for (const QString& orig : origNames_) {
        if (!kept.contains(orig))
            actions << QString("DROP COLUMN `%1`").arg(orig);
    }

    if (actions.isEmpty()) return QString();
    return QString("ALTER TABLE `%1`.`%2` %3;").arg(db_, tbl_, actions.join(",\n  "));
}
