#include "dataview.h"
#include <QHeaderView>
#include <QVBoxLayout>
#include <QHBoxLayout>
#include <QPushButton>
#include <QMessageBox>
#include <QApplication>

// ===================== EditTableModel =====================
void EditTableModel::setData(const QList<ColumnInfo>& cols,
                             const QList<QList<QString>>& rows,
                             const QList<QList<bool>>& nulls) {
    beginResetModel();
    cols_ = cols;
    origRows_ = rows;
    origNulls_ = nulls;
    curRows_ = rows;
    curNulls_ = nulls;
    state_.clear();
    editedCols_.clear();
    for (int i = 0; i < rows.size(); i++) {
        state_.append(Unchanged);
        editedCols_.append(QSet<int>());
    }
    endResetModel();
}

void EditTableModel::clearModel() {
    beginResetModel();
    cols_.clear(); curRows_.clear(); origRows_.clear();
    curNulls_.clear(); origNulls_.clear(); state_.clear(); editedCols_.clear();
    endResetModel();
}

QVariant EditTableModel::data(const QModelIndex& idx, int role) const {
    if (!idx.isValid()) return QVariant();
    int r = idx.row(), c = idx.column();
    if (r < 0 || r >= curRows_.size()) return QVariant();
    if (c == 0) { // 行号列
        if (role == Qt::DisplayRole) {
            if (state_[r] == Deleted) return QString("\u2717 %1").arg(r + 1);
            return r + 1;
        }
        if (role == Qt::TextAlignmentRole) return QVariant(Qt::AlignRight | Qt::AlignVCenter);
        return QVariant();
    }
    int col = c - 1;
    if (col < 0 || col >= curRows_[r].size()) return QVariant();
    bool isn = curNulls_[r][col];
    if (role == Qt::DisplayRole) return isn ? QString("NULL") : curRows_[r][col];
    if (role == Qt::ForegroundRole) {
        if (state_[r] == Deleted) return QColor(0xC0, 0x39, 0x2B);
        if (state_[r] == Added) return QColor(0x1E, 0x8A, 0x3C);
        if (isn) return QColor(0x99, 0x99, 0x99);
        return QVariant();
    }
    if (role == Qt::FontRole) {
        QFont f;
        if (isn) f.setItalic(true);
        if (state_[r] == Added) f.setBold(true);
        return f;
    }
    if (role == Qt::TextAlignmentRole) {
        bool ok = false;
        if (!isn) curRows_[r][col].toLongLong(&ok);
        return ok ? QVariant(Qt::AlignRight | Qt::AlignVCenter) : QVariant(Qt::AlignLeft | Qt::AlignVCenter);
    }
    return QVariant();
}

QVariant EditTableModel::headerData(int s, Qt::Orientation o, int role) const {
    if (role == Qt::DisplayRole) {
        if (o == Qt::Horizontal) {
            if (s == 0) return QString("#");
            return s - 1 < cols_.size() ? cols_[s - 1].name : QVariant();
        }
        return s + 1;
    }
    if (role == Qt::TextAlignmentRole && o == Qt::Horizontal) return Qt::AlignLeft;
    return QVariant();
}

Qt::ItemFlags EditTableModel::flags(const QModelIndex& idx) const {
    if (!idx.isValid()) return Qt::NoItemFlags;
    if (idx.column() == 0) return Qt::ItemIsEnabled | Qt::ItemIsSelectable;
    return Qt::ItemIsEnabled | Qt::ItemIsSelectable | Qt::ItemIsEditable;
}

bool EditTableModel::setData(const QModelIndex& idx, const QVariant& v, int role) {
    if (role != Qt::EditRole || !idx.isValid() || idx.column() == 0) return false;
    int r = idx.row(), col = idx.column() - 1;
    if (r < 0 || r >= curRows_.size() || col < 0 || col >= curRows_[r].size()) return false;
    QString s = v.toString();
    bool isn = v.isNull() || s.trimmed().isEmpty();
    curRows_[r][col] = isn ? QString() : s;
    curNulls_[r][col] = isn;
    if (state_[r] != Added) {
        state_[r] = Edited;
        editedCols_[r].insert(col);
    }
    emit dataChanged(idx, idx);
    return true;
}

bool EditTableModel::hasChanges() const {
    for (int r = 0; r < state_.size(); r++) {
        if (state_[r] != Unchanged) return true;
        if (!editedCols_[r].isEmpty()) return true;
    }
    return false;
}

void EditTableModel::addEmptyRow() {
    beginInsertRows(QModelIndex(), curRows_.size(), curRows_.size());
    QList<QString> row; QList<bool> nulls;
    for (int i = 0; i < cols_.size(); i++) { row.append(QString()); nulls.append(true); }
    curRows_.append(row);
    curNulls_.append(nulls);
    state_.append(Added);
    editedCols_.append(QSet<int>());
    origRows_.append(row);   // 占位，提交时不会用到
    origNulls_.append(nulls);
    endInsertRows();
}

