#include "resultgrid.h"
#include <QHeaderView>
#include <QFont>

void ResultTableModel::setResult(const SqlResult& res) {
    beginResetModel();
    headers_.clear();
    headers_.append("#");
    for (const auto& c : res.columns) headers_.append(c.name);
    rows_ = res.rows;
    nulls_ = res.nulls;
    endResetModel();
}

QVariant ResultTableModel::data(const QModelIndex& idx, int role) const {
    if (!idx.isValid()) return QVariant();
    int r = idx.row(), c = idx.column();
    if (c == 0) { // 行号列
        if (role == Qt::DisplayRole) return r + 1;
        if (role == Qt::TextAlignmentRole) return Qt::AlignRight;
        return QVariant();
    }
    int col = c - 1;
    if (r < 0 || r >= rows_.size() || col < 0 || col >= rows_[r].size()) return QVariant();
    bool isn = nulls_[r][col];
    if (role == Qt::DisplayRole) return isn ? QString("NULL") : rows_[r][col];
    if (role == Qt::ForegroundRole && isn) return QColor(0x99, 0x99, 0x99);
    if (role == Qt::FontRole && isn) { QFont f; f.setItalic(true); return f; }
    if (role == Qt::TextAlignmentRole) {
        // 数值右对齐
        bool ok = false;
        if (!isn) rows_[r][col].toLongLong(&ok);
        return ok ? QVariant(Qt::AlignRight | Qt::AlignVCenter) : QVariant(Qt::AlignLeft | Qt::AlignVCenter);
    }
    return QVariant();
}

QVariant ResultTableModel::headerData(int section, Qt::Orientation o, int role) const {
    if (role == Qt::DisplayRole) {
        if (o == Qt::Horizontal) return section < headers_.size() ? headers_[section] : QVariant();
        return section + 1;
    }
    if (role == Qt::TextAlignmentRole && o == Qt::Horizontal) return Qt::AlignLeft;
    return QVariant();
}

ResultGrid::ResultGrid(QWidget* parent) : QTableView(parent) {
    auto* m = new ResultTableModel(this);
    setModel(m);
    setAlternatingRowColors(true);
    setSelectionBehavior(SelectRows);
    setSelectionMode(ExtendedSelection);
    verticalHeader()->setVisible(false);
    horizontalHeader()->setStretchLastSection(false);
    horizontalHeader()->setSectionResizeMode(QHeaderView::ResizeToContents);
    setShowGrid(true);
    setWordWrap(false);
}

void ResultGrid::showResult(const SqlResult& res) {
    auto* m = qobject_cast<ResultTableModel*>(model());
    if (m) m->setResult(res);
    horizontalHeader()->setSectionResizeMode(0, QHeaderView::ResizeToContents);
    resizeColumnsToContents();
    if (res.columns.size() > 0) horizontalHeader()->setSectionResizeMode(QHeaderView::Interactive);
}
