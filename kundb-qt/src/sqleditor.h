#pragma once
#include <QPlainTextEdit>
#include <QSyntaxHighlighter>
#include <QRegularExpression>
#include <QVector>
#include <QStringList>

class QCompleter;

// SQL 语法高亮器（关键词 / 字符串 / 数字 / 注释 / 反引号标识符）
class SqlHighlighter : public QSyntaxHighlighter {
    Q_OBJECT
public:
    explicit SqlHighlighter(QTextDocument* doc);
protected:
    void highlightBlock(const QString& text) override;
private:
    struct Rule { QRegularExpression re; QTextCharFormat fmt; };
    QVector<Rule> rules_;
    QTextCharFormat commentFmt_;
};

// SQL 控制台编辑框（带关键词 + 元数据补全）
class SqlEditor : public QPlainTextEdit {
    Q_OBJECT
public:
    explicit SqlEditor(QWidget* parent = nullptr);

    // 取当前要执行的 SQL：有选中文本则用选中；否则返回光标所在的那条语句。
    QString currentStatement() const;
    // 全部语句（按 ; 切分，去除空语句）
    QStringList allStatements() const;

    // 更新补全词表（静态关键词 ∪ 传入的库/表/视图/列名）
    void setCompletionWords(const QStringList& words);

protected:
    void keyPressEvent(QKeyEvent* e) override;

signals:
    void executeRequested(); // Ctrl+Enter

private:
    QString wordBeforeCursor() const;  // 光标前的词片段（不含 . 之前的部分）
    void showCompletionPopup();
    void insertCompletion(const QString& text);

    SqlHighlighter* hl_;
    QCompleter* completer_ = nullptr;
    QStringList keywords_;
    QStringList fullWords_;   // 补全词表（canonical 大小写，弹窗时按前缀大小写变换）
};
