#include "createtabledialog.h"
#include <QFormLayout>
#include <QVBoxLayout>
#include <QHBoxLayout>
#include <QPushButton>
#include <QHeaderView>
#include <QMessageBox>

const QStringList CreateTableDialog::kTypes_ = {
    "INT", "BIGINT", "VARCHAR(255)", "CHAR(1)", "TEXT", "BOOLEAN",
    "DOUBLE", "FLOAT", "DECIMAL(10,2)", "DATE", "DATETIME", "TIME", "TIMESTAMP"
};

CreateTableDialog::CreateTableDialog(QWidget* parent, const QString& database)
    : QDialog(parent) {
    setWindowTitle("新建表");
    resize(620, 460);

    dbEdit_ = new QLineEdit(database, this);
    dbEdit_->setReadOnly(true);
    nameEdit_ = new QLineEdit(this);

    grid_ = new QTableWidget(0, 6, this);
    grid_->setHorizontalHeaderLabels(QStringList() << "列名" << "类型" << "非空" << "主键" << "自增" << "默认值");
    grid_->horizontalHeader()->setStretchLastSection(true);
    grid_->verticalHeader()->setVisible(false);

    addColumnRow("id", "INT", true, true, true);
    addColumnRow("name", "VARCHAR(255)", true, false, false);

    auto* addBtn = new QPushButton("+ 添加列", this);
    auto* delBtn = new QPushButton("- 删除选中列", this);
    connect(addBtn, &QPushButton::clicked, this, [this]() { addColumnRow(); });
    connect(delBtn, &QPushButton::clicked, this, [this]() {
        QList<QTableWidgetItem*> sel = grid_->selectedItems();
        QSet<int> rows;
        for (QTableWidgetItem* it : sel) rows.insert(it->row());
        for (int r : rows) if (grid_->rowCount() > 1) grid_->removeRow(r);
    });

    auto* ok = new QPushButton("确定", this);
    ok->setDefault(true);
    auto* cancel = new QPushButton("取消", this);
    connect(ok, &QPushButton::clicked, this, &CreateTableDialog::onAccept);
    connect(cancel, &QPushButton::clicked, this, &QDialog::reject);

    auto* form = new QFormLayout();
    form->addRow("数据库", dbEdit_);
    form->addRow("表名", nameEdit_);

    auto* btnRow = new QHBoxLayout();
    btnRow->addWidget(addBtn); btnRow->addWidget(delBtn); btnRow->addStretch();

    auto* dlgBtns = new QHBoxLayout();
    dlgBtns->addStretch(); dlgBtns->addWidget(ok); dlgBtns->addWidget(cancel);

    auto* vbox = new QVBoxLayout(this);
    vbox->addLayout(form);
    vbox->addWidget(grid_);
    vbox->addLayout(btnRow);
    vbox->addLayout(dlgBtns);
}

void CreateTableDialog::addColumnRow(const QString& name, const QString& type,
                                     bool notNull, bool pk, bool ai, const QString& def) {
    int r = grid_->rowCount();
    grid_->insertRow(r);
    grid_->setItem(r, 0, new QTableWidgetItem(name));
    auto* typeCombo = new QComboBox(this);
    typeCombo->addItems(kTypes_);
    int idx = kTypes_.indexOf(type);
    typeCombo->setCurrentIndex(idx < 0 ? 0 : idx);
    grid_->setCellWidget(r, 1, typeCombo);
    auto* nn = new QTableWidgetItem(); nn->setCheckState(notNull ? Qt::Checked : Qt::Unchecked); nn->setFlags(Qt::ItemIsEnabled | Qt::ItemIsUserCheckable);
    auto* pkc = new QTableWidgetItem(); pkc->setCheckState(pk ? Qt::Checked : Qt::Unchecked); pkc->setFlags(Qt::ItemIsEnabled | Qt::ItemIsUserCheckable);
    auto* aic = new QTableWidgetItem(); aic->setCheckState(ai ? Qt::Checked : Qt::Unchecked); aic->setFlags(Qt::ItemIsEnabled | Qt::ItemIsUserCheckable);
    grid_->setItem(r, 2, nn);
    grid_->setItem(r, 3, pkc);
    grid_->setItem(r, 4, aic);
    grid_->setItem(r, 5, new QTableWidgetItem(def));
}

QString CreateTableDialog::createSql() const {
    QString db = dbEdit_->text().trimmed();
    QString tbl = nameEdit_->text().trimmed();
    QString tn = db.isEmpty() ? ("`" + tbl + "`") : QString("`%1`.`%2`").arg(db, tbl);
    QStringList defs;
    for (int r = 0; r < grid_->rowCount(); r++) {
        QString col = grid_->item(r, 0) ? grid_->item(r, 0)->text().trimmed() : QString();
        if (col.isEmpty()) continue;
        auto* typeCombo = qobject_cast<QComboBox*>(grid_->cellWidget(r, 1));
        QString type = typeCombo ? typeCombo->currentText() : "INT";
        bool nn = grid_->item(r, 2) && grid_->item(r, 2)->checkState() == Qt::Checked;
        bool pk = grid_->item(r, 3) && grid_->item(r, 3)->checkState() == Qt::Checked;
        bool ai = grid_->item(r, 4) && grid_->item(r, 4)->checkState() == Qt::Checked;
        QString def = grid_->item(r, 5) ? grid_->item(r, 5)->text().trimmed() : QString();
        QString d = QString("`%1` %2").arg(col, type);
        if (pk) d += " PRIMARY KEY";
        else if (nn) d += " NOT NULL";
        if (ai) d += " AUTO_INCREMENT";
        if (!def.isEmpty()) {
            bool num = false; def.toLongLong(&num);
            if (num) d += " DEFAULT " + def;
            else d += " DEFAULT '" + def.replace("'", "''") + "'";
        }
        defs.append(d);
    }
    return QString("CREATE TABLE %1 (\n  %2\n);").arg(tn, defs.join(",\n  "));
}

void CreateTableDialog::onAccept() {
    if (nameEdit_->text().trimmed().isEmpty()) {
        QMessageBox::warning(this, "新建表", "请填写表名。");
        return;
    }
    bool hasCol = false;
    for (int r = 0; r < grid_->rowCount(); r++)
        if (grid_->item(r, 0) && !grid_->item(r, 0)->text().trimmed().isEmpty()) { hasCol = true; break; }
    if (!hasCol) {
        QMessageBox::warning(this, "新建表", "请至少定义一列。");
        return;
    }
    accept();
}
