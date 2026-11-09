#pragma once
#include <QTableView>
#include <QAbstractTableModel>
#include "sqlresult.h"

// 结果集表格模型：首列为行号，其余为结果列；NULL 以斜体灰显示。
class ResultTableModel : public QAbstractTableModel {
    Q_OBJECT
public:
    explicit ResultTableModel(QObject* parent = nullptr) : QAbstractTableModel(parent) {}
    void setResult(const SqlResult& res);

    int rowCount(const QModelIndex& p = QModelIndex()) const override { return p.isValid() ? 0 : rows_.size(); }
    int columnCount(const QModelIndex& p = QModelIndex()) const override { return p.isValid() ? 0 : headers_.size(); }
    QVariant data(const QModelIndex& idx, int role = Qt::DisplayRole) const override;
    QVariant headerData(int section, Qt::Orientation o, int role = Qt::DisplayRole) const override;

    void setCurrentDatabase(const QString& db) { db_ = db; }
private:
    QStringList headers_;
    QList<QList<QString>> rows_;
    QList<QList<bool>> nulls_;
    QString db_;
};

// 结果网格视图
class ResultGrid : public QTableView {
    Q_OBJECT
public:
    explicit ResultGrid(QWidget* parent = nullptr);
    void showResult(const SqlResult& res);
};
