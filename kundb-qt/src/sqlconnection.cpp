#include "sqlconnection.h"
#include <QElapsedTimer>
#include <QCryptographicHash>

// ---- MySQL 能力标志（仅声明我们用到的，与服务端 MysqlHandshake 对齐）----
static const quint32 CLIENT_LONG_PASSWORD    = 0x00000001;
static const quint32 CLIENT_PROTOCOL_41      = 0x00000200;
static const quint32 CLIENT_SECURE_CONNECTION= 0x00008000;
static const quint32 CLIENT_PLUGIN_AUTH      = 0x00080000;

static const int READ_TIMEOUT_MS = 15000;

// ===== 长度编码（Length-Encoded）辅助 =====
static qulonglong readLenencInt(const QByteArray& b, int& pos) {
    quint8 f = (quint8)b[pos++];
    if (f < 251) return f;
    if (f == 0xFC) {
        qulonglong v = (quint8)b[pos] | ((quint8)b[pos + 1] << 8);
        pos += 2; return v;
    }
    if (f == 0xFD) {
        qulonglong v = (quint8)b[pos] | ((quint8)b[pos + 1] << 8) | ((quint8)b[pos + 2] << 16);
        pos += 3; return v;
    }
    qulonglong v = 0;
    for (int i = 0; i < 8; i++) v |= ((qulonglong)(quint8)b[pos + i]) << (8 * i);
    pos += 8; return v;
}

static QString readLenencStr(const QByteArray& b, int& pos) {
    qulonglong n = readLenencInt(b, pos);
    QString s = QString::fromUtf8(b.mid(pos, (int)n));
    pos += (int)n;
    return s;
}

static int readInt16LE(const QByteArray& b, int& pos) {
    int v = (quint8)b[pos] | ((quint8)b[pos + 1] << 8);
    pos += 2; return v;
}
static int readInt32LE(const QByteArray& b, int& pos) {
    int v = (quint8)b[pos] | ((quint8)b[pos + 1] << 8) | ((quint8)b[pos + 2] << 16) | ((quint8)b[pos + 3] << 24);
    pos += 4; return v;
}

static QString typeNameOf(int type, int columnLength) {
    Q_UNUSED(columnLength);
    switch (type) {
        case 0:  return "DECIMAL";
        case 1:  return "TINYINT";
        case 2:  return "SMALLINT";
        case 3:  return "INT";
        case 4:  return "MEDIUMINT";
        case 5:  return "DOUBLE";
        case 6:  return "FLOAT";
        case 7:  return "NULL";
        case 8:  return "BIGINT";
        case 9:  return "BIGINT UNSIGNED";
        case 10: return "DATE";
        case 11: return "TIME";
        case 12: return "DATETIME";
        case 13: return "YEAR";
        case 14: return "NEWDATE";
        case 15: return "TIMESTAMP";
        case 16: return "BIGINT UNSIGNED";
        case 246: return "DECIMAL";
        case 247: return "ENUM";
        case 248: return "SET";
        case 249: return "TINY_BLOB";
        case 250: return "MEDIUM_BLOB";
        case 251: return "LONG_BLOB";
        case 252: return "BLOB";
        case 253: return "VARCHAR";
        case 254: return "CHAR";
        case 255: return "GEOMETRY";
        default:  return "TYPE(" + QString::number(type) + ")";
    }
}

// mysql_native_password 凭据：SHA1(SHA1(pwd)) XOR SHA1(scramble + SHA1(SHA1(pwd)))
static QByteArray nativeToken(const QByteArray& scramble, const QString& pwd) {
    QByteArray p = pwd.toUtf8();
    QByteArray s1 = QCryptographicHash::hash(p, QCryptographicHash::Sha1);
    QByteArray s2 = QCryptographicHash::hash(s1, QCryptographicHash::Sha1);
    QByteArray concat = scramble + s2;
    QByteArray raw = QCryptographicHash::hash(concat, QCryptographicHash::Sha1);
    QByteArray out(s1.size(), 0);
    for (int i = 0; i < s1.size(); i++) out[i] = (char)((quint8)s1[i] ^ (quint8)raw[i]);
    return out;
}

