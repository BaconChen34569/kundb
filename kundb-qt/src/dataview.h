#pragma once
#include <QWidget>
#include <QTableView>
#include <QAbstractTableModel>
#include <QToolBar>
#include <QSpinBox>
#include <QLabel>
#include <QSet>
#include "sqlresult.h"

// 可编辑结果集模型：在只读 ResultTableModel 基础上增加单元格编辑、
// 新增行 / 删除行 / 回滚，并跟踪每一行的改动状态，供 DataView 生成差异 SQL。
class EditTableModel : public QAbstractTableModel {
    Q_OBJECT
public:
    enum RowState { Unchanged = 0, Added = 1, Deleted = 2, Edited = 3 };

    explicit EditTableModel(QObject* parent = nullptr) : QAbstractTableModel(parent) {}

    void setData(const QList<ColumnInfo>& cols,
                 const QList<QList<QString>>& rows,
                 const QList<QList<bool>>& nulls);
    void clearModel();

    int rowCount(const QModelIndex& p = QModelIndex()) const override { return p.isValid() ? 0 : curRows_.size(); }
    int columnCount(const QModelIndex& p = QModelIndex()) const override { return p.isValid() ? 0 : (cols_.isEmpty() ? 0 : cols_.size() + 1); }
    QVariant data(const QModelIndex& idx, int role = Qt::DisplayRole) const override;
    QVariant headerData(int s, Qt::Orientation o, int role = Qt::DisplayRole) const override;
    Qt::ItemFlags flags(const QModelIndex& idx) const override;
    bool setData(const QModelIndex& idx, const QVariant& v, int role = Qt::EditRole) override;

    bool isPk(int col) const { return col >= 0 && col < cols_.size() && (cols_[col].flags & 2); }
    QList<ColumnInfo> columns() const { return cols_; }
    int rowState(int r) const { return (r >= 0 && r < state_.size()) ? state_[r] : Unchanged; }
    const QList<QList<QString>>& curRows() const { return curRows_; }
    const QList<QList<bool>>& curNulls() const { return curNulls_; }
    const QList<QList<QString>>& origRows() const { return origRows_; }
    const QList<QList<bool>>& origNulls() const { return origNulls_; }
    const QList<QSet<int>>& editedCols() const { return editedCols_; }
    bool hasChanges() const;

    void addEmptyRow();
    void deleteRow(int r);
    void revertRow(int r);
    void revertAll();

private:
    QList<ColumnInfo> cols_;
    QList<QList<QString>> curRows_, origRows_;
    QList<QList<bool>> curNulls_, origNulls_;
    QList<int> state_;                 // RowState per row
    QList<QSet<int>> editedCols_;      // 每个被改动的「非新增」行：被编辑过的列
};

// 「表数据编辑器」：可视化浏览 / 编辑表数据，生成并提交
// INSERT / UPDATE / DELETE，无需手写 SQL。
class DataView : public QWidget {
    Q_OBJECT
public:
    explicit DataView(QWidget* parent = nullptr);

    void openTable(const QString& db, const QString& table, int limit = 500);

signals:
    // 请求 MainWindow 代为执行（token 由 MainWindow 统一分配，避免与控制台撞号）
    void needExecute(const QString& sql);

public slots:
    void onStatementResult(const SqlResult& res);

private:
    void runNextCommit();
    void reload();
    void buildAndRunCommit();
    QStringList buildCommitSql() const;
    QString quoteVal(const QString& typeName, const QString& val, bool isNull) const;
    QString whereClause(const QList<QString>& origRow, const QList<bool>& origNull) const;

    EditTableModel* model_ = nullptr;
    QTableView* view_ = nullptr;
    QToolBar* bar_ = nullptr;
    QSpinBox* limitSpin_ = nullptr;
    QLabel* statusLabel_ = nullptr;

    QString db_, table_;
    QList<ColumnInfo> cols_;
    enum Mode { Idle, Loading, Committing } mode_ = Idle;
    int loadStep_ = 0;          // 0 = SHOW FULL COLUMNS, 1 = SELECT
    QStringList commitQueue_;
};