void EditTableModel::deleteRow(int r) {
    if (r < 0 || r >= state_.size()) return;
    if (state_[r] == Added) {
        beginRemoveRows(QModelIndex(), r, r);
        curRows_.removeAt(r); curNulls_.removeAt(r);
        state_.removeAt(r); editedCols_.removeAt(r);
        origRows_.removeAt(r); origNulls_.removeAt(r);
        endRemoveRows();
        return;
    }
    state_[r] = Deleted;
    QModelIndex tl = index(r, 0), br = index(r, cols_.size());
    emit dataChanged(tl, br);
}

void EditTableModel::revertRow(int r) {
    if (r < 0 || r >= state_.size()) return;
    if (state_[r] == Added) { deleteRow(r); return; }
    state_[r] = Unchanged;
    editedCols_[r].clear();
    curRows_[r] = origRows_[r];
    curNulls_[r] = origNulls_[r];
    QModelIndex tl = index(r, 0), br = index(r, cols_.size());
    emit dataChanged(tl, br);
}

void EditTableModel::revertAll() {
    beginResetModel();
    curRows_ = origRows_;
    curNulls_ = origNulls_;
    state_.clear(); editedCols_.clear();
    for (int i = 0; i < curRows_.size(); i++) { state_.append(Unchanged); editedCols_.append(QSet<int>()); }
    endResetModel();
}

// ===================== DataView =====================
DataView::DataView(QWidget* parent) : QWidget(parent) {
    auto* vbox = new QVBoxLayout(this);
    vbox->setContentsMargins(0, 0, 0, 0);

    bar_ = new QToolBar(this);
    auto* aRefresh = bar_->addAction(QIcon::fromTheme("view-refresh"), "刷新");
    auto* aAdd = bar_->addAction("+ 新增行");
    auto* aDel = bar_->addAction("- 删除所选行");
    auto* aCommit = bar_->addAction("提交");
    auto* aRoll = bar_->addAction("回滚");
    bar_->addSeparator();
    bar_->addWidget(new QLabel(" 行数上限:", this));
    limitSpin_ = new QSpinBox(this);
    limitSpin_->setRange(10, 100000);
    limitSpin_->setValue(500);
    limitSpin_->setSingleStep(100);
    bar_->addWidget(limitSpin_);
    bar_->addSeparator();
    statusLabel_ = new QLabel("未加载", this);
    bar_->addWidget(statusLabel_);

    view_ = new QTableView(this);
    model_ = new EditTableModel(this);
    view_->setModel(model_);
    view_->setAlternatingRowColors(true);
    view_->setSelectionBehavior(QAbstractItemView::SelectRows);
    view_->setSelectionMode(QAbstractItemView::ExtendedSelection);
    view_->verticalHeader()->setVisible(false);
    view_->horizontalHeader()->setStretchLastSection(false);
    view_->horizontalHeader()->setSectionResizeMode(QHeaderView::ResizeToContents);
    view_->setShowGrid(true);

    vbox->addWidget(bar_);
    vbox->addWidget(view_);

    connect(aRefresh, &QAction::triggered, this, [this]() { reload(); });
    connect(aAdd, &QAction::triggered, this, [this]() { model_->addEmptyRow(); statusLabel_->setText("已新增 1 行（未提交）"); });
    connect(aDel, &QAction::triggered, this, [this]() {
        QModelIndexList sel = view_->selectionModel()->selectedRows();
        for (const QModelIndex& i : sel) model_->deleteRow(i.row());
        statusLabel_->setText(sel.isEmpty() ? "请先选中要删除的行" : QString("已标记删除 %1 行（未提交）").arg(sel.size()));
    });
    connect(aCommit, &QAction::triggered, this, &DataView::buildAndRunCommit);
    connect(aRoll, &QAction::triggered, this, [this]() { model_->revertAll(); statusLabel_->setText("已回滚全部改动"); });
}

void DataView::openTable(const QString& db, const QString& table, int limit) {
    db_ = db; table_ = table;
    limitSpin_->setValue(limit);
    setWindowTitle(QString("数据: %1.%2").arg(db, table));
    reload();
}

void DataView::reload() {
    if (db_.isEmpty() || table_.isEmpty()) return;
    mode_ = Loading;
    loadStep_ = 0;
    statusLabel_->setText("加载中…");
    emit needExecute(QString("SHOW FULL COLUMNS FROM `%1` FROM `%2`").arg(table_, db_));
}

QString DataView::quoteVal(const QString& typeName, const QString& val, bool isNull) const {
    if (isNull) return "NULL";
    QString u = typeName.toUpper();
    bool numeric = u.contains("INT") || u.contains("BIGINT") || u.contains("DOUBLE") ||
                   u.contains("FLOAT") || u.contains("DECIMAL") || u.contains("NUMERIC") || u.contains("REAL");
    if (numeric) {
        bool ok = false;
        val.toLongLong(&ok);
        if (!ok) val.toDouble(&ok);
        return ok ? val : "0";
    }
    QString s = val;
    s.replace("'", "''");
    return "'" + s + "'";
}

