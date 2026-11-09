#include "sqleditor.h"
#include <QKeyEvent>
#include <QTextBlock>
#include <QApplication>
#include <QCompleter>
#include <QAbstractItemView>
#include <QListView>
#include <QStringListModel>
#include <QSet>

// ---- 语句切分（尊重引号/反引号/注释）----
namespace {
    // 把整段文本按顶层 ';' 切成多条语句（忽略字符串/反引号/注释内的 ';'）
    QStringList splitStatements(const QString& text) {
        QStringList out;
        QString cur;
        bool inS = false, inD = false, inBt = false;
        bool lineComment = false, blockComment = false;
        QChar strQuote;
        for (int i = 0; i < text.size(); i++) {
            QChar c = text[i];
            QChar n = (i + 1 < text.size()) ? text[i + 1] : QChar();
            if (lineComment) {
                if (c == '\n') lineComment = false;
                cur.append(c); continue;
            }
            if (blockComment) {
                if (c == '*' && n == '/') { blockComment = false; i++; cur.append(c); cur.append(n); continue; }
                cur.append(c); continue;
            }
            if (inS || inD) {
                cur.append(c);
                if (c == '\\' && i + 1 < text.size()) { cur.append(text[++i]); continue; } // 转义
                if (c == strQuote && !(i > 0 && text[i - 1] == '\\')) { inS = inD = false; }
                continue;
            }
            if (inBt) {
                cur.append(c);
                if (c == '`') inBt = false;
                continue;
            }
            if (c == '-' && n == '-') { lineComment = true; cur.append(c); continue; }
            if (c == '#') { lineComment = true; cur.append(c); continue; }
            if (c == '/' && n == '*') { blockComment = true; cur.append(c); continue; }
            if (c == '\'' || c == '"') { inS = inD = true; strQuote = c; cur.append(c); continue; }
            if (c == '`') { inBt = true; cur.append(c); continue; }
            if (c == ';') {
                QString s = cur.trimmed();
                if (!s.isEmpty()) out.append(s);
                cur.clear();
                continue;
            }
            cur.append(c);
        }
        QString s = cur.trimmed();
        if (!s.isEmpty()) out.append(s);
        return out;
    }
}

SqlHighlighter::SqlHighlighter(QTextDocument* doc) : QSyntaxHighlighter(doc) {
    QTextCharFormat kw;
    kw.setForeground(QColor(0x00, 0x73, 0xC8)); // 蓝
    kw.setFontWeight(QFont::Bold);
    const QStringList keywords = {
        "SELECT","INSERT","UPDATE","DELETE","CREATE","DROP","ALTER","TRUNCATE","RENAME",
        "FROM","WHERE","AND","OR","NOT","NULL","IS","IN","LIKE","BETWEEN","EXISTS",
        "ORDER","GROUP","BY","HAVING","LIMIT","OFFSET","AS","ON","USING","JOIN","INNER",
        "LEFT","RIGHT","OUTER","FULL","CROSS","UNION","ALL","DISTINCT","SET","VALUES",
        "INTO","TABLE","DATABASE","SCHEMA","VIEW","INDEX","INDEXES","COLUMN","COLUMNS",
        "PRIMARY","KEY","FOREIGN","REFERENCES","UNIQUE","DEFAULT","NOT","NULL","AUTO_INCREMENT",
        "COMMENT","ENGINE","CHARSET","COLLATE","USE","SHOW","DESCRIBE","DESC","EXPLAIN","ANALYZE",
        "TABLES","FULL","DATABASES","SCHEMAS","FIELDS","PROCESSLIST","DATABASE","GRANT","REVOKE",
        "USER","USERS","BEGIN","COMMIT","ROLLBACK","SAVEPOINT","TRANSACTION","ISOLATION","LEVEL",
        "CASCADE","RESTRICT","CHANGE","MODIFY","ADD","IF","EXISTS","ASC","DESC","COUNT","SUM",
        "AVG","MIN","MAX","CASE","WHEN","THEN","ELSE","END","CURRENT","TIMESTAMP"
    };
    for (const QString& k : keywords) {
        Rule r;
        r.re = QRegularExpression("\\b" + QRegularExpression::escape(k) + "\\b", QRegularExpression::CaseInsensitiveOption);
        r.fmt = kw;
        rules_.append(r);
    }
    QTextCharFormat str;
    str.setForeground(QColor(0x00, 0x88, 0x00)); // 绿
    rules_.append({QRegularExpression("'(?:[^'\\\\]|\\\\.)*'"), str});
    rules_.append({QRegularExpression("\"(?:[^\"\\\\]|\\\\.)*\""), str});

    QTextCharFormat num;
    num.setForeground(QColor(0xC8, 0x00, 0x00)); // 红
    rules_.append({QRegularExpression("\\b\\d+(\\.\\d+)?\\b"), num});

    QTextCharFormat bt;
    bt.setForeground(QColor(0x8B, 0x45, 0x13)); // 棕（反引号标识符）
    rules_.append({QRegularExpression("`[^`]*`"), bt});

    commentFmt_.setForeground(QColor(0x88, 0x88, 0x88)); // 灰
    commentFmt_.setFontItalic(true);
}