// ===== SqlConnection =====
SqlConnection::SqlConnection() = default;

SqlConnection::~SqlConnection() {
    disconnect();
}

bool SqlConnection::isOpen() const { return open_ && sock_ && sock_->state() == QAbstractSocket::ConnectedState; }

QString SqlConnection::serverVersion() const { return serverVersion_; }

void SqlConnection::markClosed() {
    open_ = false;
    if (sock_) { sock_->close(); delete sock_; sock_ = nullptr; }
}

// 阻塞读恰好 n 字节
bool SqlConnection::readPacket(QByteArray& payload, QString* err) {
    char hdr[4];
    int got = 0;
    while (got < 4) {
        if (sock_->state() != QAbstractSocket::ConnectedState) { if (err) *err = "连接已断开"; return false; }
        if (sock_->bytesAvailable() == 0 && !sock_->waitForReadyRead(READ_TIMEOUT_MS)) {
            if (err) *err = "读取超时（握手/响应）"; return false;
        }
        qint64 r = sock_->read(hdr + got, 4 - got);
        if (r < 0) { if (err) *err = "读取错误"; return false; }
        if (r == 0) { if (err) *err = "连接关闭"; return false; }
        got += (int)r;
    }
    quint32 len = (quint8)hdr[0] | ((quint8)hdr[1] << 8) | ((quint8)hdr[2] << 16);
    // seq = (quint8)hdr[3];  // 读取侧不校验序号
    QByteArray data;
    data.resize((int)len);
    int got2 = 0;
    while (got2 < (int)len) {
        if (sock_->state() != QAbstractSocket::ConnectedState) { if (err) *err = "连接已断开"; return false; }
        if (sock_->bytesAvailable() == 0 && !sock_->waitForReadyRead(READ_TIMEOUT_MS)) {
            if (err) *err = "读取超时（包体）"; return false;
        }
        qint64 r = sock_->read(data.data() + got2, (int)len - got2);
        if (r < 0) { if (err) *err = "读取错误"; return false; }
        if (r == 0) { if (err) *err = "连接关闭"; return false; }
        got2 += (int)r;
    }
    payload = data;
    return true;
}

bool SqlConnection::writePacket(const QByteArray& payload, QString* err) {
    QByteArray hdr;
    hdr.resize(4);
    quint32 len = (quint32)payload.size();
    hdr[0] = (char)(len & 0xFF);
    hdr[1] = (char)((len >> 8) & 0xFF);
    hdr[2] = (char)((len >> 16) & 0xFF);
    hdr[3] = (char)(seq_ & 0xFF);
    seq_ = (seq_ + 1) & 0xFF;
    sock_->write(hdr);
    sock_->write(payload);
    if (!sock_->waitForBytesWritten(READ_TIMEOUT_MS)) {
        if (err) *err = "写入超时";
        return false;
    }
    return true;
}

bool SqlConnection::doHandshake(QString* err) {
    QByteArray p;
    if (!readPacket(p, err)) return false;
    if ((quint8)p[0] != 0x0A) { if (err) *err = "首包不是 MySQL 握手包(Protocol v10)"; return false; }
    int pos = 1;
    int end = pos;
    while (end < p.size() && p[end] != 0) end++;
    serverVersion_ = QString::fromUtf8(p.mid(pos, end - pos));
    pos = end + 1;
    pos += 4;                       // connection id
    QByteArray sp1 = p.mid(pos, 8); pos += 8;
    pos += 1;                       // filler
    pos += 2;                       // capability 低 16 位
    pos += 1;                       // charset
    pos += 2;                       // status flags
    pos += 2;                       // capability 高 16 位
    pos += 1;                       // auth-plugin-data 长度
    pos += 10;                      // reserved
    QByteArray sp2 = p.mid(pos, 12); pos += 12;
    if (pos < p.size() && p[pos] == 0) pos++; // part2 的 NUL 结尾
    // auth plugin name（NUL 结尾）——本客户端只认 mysql_native_password
    int aend = pos;
    while (aend < p.size() && p[aend] != 0) aend++;
    QString plugin = QString::fromUtf8(p.mid(pos, aend - pos));
    if (plugin != "mysql_native_password") {
        if (err) *err = "不支持的鉴权插件: " + plugin;
        return false;
    }
    scramble_ = sp1 + sp2; // 20 字节
    return true;
}