QString DataView::whereClause(const QList<QString>& origRow, const QList<bool>& origNull) const {
    QString tn = QString("`%1`.`%2`").arg(db_, table_);
    QList<int> pks;
    for (int i = 0; i < cols_.size(); i++) if (cols_[i].flags & 2) pks.append(i);
    QStringList conds;
    if (!pks.isEmpty()) {
        for (int i : pks)
            conds.append(QString("`%1`=%2").arg(cols_[i].name, quoteVal(cols_[i].typeName, origRow.value(i), origNull.value(i))));
    } else {
        for (int i = 0; i < cols_.size(); i++) {
            if (origNull.value(i)) conds.append(QString("`%1` IS NULL").arg(cols_[i].name));
            else conds.append(QString("`%1`=%2").arg(cols_[i].name, quoteVal(cols_[i].typeName, origRow.value(i), origNull.value(i))));
        }
    }
    return " WHERE " + conds.join(" AND ");
}

QStringList DataView::buildCommitSql() const {
    QStringList out;
    QString tn = QString("`%1`.`%2`").arg(db_, table_);
    const int n = model_->curRows().size();
    for (int r = 0; r < n; r++) {
        int st = model_->rowState(r);
        const QList<QString>& row = model_->curRows()[r];
        const QList<bool>& nulls = model_->curNulls()[r];
        if (st == EditTableModel::Added) {
            QStringList cols, vals;
            for (int c = 0; c < cols_.size(); c++) {
                cols.append("`" + cols_[c].name + "`");
                vals.append(quoteVal(cols_[c].typeName, row.value(c), nulls.value(c)));
            }
            out.append(QString("INSERT INTO %1 (%2) VALUES (%3)").arg(tn, cols.join(","), vals.join(",")));
        } else if (st == EditTableModel::Deleted) {
            out.append("DELETE FROM " + tn + whereClause(model_->origRows()[r], model_->origNulls()[r]));
        } else if (!model_->editedCols()[r].isEmpty()) {
            QStringList sets;
            for (int c : model_->editedCols()[r])
                sets.append(QString("`%1`=%2").arg(cols_[c].name, quoteVal(cols_[c].typeName, row.value(c), nulls.value(c))));
            out.append("UPDATE " + tn + " SET " + sets.join(",") + whereClause(model_->origRows()[r], model_->origNulls()[r]));
        }
    }
    return out;
}

void DataView::buildAndRunCommit() {
    if (!model_->hasChanges()) { statusLabel_->setText("没有改动需要提交"); return; }
    QStringList sql = buildCommitSql();
    if (sql.isEmpty()) { statusLabel_->setText("没有改动需要提交"); return; }
    if (QMessageBox::question(this, "提交改动",
            QString("将执行 %1 条语句提交改动，确定？\n\n%2").arg(sql.size()).arg(sql.join("\n"))) != QMessageBox::Yes)
        return;
    mode_ = Committing;
    commitQueue_ = sql;
    statusLabel_->setText(QString("提交中（%1 条）…").arg(sql.size()));
    runNextCommit();
}

void DataView::runNextCommit() {
    if (commitQueue_.isEmpty()) { reload(); return; }
    emit needExecute(commitQueue_.takeFirst());
}

void DataView::onStatementResult(const SqlResult& res) {
    if (mode_ == Loading) {
        if (res.kind == SqlResult::Error) {
            statusLabel_->setText(QString("加载失败 [errno %1] %2").arg(res.errorCode).arg(res.errorMessage));
            mode_ = Idle;
            return;
        }
        if (loadStep_ == 0) {
            cols_.clear();
            if (res.kind == SqlResult::ResultSet) {
                for (const auto& row : res.rows) {
                    ColumnInfo ci;
                    if (row.size() > 0) ci.name = row[0];
                    if (row.size() > 1) ci.typeName = row[1];
                    if (row.size() > 4) {
                        QString key = row[4].toUpper();
                        if (key == "PRI") ci.flags |= 2;
                        else if (key == "UNI") ci.flags |= 4;
                    }
                    if (row.size() > 3 && row[3].toUpper() == "NO") ci.flags |= 1;
                    cols_.append(ci);
                }
            }
            loadStep_ = 1;
            emit needExecute(QString("SELECT * FROM `%1`.`%2` LIMIT %3").arg(db_, table_).arg(limitSpin_->value()));
        } else {
            model_->setData(cols_, res.rows, res.nulls);
            view_->resizeColumnsToContents();
            if (cols_.size() > 0) view_->horizontalHeader()->setSectionResizeMode(QHeaderView::Interactive);
            statusLabel_->setText(QString("已加载 %1 行 × %2 列").arg(res.rows.size()).arg(cols_.size()));
            mode_ = Idle;
        }
        return;
    }
    if (mode_ == Committing) {
        if (res.kind == SqlResult::Error) {
            statusLabel_->setText(QString("提交失败 [errno %1] %2（已应用的语句不会回滚）").arg(res.errorCode).arg(res.errorMessage));
            mode_ = Idle;
            return;
        }
        runNextCommit();
    }
}