void SqlHighlighter::highlightBlock(const QString& text) {
    for (const Rule& r : rules_) {
        QRegularExpressionMatchIterator it = r.re.globalMatch(text);
        while (it.hasNext()) {
            QRegularExpressionMatch m = it.next();
            setFormat(m.capturedStart(), m.capturedLength(), r.fmt);
        }
    }
    // 注释（行/块）—简单扫描
    int i = 0;
    while (i < text.size()) {
        if (text[i] == '-' && i + 1 < text.size() && text[i + 1] == '-') {
            setFormat(i, text.size() - i, commentFmt_); break;
        }
        if (text[i] == '#') { setFormat(i, text.size() - i, commentFmt_); break; }
        if (text[i] == '/' && i + 1 < text.size() && text[i + 1] == '*') {
            int end = text.indexOf("*/", i + 2);
            int len = (end < 0 ? text.size() : end + 2) - i;
            setFormat(i, len, commentFmt_); i += len; continue;
        }
        i++;
    }
}

SqlEditor::SqlEditor(QWidget* parent) : QPlainTextEdit(parent) {
    setFont(QFont("Consolas", 11));
    setLineWrapMode(NoWrap);
    hl_ = new SqlHighlighter(document());
    setPlainText("-- 在此输入 SQL，Ctrl+Enter 执行当前语句 / 选中文本\n"
                  "-- 示例： SELECT * FROM school.student LIMIT 100;\n");

    // ---- 补全 ----
    keywords_ = {
        "SELECT","INSERT","UPDATE","DELETE","CREATE","DROP","ALTER","TRUNCATE",
        "FROM","WHERE","AND","OR","NOT","NULL","IS","IN","LIKE","BETWEEN","EXISTS",
        "ORDER","GROUP","BY","HAVING","LIMIT","OFFSET","AS","ON","USING","JOIN",
        "INNER","LEFT","RIGHT","OUTER","UNION","ALL","DISTINCT","SET","VALUES",
        "INTO","TABLE","DATABASE","SCHEMA","VIEW","INDEX","COLUMN","PRIMARY","KEY",
        "FOREIGN","REFERENCES","UNIQUE","DEFAULT","AUTO_INCREMENT","COMMENT","ENGINE",
        "USE","SHOW","DESCRIBE","DESC","ASC","EXPLAIN","ANALYZE","TABLES","FULL",
        "DATABASES","SCHEMAS","COLUMNS","FIELDS","BEGIN","COMMIT","ROLLBACK",
        "SAVEPOINT","TRANSACTION","ISOLATION","LEVEL","IF","ADD","CHANGE","MODIFY",
        "COUNT","SUM","AVG","MIN","MAX","CASE","WHEN","THEN","ELSE","END",
        "CURRENT_TIMESTAMP","VARCHAR","CHAR","TEXT","INT","BIGINT","DOUBLE","FLOAT",
        "DECIMAL","DATE","DATETIME","TIME","BOOLEAN","LOCK","READ","WRITE","NOWAIT"
    };
    fullWords_ = keywords_;
    completer_ = new QCompleter(fullWords_, this);
    completer_->setWidget(this);
    completer_->setCompletionMode(QCompleter::PopupCompletion);
    completer_->setCaseSensitivity(Qt::CaseInsensitive);
    completer_->setModelSorting(QCompleter::UnsortedModel);
    connect(completer_, qOverload<const QString&>(&QCompleter::activated),
            this, &SqlEditor::insertCompletion);
}

void SqlEditor::setCompletionWords(const QStringList& words) {
    QSet<QString> merged;
    for (const QString& k : keywords_) merged.insert(k);      // 保留 canonical 大写
    for (const QString& w : words) if (!w.isEmpty()) merged.insert(w);
    fullWords_ = merged.values();
    // 弹窗时再按前缀大小写重建模型，这里不 setModel
}