bool SqlConnection::sendAuth(const QString& user, const QString& password, QString* err) {
    QByteArray r;
    quint32 caps = CLIENT_LONG_PASSWORD | CLIENT_PROTOCOL_41 | CLIENT_SECURE_CONNECTION | CLIENT_PLUGIN_AUTH;
    r.append((char)(caps & 0xFF)); r.append((char)((caps >> 8) & 0xFF));
    r.append((char)((caps >> 16) & 0xFF)); r.append((char)((caps >> 24) & 0xFF));
    r.append((char)0x00); r.append((char)0x00); r.append((char)0x00); r.append((char)0x01); // max packet = 16MB
    r.append((char)45); // charset = utf8mb4
    for (int i = 0; i < 23; i++) r.append((char)0); // reserved 23
    QByteArray ub = user.toUtf8();
    r.append(ub); r.append((char)0); // username + NUL
    QByteArray token = nativeToken(scramble_, password);
    r.append((char)(token.size() & 0xFF)); // auth-response 长度（短形式）
    r.append(token);
    QByteArray plugin = "mysql_native_password";
    r.append(plugin); r.append((char)0); // auth plugin name + NUL

    seq_ = 1;
    if (!writePacket(r, err)) return false;
    QByteArray ap;
    if (!readPacket(ap, err)) return false;
    if ((quint8)ap[0] == 0xFF) {
        int code = (quint8)ap[1] | ((quint8)ap[2] << 8);
        QString msg = QString::fromUtf8(ap.mid(3));
        if (err) *err = QString("认证失败 [MySQL errno %1] %2").arg(code).arg(msg);
        return false;
    }
    if ((quint8)ap[0] != 0x00) {
        if (err) *err = QString("登录未收到 OK 包 (首字节 0x%1)").arg((quint8)ap[0], 0, 16);
        return false;
    }
    return true;
}

bool SqlConnection::connectTo(const QString& host, int port, const QString& user,
                              const QString& password, const QString& database, QString* outError) {
    disconnect();
    sock_ = new QTcpSocket();
    sock_->connectToHost(host, (quint16)port);
    if (!sock_->waitForConnected(8000)) {
        if (outError) *outError = "无法连接到 " + host + ":" + QString::number(port) + " —— " + sock_->errorString();
        delete sock_; sock_ = nullptr;
        return false;
    }
    if (!doHandshake(outError)) { markClosed(); return false; }
    if (!sendAuth(user, password, outError)) { markClosed(); return false; }
    open_ = true;
    if (!database.isEmpty()) {
        // 尝试 USE 默认库；不存在也不影响连接（树会展示全部库）
        SqlResult r = execute("USE `" + database + "`");
        if (r.kind == SqlResult::Error) {
            // 仅记录，不阻断连接
            lastError_.clear();
        }
    }
    return true;
}

void SqlConnection::disconnect() {
    if (sock_) {
        if (sock_->state() == QAbstractSocket::ConnectedState) {
            QByteArray q; q.append((char)0x01); // COM_QUIT
            seq_ = 0;
            sock_->write(QByteArray("\x01\x00\x00\x00\x01", 5)); // 直接发裸包
            sock_->waitForBytesWritten(1000);
        }
        sock_->close();
        delete sock_; sock_ = nullptr;
    }
    open_ = false;
}

SqlResult SqlConnection::execute(const QString& sql) {
    SqlResult res;
    QElapsedTimer t; t.start();
    if (!isOpen()) {
        res.kind = SqlResult::Error; res.errorCode = 2003; res.errorMessage = "未连接到服务器";
        res.elapsedMs = t.elapsed(); return res;
    }
    QByteArray payload;
    payload.append((char)0x03); // COM_QUERY
    payload.append(sql.toUtf8());
    seq_ = 0;
    QString err;
    if (!writePacket(payload, &err)) {
        res.kind = SqlResult::Error; res.errorMessage = err; res.elapsedMs = t.elapsed();
        markClosed(); return res;
    }
    QByteArray first;
    if (!readPacket(first, &err)) {
        res.kind = SqlResult::Error; res.errorMessage = err; res.elapsedMs = t.elapsed();
        markClosed(); return res;
    }
    parseResponse(first, res, &err);
    if (res.kind == SqlResult::Error && !err.isEmpty()) res.errorMessage = err;
    res.elapsedMs = t.elapsed();
    return res;
}

void SqlConnection::parseResponse(const QByteArray& first, SqlResult& res, QString* err) {
    int f = (quint8)first[0];
    if (f == 0x00) { // OK 包
        res.kind = SqlResult::Ok;
        int pos = 1;
        res.affectedRows = (qlonglong)readLenencInt(first, pos);
        readLenencInt(first, pos); // last_insert_id
        if (pos < first.size()) res.info = readLenencStr(first, pos);
        return;
    }
    if (f == 0xFF) { // ERR 包
        res.kind = SqlResult::Error;
        res.errorCode = (quint8)first[1] | ((quint8)first[2] << 8);
        res.errorMessage = QString::fromUtf8(first.mid(3));
        return;
    }
    if (f == 0xFE && first.size() <= 9) { // 空 EOF（理论上的无结果）
        res.kind = SqlResult::Ok; res.affectedRows = 0; return;
    }
    // 结果集
    res.kind = SqlResult::ResultSet;
    int pos = 0;
    int ncol = (int)readLenencInt(first, pos);
    res.columns.clear();
    for (int i = 0; i < ncol; i++) {
        QByteArray cp;
        if (!readPacket(cp, err)) { res.kind = SqlResult::Error; res.errorMessage = *err; return; }
        int cpos = 0;
        readLenencStr(cp, cpos); // catalog
        QString schema = readLenencStr(cp, cpos); // schema
        QString tbl = readLenencStr(cp, cpos);     // table
        QString orgTbl = readLenencStr(cp, cpos);  // org_table
        QString name = readLenencStr(cp, cpos);    // name
        readLenencStr(cp, cpos); // org_name
        int fixedLen = (quint8)cp[cpos++]; Q_UNUSED(fixedLen);
        int charset = readInt16LE(cp, cpos);
        int columnLength = readInt32LE(cp, cpos);
        int type = (quint8)cp[cpos++];
        int flags = readInt16LE(cp, cpos);
        int decimals = (quint8)cp[cpos++];
        readInt16LE(cp, cpos); // filler
        ColumnInfo ci;
        ci.name = name; ci.table = tbl; ci.orgTable = orgTbl;
        ci.type = type; ci.flags = flags;
        ci.typeName = typeNameOf(type, columnLength);
        Q_UNUSED(schema); Q_UNUSED(charset); Q_UNUSED(decimals);
        res.columns.append(ci);
    }
    // 列定义之后的 EOF
    QByteArray eof;
    if (!readPacket(eof, err)) { res.kind = SqlResult::Error; res.errorMessage = *err; return; }
    // 行
    res.rows.clear(); res.nulls.clear();
    while (true) {
        QByteArray rp;
        if (!readPacket(rp, err)) { res.kind = SqlResult::Error; res.errorMessage = *err; return; }
        if ((quint8)rp[0] == 0xFE && rp.size() <= 9) break; // EOF 终止符
        int rpos = 0;
        QList<QString> row; QList<bool> nul;
        for (int i = 0; i < ncol; i++) {
            if ((quint8)rp[rpos] == 0xFB) { // NULL
                rpos++; row.append(QString()); nul.append(true);
            } else {
                row.append(readLenencStr(rp, rpos)); nul.append(false);
            }
        }
        res.rows.append(row); res.nulls.append(nul);
    }
}

bool SqlConnection::ping() {
    if (!isOpen()) return false;
    QByteArray payload;
    payload.append((char)0x0E); // COM_PING
    seq_ = 0;
    QString err;
    if (!writePacket(payload, &err)) { markClosed(); return false; }
    QByteArray p;
    if (!readPacket(p, &err)) { markClosed(); return false; }
    return (quint8)p[0] == 0x00;
}