QString SqlEditor::wordBeforeCursor() const {
    QTextCursor c = textCursor();
    QString blockText = c.block().text();
    int pos = c.positionInBlock();
    int i = pos;
    while (i > 0) {
        QChar ch = blockText[i - 1];
        if (ch.isLetterOrNumber() || ch == '_' || ch == '.' || ch == '`') --i;
        else break;
    }
    QString seg = blockText.mid(i, pos - i);
    // 「school.stu」只补全最后一段
    int dot = qMax(seg.lastIndexOf('.'), seg.lastIndexOf('`'));
    if (dot >= 0) seg = seg.mid(dot + 1);
    return seg;
}

namespace {
// 按前缀的大小写风格变换补全词：前缀全小写 -> 全小写；全大写 -> 全大写；否则首字母大写
QString applyCase(const QString& word, const QString& prefix) {
    if (prefix.isEmpty()) return word;
    bool allUpper = true, allLower = true;
    for (QChar ch : prefix) {
        if (!ch.isLetter()) continue;
        if (ch.isLower()) allUpper = false;
        if (ch.isUpper()) allLower = false;
    }
    if (allUpper) return word.toUpper();
    if (allLower) return word.toLower();
    QString t = word;
    if (!t.isEmpty()) t[0] = t[0].toUpper();
    return t;
}
}

void SqlEditor::showCompletionPopup() {
    QString w = wordBeforeCursor();
    if (fullWords_.isEmpty()) {
        completer_->popup()->hide();
        return;
    }
    // 按前缀大小写重建候选（输入小写 -> 候选小写；输入大写 -> 候选大写）
    QStringList display;
    display.reserve(fullWords_.size());
    for (const QString& word : fullWords_) display.append(applyCase(word, w));
    completer_->setModel(new QStringListModel(display, completer_));
    completer_->setCompletionPrefix(w);
    if (completer_->completionCount() == 0) {
        completer_->popup()->hide();
        return;
    }
    QRect r = cursorRect();
    int rows = qMin(8, completer_->completionCount());
    int rowH = completer_->popup()->sizeHintForRow(0);
    if (rowH <= 0) rowH = fontMetrics().height() + 4;
    r.setHeight(rows * rowH + 4);
    r.setWidth(completer_->popup()->sizeHintForColumn(0) + 24);
    completer_->complete(r);
}

void SqlEditor::insertCompletion(const QString& text) {
    QTextCursor c = textCursor();
    int extra = wordBeforeCursor().length();
    for (int i = 0; i < extra; i++) c.deletePreviousChar();
    c.insertText(text);
    setTextCursor(c);
}

void SqlEditor::keyPressEvent(QKeyEvent* e) {
    if ((e->modifiers() & Qt::ControlModifier) && (e->key() == Qt::Key_Return || e->key() == Qt::Key_Enter)) {
        emit executeRequested();
        return;
    }
    // Ctrl+Space 强制弹补全
    if (completer_ && (e->modifiers() & Qt::ControlModifier) && e->key() == Qt::Key_Space) {
        showCompletionPopup();
        return;
    }
    // 补全弹窗打开时，方向键/回车/Tab 交给弹窗处理
    if (completer_ && completer_->popup() && completer_->popup()->isVisible()) {
        switch (e->key()) {
        case Qt::Key_Enter:
        case Qt::Key_Return:
        case Qt::Key_Escape:
        case Qt::Key_Tab:
        case Qt::Key_Backtab:
            e->ignore();
            return;
        default:
            break;
        }
    }
    QPlainTextEdit::keyPressEvent(e);
    // 输入字符或退格后刷新补全
    if (completer_) {
        QString t = e->text();
        bool wordy = (!t.isEmpty() && (t.at(0).isLetterOrNumber() || t.at(0) == '_' || t == QStringLiteral(".")));
        if (wordy || e->key() == Qt::Key_Backspace) showCompletionPopup();
        else completer_->popup()->hide();
    }
}

QString SqlEditor::currentStatement() const {
    QTextCursor cur = textCursor();
    if (cur.hasSelection()) return cur.selectedText().trimmed();
    // 取光标所在语句：用整体切分后找包含光标位置的语句
    int pos = cur.position();
    QStringList stmts = splitStatements(toPlainText());
    QString full = toPlainText();
    int acc = 0;
    for (const QString& s : stmts) {
        int idx = full.indexOf(s, acc);
        int end = idx + s.size();
        if (pos >= idx && pos <= end) return s;
        acc = (idx >= 0 ? idx + 1 : acc);
    }
    return stmts.isEmpty() ? QString() : stmts.last();
}

QStringList SqlEditor::allStatements() const {
    return splitStatements(toPlainText());
}
