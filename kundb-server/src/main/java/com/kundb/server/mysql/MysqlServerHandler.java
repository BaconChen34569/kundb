package com.kundb.server.mysql;

import com.kundb.common.protocol.ColumnType;
import com.kundb.common.protocol.ErrorCode;
import com.kundb.server.engine.ColumnMeta;
import com.kundb.server.engine.EngineException;
import com.kundb.server.engine.ExecutionEngine;
import com.kundb.server.engine.QueryResult;
import com.kundb.server.engine.SqlLexer;
import com.kundb.server.session.SessionContext;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 单连接的 MySQL 协议处理器：握手 → mysql_native_password 鉴权 → COM_QUERY/PING/INIT_DB/QUIT 分发。
 * <p>为兼容 DataGrip / Navicat 等标准工具，对它们常用的元数据探测（SHOW 系列、SELECT @@变量、
 * information_schema 查询）做了路由与翻译，其余 SQL 交给执行引擎。</p>
 */
public class MysqlServerHandler extends ChannelInboundHandlerAdapter {

    private final ExecutionEngine engine;
    private final SessionContext session = new SessionContext();
    private final int[] seqRef = {0};
    /** 本连接的预处理语句：stmt_id → 语句。Navicat / JDBC PreparedStatement 走二进制协议，必须给它完整的元数据与二进行。 */
    private final java.util.Map<Integer, PrepStmt> prepared = new java.util.HashMap<>();

    private byte[] scramble;
    private boolean authenticated = false;
    private boolean multiStatements = false; // 客户端握手时是否协商 CLIENT_MULTI_STATEMENTS
    private volatile String lastQuerySql = "(none)";

    private static final int CLIENT_CONNECT_WITH_DB = 0x08;
    private static final int CLIENT_MULTI_STATEMENTS = 0x0001_0000;

    public MysqlServerHandler(ExecutionEngine engine) {
        this.engine = engine;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        scramble = MysqlHandshake.newScramble();
        int connId = (int) (Thread.currentThread().getId() & 0x7FFFFFFF) + (int) (System.nanoTime() & 0xFFFF);
        seqRef[0] = 0;
        MysqlCodec.send(ctx, seqRef, MysqlHandshake.build(connId, scramble));
    }

    /** 连接断开：必须把该连接残留的锁与未提交事务清理掉，否则它占住的锁会一直卡住别人。 */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        try {
            engine.onDisconnect(session);
        } catch (Exception ignore) {
            // 断开清理失败不能影响 Netty 的正常关闭流程
        }
        super.channelInactive(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof MysqlPacket pkt)) {
            return;
        }
        try {
            if (!authenticated) {
                handleHandshakeResponse(ctx, pkt);
            } else {
                handleCommand(ctx, pkt);
            }
        } catch (EngineException ee) {
            sendErr(ctx, pkt, ee.code(), ee.getMessage());
        } catch (Exception e) {
            try {
                java.io.StringWriter sw = new java.io.StringWriter();
                e.printStackTrace(new java.io.PrintWriter(sw));
                java.nio.file.Files.writeString(java.nio.file.Path.of(System.getProperty("java.io.tmpdir") + "kundb-errtrace.log"),
                        "[ERR] sql=" + lastQuerySql + "\n" + sw + "\n",
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (Exception ignore) {
            }
            sendErr(ctx, pkt, ErrorCode.SERVER_ERROR, "服务端异常: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getName()));
        } finally {
            pkt.payload.release();
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent e && e.state() == IdleState.READER_IDLE) {
            ctx.close();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.close();
    }

    // ===================== 握手 / 鉴权 =====================

    private void handleHandshakeResponse(ChannelHandlerContext ctx, MysqlPacket pkt) throws EngineException {
        ByteBuf p = pkt.payload;
        int capsLow = p.readUnsignedShortLE();    // 能力低 16 位
        int capsHigh = p.readUnsignedShortLE();   // 能力高 16 位
        int capabilities = capsLow | (capsHigh << 16);
        multiStatements = (capabilities & CLIENT_MULTI_STATEMENTS) != 0;
        p.readIntLE();                 // max packet
        p.readUnsignedByte();          // charset
        p.skipBytes(23);               // reserved
        String username = readNulString(p);
        byte[] authResp = readLenEncBytes(p); // CLIENT_SECURE_CONNECTION → 长度编码
        // 调试期曾在此转储握手尾部字节；公开版本不再把鉴权材料写入磁盘。
        String db = null;
        if ((capabilities & CLIENT_CONNECT_WITH_DB) != 0 && p.isReadable()) {
            db = readNulString(p);     // 仅当客户端声明 CLIENT_CONNECT_WITH_DB 才读库名
        }
        String plain = engine.lookupPassword(username);
        boolean ok = NativeAuth.verify(scramble, plain, authResp);
        seqRef[0] = pkt.seq + 1;
        if (ok) {
            authenticated = true;
            session.setUser(username);
            if (db != null && !db.isEmpty()) {
                session.setCurrentDatabase(db);
            }
            // 并发控制：登记这条连接（继承全局隔离级别），之后 SHOW PROCESSLIST 能看到它
            engine.onConnect(session);
            MysqlCodec.send(ctx, seqRef, MysqlCodec.okPacket(0, 0, MysqlCodec.SERVER_STATUS_AUTOCOMMIT, null));
        } else {
            System.out.println("[KUN-DB] 鉴权失败: user=" + username + " db=" + db);
            MysqlCodec.send(ctx, seqRef, MysqlCodec.errPacket(1045, "28000", "Access denied for user '" + username + "'"));
        }
    }

    // ===================== 命令分发 =====================

    private void handleCommand(ChannelHandlerContext ctx, MysqlPacket pkt) throws EngineException {
        ByteBuf p = pkt.payload;
        if (!p.isReadable()) {
            return;
        }
        int cmd = p.readUnsignedByte();
        switch (cmd) {
            case 0x03 -> handleQuery(ctx, pkt, p);   // COM_QUERY
            case 0x0E -> {                            // COM_PING
                seqRef[0] = pkt.seq + 1;
                MysqlCodec.send(ctx, seqRef, MysqlCodec.okPacket(0, 0, MysqlCodec.SERVER_STATUS_AUTOCOMMIT, null));
            }
            case 0x02 -> handleInitDb(ctx, pkt, p);   // COM_INIT_DB
            case 0x01 -> ctx.close();                // COM_QUIT
            case 0x16 -> handleStmtPrepare(ctx, pkt, p); // COM_STMT_PREPARE
            case 0x17 -> handleStmtExecute(ctx, pkt, p); // COM_STMT_EXECUTE
            case 0x18 -> {                            // COM_STMT_SEND_LONG_DATA：协议规定无响应
                traceRaw(cmd, p);
            }
            case 0x19 -> handleStmtClose(p);              // COM_STMT_CLOSE：无响应
            case 0x1A -> {                            // COM_STMT_RESET
                traceRaw(cmd, p);
                int id = (int) p.readIntLE();
                PrepStmt rs = prepared.get(id);
                if (rs != null) {
                    rs.rows = List.of();
                    rs.cursorPos = 0;
                }
                seqRef[0] = pkt.seq + 1;
                MysqlCodec.send(ctx, seqRef, MysqlCodec.okPacket(0, 0, statusFlags(false), null));
            }
            case 0x1C -> handleStmtFetch(ctx, pkt, p);  // COM_STMT_FETCH（游标）
            case 0x04 -> handleFieldList(ctx, pkt, p);  // COM_FIELD_LIST
            case 0x1B -> {                            // COM_SET_OPTION
                traceRaw(cmd, p);
                seqRef[0] = pkt.seq + 1;
                MysqlCodec.send(ctx, seqRef, MysqlCodec.eofPacket(0, statusFlags(false)));
            }
            case 0x1F -> {                            // COM_RESET_CONNECTION
                traceRaw(cmd, p);
                seqRef[0] = pkt.seq + 1;
                MysqlCodec.send(ctx, seqRef, MysqlCodec.okPacket(0, 0, statusFlags(false), null));
            }
            default -> {                              // 其它命令：回 OK，避免工具报错
                traceRaw(cmd, p);
                seqRef[0] = pkt.seq + 1;
                MysqlCodec.send(ctx, seqRef, MysqlCodec.okPacket(0, 0, MysqlCodec.SERVER_STATUS_AUTOCOMMIT, null));
            }
        }
    }

    /** 非 COM_QUERY 的原始命令追踪（KUNDB_TRACE=1 时启用），用于看清 Navicat 等客户端的真实协议行为。 */
    private void traceRaw(int cmd, ByteBuf p) {
        if (System.getenv("KUNDB_TRACE") == null || !p.isReadable()) {
            return;
        }
        byte[] b = new byte[Math.min(p.readableBytes(), 140)];
        p.getBytes(p.readerIndex(), b);
        String s = new String(b, StandardCharsets.UTF_8).replaceAll("[^\\x20-\\x7e]", ".");
        trace("CMD", String.format("0x%02X %s", cmd, s), -1);
    }

    // ===================== 预处理语句（COM_STMT_*，二进制协议）=====================

    /** 一条服务端预处理语句。rows/cursorPos 仅在游标模式（EXECUTE 带 CURSOR_TYPE_READ_ONLY）下使用。 */
    private static final class PrepStmt {
        final int id;
        final String sql;
        final int paramCount;
        List<ColumnMeta> columns = List.of();
        List<List<Object>> rows = List.of();
        int cursorPos = 0;

        PrepStmt(int id, String sql, int paramCount) {
            this.id = id;
            this.sql = sql;
            this.paramCount = paramCount;
        }
    }

    private int nextStmtId = 1;

    private void handleStmtPrepare(ChannelHandlerContext ctx, MysqlPacket pkt, ByteBuf p) {
        byte[] rest = new byte[p.readableBytes()];
        p.readBytes(rest);
        String sql = new String(rest, StandardCharsets.UTF_8).trim();
        int paramCount = countPlaceholders(sql);
        PrepStmt st = new PrepStmt(nextStmtId++, sql, paramCount);

        List<ColumnMeta> cols = List.of();
        if (isQueryLike(sql)) {
            try {
                // prepare 阶段只为取回列元数据：SELECT 无副作用，可以安全先跑一次。
                // 有占位符时把 ? 换成 NULL 以求类型正确（行数通常是 0，但元数据可用）。
                QueryResult r = execSql(paramCount == 0 ? sql : sql.replace("?", "NULL"));
                if (r.isResultSet()) {
                    cols = r.columns();
                }
            } catch (Exception ignore) {
                // 取不到元数据就退化为「零列」响应：仍是合法协议，EXECUTE 时会用真实列元数据集回应
            }
        }
        st.columns = cols;
        prepared.put(st.id, st);
        trace("PREPARE", sql + "   [params=" + paramCount + "]", cols.size());

        ByteBuf resp = ctx.alloc().buffer();
        resp.writeByte(0x00);              // OK
        resp.writeIntLE(st.id);            // statement_id
        resp.writeShortLE(cols.size());    // column_count
        resp.writeShortLE(paramCount);     // param_count
        resp.writeByte(0x00);              // filler
        resp.writeShortLE(0);              // warnings
        seqRef[0] = pkt.seq + 1;
        MysqlCodec.send(ctx, seqRef, resp);
        if (paramCount > 0) {
            // 参数定义：一律按可空的字符串占位（客户端据此处决定本次绑定的类型，够用且不会错转浮点数）
            for (int i = 0; i < paramCount; i++) {
                MysqlCodec.send(ctx, seqRef, MysqlCodec.columnDef("", "", "", "?", "?",
                        MysqlCodec.CHARSET_UTF8MB4, 255, MysqlCodec.TYPE_VAR_STRING, 0x80));
            }
            MysqlCodec.send(ctx, seqRef, MysqlCodec.eofPacket(0, statusFlags(false)));
        }
        if (!cols.isEmpty()) {
            for (ColumnMeta cm : cols) {
                int[] m = MysqlCodec.typeMapping(cm.type(), cm.nullable(), cm.primaryKey());
                MysqlCodec.send(ctx, seqRef, MysqlCodec.columnDef("", "", "", cm.name(), cm.name(),
                        m[1], m[2], m[0], m[3]));
            }
            MysqlCodec.send(ctx, seqRef, MysqlCodec.eofPacket(0, statusFlags(false)));
        }
    }

    private void handleStmtExecute(ChannelHandlerContext ctx, MysqlPacket pkt, ByteBuf p) {
        int stmtId = (int) p.readIntLE();
        int flags = p.readUnsignedByte();
        p.skipBytes(4);                                  // iteration-count（恒为 1）
        boolean cursor = (flags & 0x01) != 0;            // CURSOR_TYPE_READ_ONLY
        PrepStmt st = prepared.get(stmtId);
        if (st == null) {
            sendErr(ctx, pkt, ErrorCode.SERVER_ERROR, "Unknown prepared statement id: " + stmtId);
            return;
        }
        String sql = st.sql;
        if (st.paramCount > 0 && p.isReadable()) {
            int bitmapLen = (st.paramCount + 7) / 8;
            byte[] nb = new byte[bitmapLen];
            if (p.readableBytes() >= bitmapLen) {
                p.readBytes(nb);
            }
            int bound = p.isReadable() ? p.readUnsignedByte() : 0;
            List<Integer> types = new ArrayList<>();
            List<Object> vals = new ArrayList<>();
            if (bound == 1 && p.readableBytes() >= st.paramCount * 2) {
                for (int i = 0; i < st.paramCount; i++) {
                    types.add(p.readUnsignedShortLE());
                }
                for (int i = 0; i < st.paramCount; i++) {
                    boolean isNull = (nb[i / 8] & (1 << (i % 8))) != 0;
                    vals.add(isNull ? null : readBinaryParam(p, types.get(i)));
                }
            } else {
                for (int i = 0; i < st.paramCount; i++) {
                    vals.add(null);
                }
            }
            sql = bindParams(st.sql, vals);
        }
        trace("EXECUTE", sql, -1);
        try {
            QueryResult r = execSql(sql);
            if (!r.isResultSet()) {
                seqRef[0] = pkt.seq + 1;
                MysqlCodec.send(ctx, seqRef, MysqlCodec.okPacket(r.affectedRows(), 0, statusFlags(false), null));
                return;
            }
            st.columns = r.columns();
            st.rows = r.rows();
            st.cursorPos = 0;
            List<Integer> types = new ArrayList<>();
            for (ColumnMeta cm : r.columns()) {
                types.add(MysqlCodec.typeMapping(cm.type(), cm.nullable(), cm.primaryKey())[0]);
            }
            seqRef[0] = pkt.seq + 1;
            ByteBuf cc = ctx.alloc().buffer();
            MysqlCodec.writeLenEncInt(cc, r.columns().size());
            MysqlCodec.send(ctx, seqRef, cc);
            for (ColumnMeta cm : r.columns()) {
                int[] m = MysqlCodec.typeMapping(cm.type(), cm.nullable(), cm.primaryKey());
                MysqlCodec.send(ctx, seqRef, MysqlCodec.columnDef("", "", "", cm.name(), cm.name(),
                        m[1], m[2], m[0], m[3]));
            }
            int status = statusFlags(false) | (cursor ? MysqlCodec.SERVER_STATUS_CURSOR_EXISTS : 0);
            MysqlCodec.send(ctx, seqRef, MysqlCodec.eofPacket(0, status));
            if (cursor) {
                return;                                  // 游标模式：等 COM_STMT_FETCH 再推数据
            }
            for (List<Object> row : r.rows()) {
                ByteBuf rb = ctx.alloc().buffer();
                MysqlCodec.writeBinaryRow(rb, row, types);
                MysqlCodec.send(ctx, seqRef, rb);
            }
            MysqlCodec.send(ctx, seqRef, MysqlCodec.eofPacket(0, statusFlags(false)));
        } catch (EngineException e) {
            sendErr(ctx, pkt, e.code(), e.getMessage());
        } catch (Exception e) {
            sendErr(ctx, pkt, ErrorCode.SERVER_ERROR, "服务端异常: " + e);
        }
    }

    private void handleStmtFetch(ChannelHandlerContext ctx, MysqlPacket pkt, ByteBuf p) {
        int stmtId = (int) p.readIntLE();
        long numRows = p.readUnsignedIntLE();
        PrepStmt st = prepared.get(stmtId);
        seqRef[0] = pkt.seq + 1;
        if (st == null) {
            MysqlCodec.send(ctx, seqRef, MysqlCodec.errPacket(1243, "HY000", "Unknown prepared statement"));
            return;
        }
        List<Integer> types = new ArrayList<>();
        for (ColumnMeta cm : st.columns) {
            types.add(MysqlCodec.typeMapping(cm.type(), cm.nullable(), cm.primaryKey())[0]);
        }
        int avail = st.rows.size() - st.cursorPos;
        int n = (int) Math.min(numRows, Math.max(avail, 0));
        for (int i = 0; i < n; i++) {
            ByteBuf rb = ctx.alloc().buffer();
            MysqlCodec.writeBinaryRow(rb, st.rows.get(st.cursorPos + i), types);
            MysqlCodec.send(ctx, seqRef, rb);
        }
        st.cursorPos += n;
        boolean last = st.cursorPos >= st.rows.size();
        int status = statusFlags(false)
                | (last ? MysqlCodec.SERVER_STATUS_LAST_ROW_SENT : MysqlCodec.SERVER_STATUS_CURSOR_EXISTS);
        trace("FETCH", "stmt=" + stmtId + " want=" + numRows + " sent=" + n + " last=" + last, -1);
        MysqlCodec.send(ctx, seqRef, MysqlCodec.eofPacket(0, status));
    }

    /** COM_STMT_CLOSE 按协议规定【不返回任何响应】——多发了包会让客户端后续收入错位。 */
    private void handleStmtClose(ByteBuf p) {
        if (p.readableBytes() >= 4) {
            prepared.remove((int) p.readIntLE());
        }
    }

    /**
     * COM_FIELD_LIST：返回指定表的字段定义流（若干 ColumnDefinition 包 + EOF）。
     * 老客户端/部分工具用它取列元数据；此前这里只回一个 OK 包，客户端会拿到 0 列。
     */
    private void handleFieldList(ChannelHandlerContext ctx, MysqlPacket pkt, ByteBuf p) {
        String table = readNulString(p);
        String wild = p.isReadable() ? readNulString(p) : "%";
        trace("FIELDLIST", table + " / " + wild, -1);
        seqRef[0] = pkt.seq + 1;
        try {
            String db = session.getCurrentDatabase();
            List<ColumnMeta> cols;
            try {
                cols = engine.describe(db, table);
            } catch (Exception e) {
                MysqlCodec.send(ctx, seqRef, MysqlCodec.errPacket(1146, "42S02", "Unknown table '" + table + "'"));
                return;
            }
            for (ColumnMeta cm : cols) {
                if (wild != null && !wild.isEmpty() && !wild.equals("%") && !wildMatch(cm.name(), wild)) {
                    continue;
                }
                int[] m = MysqlCodec.typeMapping(cm.type(), cm.nullable(), cm.primaryKey());
                MysqlCodec.send(ctx, seqRef, MysqlCodec.columnDef(db, table, table, cm.name(), cm.name(),
                        m[1], m[2], m[0], m[3]));
            }
            MysqlCodec.send(ctx, seqRef, MysqlCodec.eofPacket(0, statusFlags(false)));
        } catch (Exception e) {
            MysqlCodec.send(ctx, seqRef, MysqlCodec.errPacket(1146, "42S02", "Unknown table '" + table + "'"));
        }
    }

    private static boolean wildMatch(String name, String pattern) {
        String re = pattern.replace(".", "\\.").replace("%", ".*").replace("_", ".").replace("*", ".*");
        return name.matches("(?i)" + re);
    }


    /** 读出一个长度编码字符串（用于二进制协议的参数值）。 */
    private static String readParamStr(ByteBuf p) {
        if (!p.isReadable()) {
            return null;
        }
        byte[] b = readLenEncBytes(p);
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    /** 语句里的占位符 ? 个数（忽略字符串字面量与反引号标识符内的问号）。 */
    private static int countPlaceholders(String sql) {
        int n = 0;
        boolean inS = false, inD = false, inB = false;
        char prev = ' ';
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (inS) {
                if (c == '\\' && prev != '\\') {
                    // skip escaped
                } else if (c == '\'' && prev != '\\') {
                    inS = false;
                }
            } else if (inD) {
                if (c == '"' && prev != '\\') {
                    inD = false;
                }
            } else if (inB) {
                if (c == '`') {
                    inB = false;
                }
            } else if (c == '\'') {
                inS = true;
            } else if (c == '"') {
                inD = true;
            } else if (c == '`') {
                inB = true;
            } else if (c == '?') {
                n++;
            }
            prev = c;
        }
        return n;
    }

    /** 把实参按顺序替换进 SQL 的占位符（保持字面量安全），得到可直接交给引擎执行的文本 SQL。 */
    private static String bindParams(String sql, List<Object> vals) {
        StringBuilder out = new StringBuilder();
        int vi = 0;
        boolean inS = false, inD = false, inB = false;
        char prev = ' ';
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (inS) {
                out.append(c);
                if (c == '\\' && prev != '\\') {
                    // no-op
                } else if (c == '\'' && prev != '\\') {
                    inS = false;
                }
            } else if (inD) {
                out.append(c);
                if (c == '"' && prev != '\\') {
                    inD = false;
                }
            } else if (inB) {
                out.append(c);
                if (c == '`') {
                    inB = false;
                }
            } else if (c == '?') {
                Object v = vi < vals.size() ? vals.get(vi++) : null;
                if (v == null) {
                    out.append("NULL");
                } else if (v instanceof Number || v instanceof Boolean) {
                    out.append(v instanceof Boolean b ? (b ? 1 : 0) : v.toString());
                } else {
                    out.append('\'').append(v.toString().replace("'", "''")).append('\'');
                }
            } else {
                if (c == '\'') {
                    inS = true;
                } else if (c == '"') {
                    inD = true;
                } else if (c == '`') {
                    inB = true;
                }
                out.append(c);
            }
            prev = c;
        }
        return out.toString();
    }

    /** 从 COM_STMT_EXECUTE 的参数区读出一个二进制协议值。 */
    private static Object readBinaryParam(ByteBuf p, int type) {
        if (!p.isReadable()) {
            return null;
        }
        try {
            switch (type) {
                case MysqlCodec.TYPE_TINY -> { return (long) p.readUnsignedByte(); }
                case 2 -> { return (long) p.readShortLE(); }          // TYPE_SHORT
                case MysqlCodec.TYPE_LONG, 9 -> { return (long) p.readIntLE(); } // TYPE_LONG / INT24
                case MysqlCodec.TYPE_LONGLONG -> { return p.readLongLE(); }
                case 4 -> { return (double) Float.parseFloat(String.valueOf(p.readFloatLE())); } // TYPE_FLOAT
                case MysqlCodec.TYPE_DOUBLE -> { return p.readDoubleLE(); }
                case 0, 246 -> { return readParamStr(p); }            // DECIMAL / NEWDECIMAL
                case MysqlCodec.TYPE_DATE -> {
                    int len = (int) readLenEncInt(p);
                    if (len < 4) {
                        skip(p, len);
                        return null;
                    }
                    int y = p.readUnsignedShortLE();
                    int mo = p.readUnsignedByte();
                    int d = p.readUnsignedByte();
                    skip(p, len - 4);
                    return String.format("%04d-%02d-%02d", y, mo, d);
                }
                case MysqlCodec.TYPE_DATETIME, 7 -> {                  // DATETIME / TIMESTAMP
                    int len = (int) readLenEncInt(p);
                    if (len < 7) {
                        skip(p, len);
                        return null;
                    }
                    int y = p.readUnsignedShortLE();
                    int mo = p.readUnsignedByte();
                    int d = p.readUnsignedByte();
                    int hh = p.readUnsignedByte();
                    int mi = p.readUnsignedByte();
                    int se = p.readUnsignedByte();
                    skip(p, len - 7);
                    return String.format("%04d-%02d-%02d %02d:%02d:%02d", y, mo, d, hh, mi, se);
                }
                default -> { return readParamStr(p); }                 // 字符串/布尔等
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static void skip(ByteBuf p, int n) {
        if (n > 0 && p.readableBytes() >= n) {
            p.skipBytes(n);
        }
    }

    /** 判定语句是否为「无副作用的查询类」——只有这类语句才允许在 PREPARE 阶段先跑一次取元数据。 */
    private static boolean isQueryLike(String sql) {
        String[] parts = sql.trim().split("[\\s(]+", 2);
        if (parts.length == 0) {
            return false;
        }
        return switch (parts[0].toLowerCase()) {
            case "select", "show", "describe", "desc", "explain", "with", "values" -> true;
            default -> false;
        };
    }

    /** 统一的 SQL 执行入口：元数据路由优先，其余交给执行引擎。 */
    private QueryResult execSql(String sql) throws EngineException {
        QueryResult r = routeMetadata(sql);
        if (r == null) {
            r = engine.execute(session, stripBackticks(sql));
        }
        return r;
    }

    private void handleInitDb(ChannelHandlerContext ctx, MysqlPacket pkt, ByteBuf p) {
        String db = readNulString(p);
        seqRef[0] = pkt.seq + 1;
        if (engine.databaseExists(db)) {
            session.setCurrentDatabase(db);
            MysqlCodec.send(ctx, seqRef, MysqlCodec.okPacket(0, 0, MysqlCodec.SERVER_STATUS_AUTOCOMMIT, null));
        } else {
            MysqlCodec.send(ctx, seqRef, MysqlCodec.errPacket(1049, "42000", "Unknown database '" + db + "'"));
        }
    }

    private void handleQuery(ChannelHandlerContext ctx, MysqlPacket pkt, ByteBuf p) throws EngineException {
        byte[] rest = new byte[p.readableBytes()];
        p.readBytes(rest);
        String raw = new String(rest, StandardCharsets.UTF_8).trim();
        if (raw.isEmpty()) {
            seqRef[0] = pkt.seq + 1;
            MysqlCodec.send(ctx, seqRef, MysqlCodec.okPacket(0, 0, MysqlCodec.SERVER_STATUS_AUTOCOMMIT, null));
            return;
        }
        // 多语句批（Navicat/DataGrip 连接期常把多条语句塞进一个 COM_QUERY）：仅当客户端协商了
        // CLIENT_MULTI_STATEMENTS 才逐条执行并分别返回结果集（带 MORE 标志）；否则只执行第一条（原行为），
        // 避免未开启多语句的客户端（如 PyMySQL）因无法消费多结果集而连接乱序。
        List<String> stmts = multiStatements ? splitStatements(raw) : List.of(raw);
        seqRef[0] = pkt.seq + 1; // 整个响应（含多结果集）的序列号从此连续递增
        for (int i = 0; i < stmts.size(); i++) {
            String sql = stmts.get(i).trim();
            if (sql.isEmpty()) {
                continue;
            }
            lastQuerySql = sql;
            boolean more = (i < stmts.size() - 1);
            QueryResult r = routeMetadata(sql);
            if (r == null) {
                r = engine.execute(session, stripBackticks(sql));
                trace("ENGINE", sql, r.columns().size(), r.rows().size()); // 记录掉进执行引擎的查询最终列数（定位普通 SELECT 类"列数不符"）
            }
            if (r.isResultSet()) {
                sendResultSet(ctx, pkt, r, more);
            } else {
                MysqlCodec.send(ctx, seqRef, MysqlCodec.okPacket(r.affectedRows(), 0, statusFlags(more), null));
            }
        }
    }

    /** 按分号切分语句，忽略字符串字面量（' 与 "）与行注释中的分号。 */
    private static List<String> splitStatements(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inS = false, inD = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inS) {
                cur.append(c);
                if (c == '\\' && i + 1 < s.length()) {
                    cur.append(s.charAt(++i));
                } else if (c == '\'') {
                    inS = false;
                }
            } else if (inD) {
                cur.append(c);
                if (c == '\\' && i + 1 < s.length()) {
                    cur.append(s.charAt(++i));
                } else if (c == '"') {
                    inD = false;
                }
            } else if (c == '\'') {
                inS = true;
                cur.append(c);
            } else if (c == '"') {
                inD = true;
                cur.append(c);
            } else if (c == ';') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }


    // ===================== 结果编码 =====================

    private void sendResultSet(ChannelHandlerContext ctx, MysqlPacket pkt, QueryResult r, boolean more) {
        ByteBuf cc = ctx.alloc().buffer();
        MysqlCodec.writeLenEncInt(cc, r.columns().size());
        MysqlCodec.send(ctx, seqRef, cc);
        for (ColumnMeta cm : r.columns()) {
            int[] m = MysqlCodec.typeMapping(cm.type(), cm.nullable(), cm.primaryKey());
            ByteBuf cd = MysqlCodec.columnDef("", "", "", cm.name(), cm.name(), m[1], m[2], m[0], m[3]);
            MysqlCodec.send(ctx, seqRef, cd);
        }
        MysqlCodec.send(ctx, seqRef, MysqlCodec.eofPacket(0, statusFlags(more)));
        for (List<Object> row : r.rows()) {
            ByteBuf rb = ctx.alloc().buffer();
            for (Object v : row) {
                MysqlCodec.writeRowValue(rb, v);
            }
            MysqlCodec.send(ctx, seqRef, rb);
        }
        MysqlCodec.send(ctx, seqRef, MysqlCodec.eofPacket(0, statusFlags(more)));
    }

    private static int statusFlags(boolean more) {
        return MysqlCodec.SERVER_STATUS_AUTOCOMMIT | (more ? MysqlCodec.SERVER_MORE_RESULTS_EXISTS : 0);
    }

    private void sendErr(ChannelHandlerContext ctx, MysqlPacket pkt, ErrorCode code, String message) {
        seqRef[0] = pkt.seq + 1;
        int c = switch (code) {
            case ACCESS_DENIED, AUTH_REQUIRED -> 1045;
            case PRIVILEGE_DENIED -> 1142;
            case SYNTAX_ERROR -> 1064;
            case UNKNOWN_DATABASE -> 1049;
            case UNKNOWN_TABLE -> 1146;
            case UNKNOWN_COLUMN -> 1054;
            case DUPLICATE_KEY -> 1062;
            case TYPE_MISMATCH -> 1366;
            case TXN_CONFLICT -> 1205;
            case LOCK_WAIT_TIMEOUT -> 1205;
            case DEADLOCK -> 1213;
            case PROTOCOL_ERROR -> 1105;
            default -> 1105;
        };
        String st = code.sqlState() != null ? code.sqlState() : "HY000";
        MysqlCodec.send(ctx, seqRef, MysqlCodec.errPacket(c, st, message));
    }

    // ===================== 元数据路由（兼容 DataGrip）=====================

    private QueryResult routeMetadata(String rawSql) throws EngineException {
        String sql = stripComments(rawSql).trim();
        sql = sql.replaceAll(";+\\s*$", "").trim(); // DataGrip 等客户端常带尾分号
        String lower = sql.toLowerCase();
        // 顶层追踪：记录每一条进来的 SQL（含普通 SELECT / 掉进引擎的命令），用于定位"列数不符"元凶
        trace("RAW", sql, -1);
        if (lower.startsWith("set ")) {
            // 并发控制相关的 SET 必须交回引擎（隔离级别 / 锁等待超时 / autocommit 都会真实生效）；
            // 其余（SET NAMES、各种客户端变量探测）引擎里 doSet 会忽略并回 OK。
            if (lower.contains("isolation") || lower.contains("autocommit")
                    || lower.contains("lock_wait_timeout") || lower.contains("transaction")) {
                trace("SET", sql, -1);
                return null;   // null = 交给执行引擎
            }
            return QueryResult.ofAffected(0); // 忽略 names 等
        }
        // 仅当查询为「纯 SELECT 1 类」保活探针时才走快路径；含 UNION 的复合查询（如 `SELECT 1 UNION SELECT 2`）
        // 必须以 "select 1 " 开头却会被下方 startsWith 误截，导致 UNION 的后续分支被丢弃、只回首行。
        // 含 UNION 时必须交回执行引擎走 doUnion。
        if (!lower.contains("union")
                && (lower.equals("select 1") || lower.equals("select 1+1")
                || lower.startsWith("select 1 ") || lower.startsWith("select 1\t"))) {
            return QueryResult.ofResultSet(List.of(cm("1", ColumnType.BIGINT)), List.of(List.of(1L)));
        }
        if (lower.startsWith("select version()") && lower.contains("@@version_comment")) {
            // Connector/J 连接初始化探测：select version(), @@version_comment, database()
            return QueryResult.ofResultSet(List.of(cm("VERSION()", ColumnType.VARCHAR),
                            cm("@@version_comment", ColumnType.VARCHAR), cm("DATABASE()", ColumnType.VARCHAR)),
                    List.of(Arrays.asList(MysqlHandshake.SERVER_VERSION, "KUN-DB simple DBMS", session.getCurrentDatabase())));
        }
        if (lower.startsWith("select database(), schema(), left(user()")) {
            // DataGrip 会话探测：第三个表达式 left(user(), instr(concat(user(),'@'),'@')-1) 取 '@' 前的用户名。
            // 引擎词法/函数求值不支持该写法（含 '-' 与带参嵌套函数），在协议层直接求值。
            String u = "root@localhost";
            String name = u.substring(0, u.indexOf('@'));
            return QueryResult.ofResultSet(List.of(cm("database()", ColumnType.VARCHAR),
                            cm("schema()", ColumnType.VARCHAR),
                            cm("left(user(),instr(concat(user(),'@'),'@')-1)", ColumnType.VARCHAR)),
                    List.of(Arrays.asList(session.getCurrentDatabase(), session.getCurrentDatabase(), name)));
        }
        if (lower.startsWith("select version()")) {
            return QueryResult.ofResultSet(List.of(cm("VERSION()", ColumnType.VARCHAR)),
                    List.of(List.of(MysqlHandshake.SERVER_VERSION)));
        }
        // 注意顺序：`select @@version` 这类系统变量查询必须先于 selectNoFrom 处理。
        // 否则词法器把 `@@` 拆成两个 IDENT("@")，selectNoFrom 会误判为"零参函数 @"
        // 而返回列名 `@`、值 `0` 的错误结果（DataGrip/JDBC 探测版本即走此路径）。
        if (lower.contains("@@")) {
            return selectSystemVars(sql);
        }
        if (lower.startsWith("select ")) {
            QueryResult r = selectNoFrom(sql); // 无 FROM 的 SELECT：字面量 / 零参函数（keep-alive 常用）
            if (r != null) {
                return r;
            }
        }
        if (lower.startsWith("show ")) {
            QueryResult r = routeShow(sql);
            if (r != null) {
                trace("SHOW", sql, r.columns().size(), r.rows().size());
                return r;
            }
        }
        if (Pattern.compile("\\bfrom\\s+mysql\\.[a-zA-Z0-9_]+", Pattern.CASE_INSENSITIVE).matcher(sql).find()) {
            // mysql.* 系统库（user/procs_priv/role_edges 等权限表）：KUN-DB 不暴露权限系统，
            // 返回 0 行结果集（列名取自 SELECT 列表的别名/表达式）。若漏进执行引擎，
            // SELECT 列表中的比较表达式（Routine_type='PROCEDURE'）会触发 1064 “期望 FROM 但得到 '='”。
            return QueryResult.ofResultSet(selectListLabels(sql), List.of());
        }
        if (Pattern.compile("\\bfrom\\s+(performance_schema|sys)\\.[a-zA-Z0-9_]+", Pattern.CASE_INSENSITIVE).matcher(sql).find()) {
            // performance_schema / sys：Workbench「Server Status / Performance」面板会查这些库，
            // KUN-DB 不实现，返回 0 行结果集（列名取自 SELECT 列表），避免掉进执行引擎报 1064。
            return QueryResult.ofResultSet(selectListLabels(sql), List.of());
        }
        if (lower.contains("information_schema")) {
            QueryResult r = routeInfoSchema(sql);
            trace("I_S", sql, r.columns().size(), r.rows().size());
            return r;
        }
        trace("ENGINE", sql, -1);
        return null;
    }

    private QueryResult selectSystemVars(String sql) {
        Matcher m = Pattern.compile("@@([a-zA-Z0-9_.]+)").matcher(sql);
        List<ColumnMeta> cols = new ArrayList<>();
        List<Object> row = new ArrayList<>();
        while (m.find()) {
            String var = m.group(1).toLowerCase();
            // 支持 @@session.x / @@global.x 前缀：DataGrip/Connector-J 常以作用域前缀取变量
            String key = var.contains(".") ? var.substring(var.lastIndexOf('.') + 1) : var;
            cols.add(cm("@@" + m.group(1), ColumnType.VARCHAR));
            row.add(switch (key) {
                case "version" -> MysqlHandshake.SERVER_VERSION;
                case "version_comment" -> "KUN-DB simple DBMS";
                case "event_scheduler" -> "OFF";
                case "default_storage_engine", "default_tmp_storage_engine" -> "InnoDB";
                case "transaction_isolation" -> session.getIsolation().sqlName();
                // 注意：这三个是**数值型布尔**变量（真 MySQL 返回 0/1），
                // Connector/J 的 ConnectionImpl.isReadOnly() 会 Integer.parseInt 其值，
                // 若返回 "OFF" 会抛 NumberFormatException: For input string: "OFF"。
                case "transaction_read_only", "read_only", "super_read_only" -> "0";
                // offline_mode 是字符串型（ON/OFF），保持字符串。
                case "offline_mode" -> "OFF";
                case "sql_mode" -> "";
                case "license" -> "KUN-DB";
                case "performance_schema" -> "1";
                case "wait_timeout", "interactive_timeout" -> "28800";
                case "net_write_timeout", "net_read_timeout" -> "60";
                case "max_allowed_packet" -> "67108864";
                case "system_time_zone", "time_zone" -> "SYSTEM";
                default -> "0";
            });
        }
        if (cols.isEmpty()) {
            return QueryResult.ofAffected(0);
        }
        return QueryResult.ofResultSet(cols, List.of(row));
    }

    /**
     * 无 FROM 的 SELECT（DataGrip keep-alive / 驱动探测常用）：
     * SELECT 字面量、零参函数（DATABASE()/CONNECTION_ID()/CURRENT_USER()/...）、SELECT ... FROM DUAL。
     * 返回 null 表示该语句含真实 FROM（或结构复杂），应交回执行引擎。
     */
    private QueryResult selectNoFrom(String sql) {
        // 含 UNION / UNION ALL 的查询（即便无 FROM，如 `SELECT 1 UNION SELECT 2`）必须交回执行引擎，
        // 本方法只会解析并回显首个 SELECT 项，会静默丢弃后续分支，导致 UNION 结果被截断。
        if (Pattern.compile("\\bunion\\b", Pattern.CASE_INSENSITIVE).matcher(sql).find()) {
            return null;
        }
        List<SqlLexer.Token> toks;
        try {
            toks = SqlLexer.tokenize(sql);
        } catch (Exception e) {
            return null; // 含 @ 等字符，交给原有路由（如 @@ 变量）
        }
        int fromIdx = -1;
        for (int k = 1; k < toks.size(); k++) {
            SqlLexer.Token t = toks.get(k);
            if (t.type == SqlLexer.TokenType.IDENT && t.text.equalsIgnoreCase("FROM")) {
                fromIdx = k;
                break;
            }
        }
        if (fromIdx >= 0) {
            SqlLexer.Token nx = fromIdx + 1 < toks.size() ? toks.get(fromIdx + 1) : null;
            boolean dual = nx != null && nx.type == SqlLexer.TokenType.IDENT && nx.text.equalsIgnoreCase("DUAL");
            if (!dual) {
                return null; // 真实 FROM：交回引擎
            }
        }
        int end = fromIdx >= 0 ? fromIdx : toks.size() - 1; // 最后一项是 EOF
        List<ColumnMeta> cols = new ArrayList<>();
        List<Object> row = new ArrayList<>();
        int i = 1; // 跳过 SELECT
        while (i < end) {
            SqlLexer.Token t = toks.get(i);
            String label;
            Object val;
            try {
                if (t.type == SqlLexer.TokenType.NUMBER) {
                    label = t.text;
                    val = literalNumber(t.text);
                    i++;
                } else if (t.type == SqlLexer.TokenType.STRING) {
                    label = t.text;
                    val = t.text;
                    i++;
                } else if (t.type == SqlLexer.TokenType.IDENT) {
                    boolean isFunc = i + 1 < toks.size() && toks.get(i + 1).type == SqlLexer.TokenType.LPAREN;
                    if (isFunc) {
                        if (i + 2 >= toks.size() || toks.get(i + 2).type != SqlLexer.TokenType.RPAREN) {
                            return null; // 带参函数交回引擎
                        }
                        label = t.text + "()";
                        val = zeroArgFunc(t.text);
                        i += 3;
                    } else {
                        label = t.text;
                        val = zeroArgFunc(t.text); // CURRENT_USER 等可不带括号
                        i++;
                    }
                } else {
                    return null;
                }
            } catch (Exception e) {
                return null;
            }
            cols.add(cm(label, ColumnType.VARCHAR));
            row.add(val);
            if (i < end && toks.get(i).type == SqlLexer.TokenType.COMMA) {
                i++;
                continue;
            }
            break;
        }
        if (cols.isEmpty()) {
            return null;
        }
        return QueryResult.ofResultSet(cols, List.of(row));
    }

    private static Object literalNumber(String s) {
        if (s.contains(".")) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return s;
            }
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return s;
        }
    }

    private Object zeroArgFunc(String name) {
        return switch (name.toLowerCase()) {
            case "database", "schema" -> session.getCurrentDatabase();
            case "version" -> MysqlHandshake.SERVER_VERSION;
            case "connection_id" -> "1";
            case "current_user", "user", "session_user", "system_user" -> "root@localhost";
            case "last_insert_id", "found_rows", "row_count" -> "0";
            default -> "0";
        };
    }

    /**
     * 内省诊断追踪：仅当设置环境变量 KUNDB_TRACE 时，把 DataGrip/Navicat 发的每一条
     * SHOW / information_schema 查询及返回列数追加到 dev-tools/_dgq.log。
     * 用途：当工具刷新报"列数不符"却无法定位是哪条命令时，凭此日志精确定位。
     */
        private static void trace(String tag, String sql, int cols) {
        trace(tag, sql, cols, -1);
    }

    /** 带结果行数的追踪：排查"列有了却一行数据都没有"（图形客户端网格全 (N/A)）时最关键的一格。 */
private static void trace(String tag, String sql, int cols, int rows) {
        if (System.getenv("KUNDB_TRACE") == null) {
            return;
        }
        try {
            Files.writeString(Path.of(System.getProperty("java.io.tmpdir") + "kundb-dgq.log"),
                    tag + (rows >= 0 ? " cols=" + cols + " rows=" + rows : " cols=" + cols) + " | " + sql.trim().replace('\n', ' ') + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignore) {
        }
    }

    private QueryResult routeShow(String sql) throws EngineException {
        String lower = sql.toLowerCase();
        // ===== KUN-DB 自更新命令 =====
        // 自更新功能已于 1.1.0 移除（改由官网下载新安装包升级）。这里保留一个静态结果，
        // 让已连接的 Qt 客户端「检查更新」菜单能正常收到一行状态而不是语法错误。
        if (lower.matches("show\\s+kundb\\s+update\\b.*")) {
            return updateRemovedStub();
        }
        // 并发观测命令交给执行引擎（SHOW PROCESSLIST / SHOW ENGINE INNODB STATUS），
        // 必须在此放行，否则会落到下面的通用兜底返回 0 行。
        if (Pattern.compile("^show\\s+(processlist|engine)\\b",
                Pattern.CASE_INSENSITIVE).matcher(sql).find()) {
            return null;
        }
        if (lower.matches("show\\s+(full\\s+)?(databases|schemas).*")) {
            List<List<Object>> rows = new ArrayList<>();
            for (String d : engine.listDatabases()) {
                rows.add(List.of(d));
            }
            return QueryResult.ofResultSet(List.of(cm("Database", ColumnType.VARCHAR)), rows);
        }
        // KUN-DB 自有的运维命令交给执行引擎处理（此处必须返回 null 放行，
        // 否则会被下面的 SHOW TABLES 正则误匹配 / 落到通用兜底）：
        //   SHOW TABLESPACES / SHOW TABLESPACE <名> / SHOW LOGS [n] / SHOW WAL STATUS
        //   SHOW TABLE FILE <名> / SHOW FILE MAP <名>（附加功能1：三段式记录文件的内部视图）
        // 注意：SHOW TABLE FILE 会被下面的 SHOW TABLES 正则（tables?）误吞，必须在这里先放行。
        if (Pattern.compile("^show\\s+(tablespaces?|logs|wal|table\\s+file|file\\s+map)\\b",
                Pattern.CASE_INSENSITIVE).matcher(sql).find()) {
            return null;
        }
        Matcher mt = Pattern.compile("show\\s+(full\\s+)?tables\\s*(?:from\\s+`?([a-zA-Z0-9_]+)`?)?",
                Pattern.CASE_INSENSITIVE).matcher(sql);
        if (mt.find()) {
            boolean full = mt.group(1) != null;
            String db = mt.group(2);
            List<String> tabs = engine.listTables(resolveDb(db));
            String labelDb = db != null ? db : session.getCurrentDatabase();
            List<ColumnMeta> cols = new ArrayList<>();
            cols.add(cm("Tables_in_" + labelDb, ColumnType.VARCHAR));
            if (full) {
                cols.add(cm("Table_type", ColumnType.VARCHAR));
            }
            List<List<Object>> rows = new ArrayList<>();
            if (full) {
                for (List<Object> e : engine.listTablesWithType(resolveDb(db))) {
                    rows.add(e);
                }
            } else {
                for (String t : tabs) {
                    rows.add(List.of(t));
                }
            }
            return QueryResult.ofResultSet(cols, rows);
        }
        Matcher mc = Pattern.compile("show\\s+(full\\s+)?(columns|fields)\\s+from\\s+(?:`?([a-zA-Z0-9_]+)`?\\s*\\.\\s*)?`?([^`\\s]+)`?\\s*(?:from\\s+`?([a-zA-Z0-9_]+)`?)?",
                Pattern.CASE_INSENSITIVE).matcher(sql);
        if (mc.find()) {
            String db = mc.group(5) != null ? mc.group(5) : mc.group(3);
            String tbl = mc.group(4);
            boolean full = mc.group(1) != null;
            // DataGrip 刷新 schema 时发 `SHOW COLUMNS FROM \`%\` FROM \`db\``（% 为通配取全库列），
            // 表名非标识符，正则须能匹配；返回 MySQL 标准 SHOW COLUMNS 列结构 + 0 行，
            // 避免掉进兜底 1 列分支触发"Query returns too few columns: 1 when expected 2"。
            if ("%".equals(tbl) || tbl == null || tbl.isEmpty()) {
                return metaColumnsEmpty(full);
            }
            try {
                return metaColumns(resolveDb(db), tbl, full);
            } catch (EngineException ignore) {
                return metaColumnsEmpty(full);
            }
        }
        Matcher mct = Pattern.compile("show\\s+create\\s+table\\s+(?:`?([a-zA-Z0-9_]+)`?\\s*\\.\\s*)?`?([a-zA-Z0-9_]+)`?",
                Pattern.CASE_INSENSITIVE).matcher(sql);
        if (mct.find()) {
            String ddl = engine.createTableDdl(resolveDb(mct.group(1)), mct.group(2));
            return QueryResult.ofResultSet(
                    List.of(cm("Table", ColumnType.VARCHAR), cm("Create Table", ColumnType.TEXT)),
                    List.of(List.of(mct.group(2), ddl)));
        }
        // SHOW CREATE VIEW：MySQL 协议返回 4 列（View / Create View / character_set_client / collation_connection）。
        // DataGrip 刷新内省视图时执行此命令并期望至少 2 列；若掉进兜底单列分支会报
        // "Query returns too few columns: 1 when expected 2"。
        Matcher mcv = Pattern.compile("show\\s+create\\s+view\\s+(?:`?([a-zA-Z0-9_]+)`?\\s*\\.\\s*)?`?([a-zA-Z0-9_]+)`?",
                Pattern.CASE_INSENSITIVE).matcher(sql);
        if (mcv.find()) {
            String ddl = engine.createTableDdl(resolveDb(mcv.group(1)), mcv.group(2));
            return QueryResult.ofResultSet(
                    List.of(cm("View", ColumnType.VARCHAR), cm("Create View", ColumnType.TEXT),
                            cm("character_set_client", ColumnType.VARCHAR), cm("collation_connection", ColumnType.VARCHAR)),
                    List.of(Arrays.asList(mcv.group(2), ddl, "utf8mb4", "utf8mb4_general_ci")));
        }
        // SHOW CREATE DATABASE：Navicat 右键"设计数据库"/查看库 DDL 时发出。
        Matcher mcd = Pattern.compile("show\\s+create\\s+(?:database|schema)\\s+`?([a-zA-Z0-9_]+)`?",
                Pattern.CASE_INSENSITIVE).matcher(sql);
        if (mcd.find()) {
            String db = mcd.group(1);
            List<ColumnMeta> cols = List.of(cm("Database", ColumnType.VARCHAR), cm("Create Database", ColumnType.TEXT));
            if (engine.databaseExists(db)) {
                return QueryResult.ofResultSet(cols, List.of(
                        List.of(db, "CREATE DATABASE `" + db + "` /*!40100 DEFAULT CHARACTER SET utf8mb4 */")));
            }
            return QueryResult.ofResultSet(cols, List.of());
        }
        // SHOW TABLE STATUS：DataGrip/Navicat 刷新时探测表统计信息，MySQL 返回 18 列
        Matcher mts = Pattern.compile("show\\s+table\\s+status\\s*(?:from\\s+`?([a-zA-Z0-9_]+)`?)?",
                Pattern.CASE_INSENSITIVE).matcher(sql);
        if (mts.find()) {
            List<ColumnMeta> cols = List.of(
                    cm("Name", ColumnType.VARCHAR), cm("Engine", ColumnType.VARCHAR), cm("Version", ColumnType.BIGINT),
                    cm("Row_format", ColumnType.VARCHAR), cm("Rows", ColumnType.BIGINT), cm("Avg_row_length", ColumnType.BIGINT),
                    cm("Data_length", ColumnType.BIGINT), cm("Max_data_length", ColumnType.BIGINT), cm("Index_length", ColumnType.BIGINT),
                    cm("Data_free", ColumnType.BIGINT), cm("Auto_increment", ColumnType.BIGINT), cm("Create_time", ColumnType.VARCHAR),
                    cm("Update_time", ColumnType.VARCHAR), cm("Check_time", ColumnType.VARCHAR), cm("Table_collation", ColumnType.VARCHAR),
                    cm("Checksum", ColumnType.BIGINT), cm("Create_options", ColumnType.VARCHAR), cm("Comment", ColumnType.VARCHAR));
            String stdb = resolveDb(mts.group(1));
            List<List<Object>> rows = new ArrayList<>();
            for (List<Object> e : engine.listTablesWithType(stdb)) {
                boolean isView = "VIEW".equals(e.get(1));
                String tname = (String) e.get(0);
                rows.add(Arrays.asList(e.get(0), isView ? null : "InnoDB", 10, isView ? null : "Dynamic",
                        0, 0, 0, 0, 0, 0, null, null, null, null, isView ? null : "utf8mb4_general_ci", null, "",
                        isView ? "VIEW" : engine.tableComment(stdb, tname)));
            }
            return QueryResult.ofResultSet(cols, rows);
        }
        // SHOW PROCEDURE/FUNCTION STATUS / SHOW TRIGGERS：按 MySQL 真实列结构返回空结果集
        if (lower.matches("show\\s+(procedure|function)\\s+status.*")) {
            return QueryResult.ofResultSet(List.of(
                    cm("Db", ColumnType.VARCHAR), cm("Name", ColumnType.VARCHAR), cm("Type", ColumnType.VARCHAR),
                    cm("Definer", ColumnType.VARCHAR), cm("Modified", ColumnType.VARCHAR), cm("Created", ColumnType.VARCHAR),
                    cm("Security_type", ColumnType.VARCHAR), cm("Comment", ColumnType.VARCHAR),
                    cm("character_set_client", ColumnType.VARCHAR), cm("collation_connection", ColumnType.VARCHAR),
                    cm("Database Collation", ColumnType.VARCHAR)), List.of());
        }
        if (lower.matches("show\\s+triggers.*")) {
            return QueryResult.ofResultSet(List.of(
                    cm("Trigger", ColumnType.VARCHAR), cm("Event", ColumnType.VARCHAR), cm("Table", ColumnType.VARCHAR),
                    cm("Statement", ColumnType.VARCHAR), cm("Timing", ColumnType.VARCHAR), cm("Created", ColumnType.VARCHAR),
                    cm("sql_mode", ColumnType.VARCHAR), cm("Definer", ColumnType.VARCHAR),
                    cm("character_set_client", ColumnType.VARCHAR), cm("collation_connection", ColumnType.VARCHAR),
                    cm("Database Collation", ColumnType.VARCHAR)), List.of());
        }
        Matcher mi = Pattern.compile("show\\s+(index|keys)\\s+from\\s+`?([a-zA-Z0-9_%]+)`?\\s*(?:from\\s+`?([a-zA-Z0-9_]+)`?)?",
                Pattern.CASE_INSENSITIVE).matcher(sql);
        if (mi.find()) {
            String tbl = mi.group(2);
            // DataGrip 刷新索引时发 `SHOW KEYS FROM \`%\``（% 为通配），表名非标识符须能匹配；
            // 直接走 metaIndex 会因 describe('%') 抛异常；此处返回空骨架（15 列，与 MySQL SHOW INDEX 一致）。
            if ("%".equals(tbl) || tbl == null || tbl.isEmpty()) {
                return metaIndexEmpty();
            }
            try {
                return metaIndex(resolveDb(mi.group(3)), tbl);
            } catch (EngineException ignore) {
                return metaIndexEmpty();
            }
        }
        if (lower.startsWith("show warnings") || lower.startsWith("show errors")) {
            return QueryResult.ofResultSet(List.of(
                    cm("Level", ColumnType.VARCHAR), cm("Code", ColumnType.BIGINT), cm("Message", ColumnType.VARCHAR)), List.of());
        }
        // SHOW [GLOBAL|SESSION] VARIABLES [LIKE 'pattern'] / SHOW STATUS：Navicat 连接期高频发出
        // `SHOW VARIABLES LIKE 'character_set%'` 等探测字符集/版本/sql_mode，返回真实变量子集（支持 % 通配）。
        if (Pattern.compile("show\\s+(global\\s+|session\\s+)?(variables|status).*", Pattern.CASE_INSENSITIVE).matcher(sql).matches()) {
            List<ColumnMeta> cols = List.of(cm("Variable_name", ColumnType.VARCHAR), cm("Value", ColumnType.VARCHAR));
            java.util.LinkedHashMap<String, String> all = new java.util.LinkedHashMap<>();
            all.put("version", MysqlHandshake.SERVER_VERSION);
            all.put("version_comment", "KUN-DB simple DBMS");
            all.put("character_set_server", "utf8mb4");
            all.put("character_set_client", "utf8mb4");
            all.put("character_set_connection", "utf8mb4");
            all.put("character_set_database", "utf8mb4");
            all.put("character_set_results", "utf8mb4");
            all.put("character_set_system", "utf8mb4");
            all.put("collation_server", "utf8mb4_general_ci");
            all.put("collation_connection", "utf8mb4_general_ci");
            all.put("collation_database", "utf8mb4_general_ci");
            all.put("sql_mode", "");
            all.put("lower_case_table_names", "0");
            all.put("autocommit", session.isAutocommit() ? "ON" : "OFF");
            // 返回会话真实隔离级别（不再是写死的字符串）：SET TRANSACTION ISOLATION LEVEL 后这里会跟着变
            all.put("transaction_isolation", session.getIsolation().sqlName());
            all.put("innodb_lock_wait_timeout",
                    String.valueOf(session.getLockWaitTimeoutMs() / 1000));
            all.put("system_time_zone", "CST");
            all.put("time_zone", "SYSTEM");
            all.put("max_allowed_packet", "4194304");
            all.put("wait_timeout", "28800");
            all.put("interactive_timeout", "28800");
            all.put("default_storage_engine", "InnoDB");
            all.put("innodb_version", "8.0.41");
            String likePat = null;
            Matcher lm = Pattern.compile("like\\s+'?([^'\\s]+)'?", Pattern.CASE_INSENSITIVE).matcher(sql);
            if (lm.find()) {
                likePat = lm.group(1);
            }
            List<List<Object>> rows = new ArrayList<>();
            for (var e : all.entrySet()) {
                if (likePat == null) {
                    rows.add(List.of(e.getKey(), e.getValue()));
                } else {
                    String regex = "(?i)" + likePat.replace("%", ".*").replace("_", ".");
                    if (e.getKey().matches(regex)) {
                        rows.add(List.of(e.getKey(), e.getValue()));
                    }
                }
            }
            return QueryResult.ofResultSet(cols, rows);
        }
        // SHOW ENGINES：MySQL 返回 6 列（Engine/Support/Comment/Transactions/XA/Savepoints）
        if (lower.matches("show\\s+engines.*")) {
            List<ColumnMeta> cols = List.of(cm("Engine", ColumnType.VARCHAR), cm("Support", ColumnType.VARCHAR),
                    cm("Comment", ColumnType.VARCHAR), cm("Transactions", ColumnType.VARCHAR), cm("XA", ColumnType.VARCHAR),
                    cm("Savepoints", ColumnType.VARCHAR));
            List<List<Object>> rows = new ArrayList<>();
            rows.add(Arrays.asList("InnoDB", "DEFAULT", "Supports transactions, row-level locking, and foreign keys", "YES", "YES", "YES"));
            rows.add(Arrays.asList("CSV", "YES", "CSV storage engine", "NO", "NO", "NO"));
            rows.add(Arrays.asList("MEMORY", "YES", "Hash based, stored in memory, useful for temporary tables", "NO", "NO", "NO"));
            rows.add(Arrays.asList("MyISAM", "YES", "MyISAM storage engine", "NO", "NO", "NO"));
            rows.add(Arrays.asList("NDB", "NO", "Clustered, fault-tolerant, memory-based tables", "NO", "NO", "NO"));
            return QueryResult.ofResultSet(cols, rows);
        }
        // SHOW CHARSET / SHOW CHARACTER SET：MySQL 返回 4 列（Charset/Description/Default collation/Maxlen）
        if (lower.matches("show\\s+charset.*") || lower.matches("show\\s+character\\s+set.*")) {
            List<ColumnMeta> cols = List.of(cm("Charset", ColumnType.VARCHAR), cm("Description", ColumnType.VARCHAR),
                    cm("Default collation", ColumnType.VARCHAR), cm("Maxlen", ColumnType.BIGINT));
            List<List<Object>> rows = new ArrayList<>();
            rows.add(Arrays.asList("utf8mb4", "UTF-8 Unicode", "utf8mb4_general_ci", 4L));
            rows.add(Arrays.asList("utf8", "UTF-8 Unicode", "utf8_general_ci", 3L));
            rows.add(Arrays.asList("latin1", "cp1252 West European", "latin1_swedish_ci", 1L));
            rows.add(Arrays.asList("binary", "Binary pseudo charset", "binary", 1L));
            rows.add(Arrays.asList("ascii", "US ASCII", "ascii_general_ci", 1L));
            return QueryResult.ofResultSet(cols, rows);
        }
        // SHOW COLLATION：MySQL 返回 6 列（Collation/Charset/Id/Default/Compiled/Sortlen）
        if (lower.matches("show\\s+collation.*")) {
            List<ColumnMeta> cols = List.of(cm("Collation", ColumnType.VARCHAR), cm("Charset", ColumnType.VARCHAR),
                    cm("Id", ColumnType.BIGINT), cm("Default", ColumnType.VARCHAR), cm("Compiled", ColumnType.VARCHAR),
                    cm("Sortlen", ColumnType.BIGINT));
            List<List<Object>> rows = new ArrayList<>();
            rows.add(Arrays.asList("utf8mb4_general_ci", "utf8mb4", 45L, "Yes", "Yes", 1L));
            rows.add(Arrays.asList("utf8mb4_bin", "utf8mb4", 46L, "", "Yes", 1L));
            rows.add(Arrays.asList("utf8_general_ci", "utf8", 33L, "Yes", "Yes", 1L));
            rows.add(Arrays.asList("latin1_swedish_ci", "latin1", 8L, "Yes", "Yes", 1L));
            rows.add(Arrays.asList("binary", "binary", 63L, "Yes", "Yes", 1L));
            return QueryResult.ofResultSet(cols, rows);
        }
        // SHOW PLUGINS：MySQL 返回 5 列（Name/Status/Type/Library/License）
        if (lower.matches("show\\s+plugins.*")) {
            List<ColumnMeta> cols = List.of(cm("Name", ColumnType.VARCHAR), cm("Status", ColumnType.VARCHAR),
                    cm("Type", ColumnType.VARCHAR), cm("Library", ColumnType.VARCHAR), cm("License", ColumnType.VARCHAR));
            return QueryResult.ofResultSet(cols, List.of());
        }
        // 其余 SHOW（privileges 等少数内省探测）：返回 2 列空结果集（Variable_name/Value），
        // 保证列数 >= 2，绝不再因"返回 1 列"触发"Query returns too few columns: 1 when expected 2"。
        return QueryResult.ofResultSet(List.of(cm("Variable_name", ColumnType.VARCHAR), cm("Value", ColumnType.VARCHAR)), List.of());
    }

    // ==================== DataGrip 2024.x 自定义列清单内省查询投影 ====================

    /** 投影 SELECT 列项：count(*) / 布尔比较表达式 / 简单列引用（可带限定符、AS 或隐式别名）。 */
    private record IsSelItem(String label, String col, String boolRhs, boolean lowerLhs, boolean count) {
    }

    /** 投影 WHERE 条件：eqVal != null 为等值比较；否则为 IS [NOT] NULL。 */
    private record IsCond(String col, boolean lowerLhs, String eqVal, boolean nullCheck, boolean notNull) {
    }

    /**
     * 按 DataGrip 查询的 SELECT 列清单投影 information_schema 视图：
     * 简单列引用 → 取同名列值；`x = 'lit'` / `lower(x) = 'lit'` → 布尔表达式返回整数 1/0
     * （真 MySQL 行为，DataGrip 按 getInt 读取，返回 "YES" 字符串会抛 NumberFormatException）；
     * COUNT(*) → 过滤后行数。缺失列（如 tables.table_collation / columns.column_type）
     * 按真 MySQL 语义给缺省值。任一列项/条件超出解析能力时返回 null（走旧固定布局）。
     */
    private QueryResult projectIsQuery(String sql, String lower) throws EngineException {
        Matcher fm = Pattern.compile("from\\s+information_schema\\.([a-zA-Z0-9_]+)", Pattern.CASE_INSENSITIVE).matcher(sql);
        if (!fm.find()) {
            return null;
        }
        String view = fm.group(1).toLowerCase();
        int selStart = lower.indexOf("select");
        if (selStart < 0) {
            return null;
        }
        String selList = sql.substring(selStart + 6, fm.start()).trim();
        if (selList.isEmpty() || selList.equals("*")) {
            return null;
        }
        if (Pattern.compile("\\b(distinct|union|group\\s+by|having)\\b").matcher(lower).find()) {
            return null;
        }
        String wherePart = null;
        Matcher wm = Pattern.compile("\\bwhere\\b(.*)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(sql.substring(fm.end()));
        if (wm.find()) {
            wherePart = wm.group(1);
            Matcher om = Pattern.compile("\\border\\s+by\\b.*", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(wherePart);
            if (om.find()) {
                wherePart = wherePart.substring(0, om.start());
            }
            wherePart = wherePart.trim();
        }
        List<IsSelItem> items = new ArrayList<>();
        for (String raw : splitTopLevel(selList, ',')) {
            IsSelItem item = parseIsSelItem(raw.trim());
            if (item == null) {
                return null;
            }
            items.add(item);
        }
        boolean hasCount = items.stream().anyMatch(IsSelItem::count);
        if (hasCount && items.size() > 1) {
            return null;
        }
        List<IsCond> conds = new ArrayList<>();
        if (wherePart != null && !wherePart.isEmpty()) {
            for (String raw : splitTopLevelWord(wherePart, "and")) {
                IsCond cond = parseIsCond(raw.trim());
                if (cond == null) {
                    return null;
                }
                conds.add(cond);
            }
        }
        String schema = extractEq(sql, "table_schema");
        String table = extractEq(sql, "table_name");
        QueryResult full = buildIsViewResult(lower, view, schema, table);
        List<String> names = new ArrayList<>();
        for (ColumnMeta c : full.columns()) {
            names.add(c.name().toLowerCase());
        }
        List<List<Object>> filtered = new ArrayList<>();
        for (List<Object> row : full.rows()) {
            if (isRowMatches(conds, view, names, row)) {
                filtered.add(row);
            }
        }
        if (hasCount) {
            return QueryResult.ofResultSet(List.of(cm(items.get(0).label(), ColumnType.BIGINT)),
                    List.of(List.of((long) filtered.size())));
        }
        List<ColumnMeta> cols = new ArrayList<>();
        for (IsSelItem item : items) {
            if (item.count() || item.boolRhs() != null) {
                cols.add(cm(item.label(), ColumnType.BIGINT));
            } else {
                int idx = names.indexOf(item.col());
                cols.add(cm(item.label(), idx >= 0 ? full.columns().get(idx).type() : ColumnType.VARCHAR));
            }
        }
        List<List<Object>> out = new ArrayList<>();
        for (List<Object> row : filtered) {
            List<Object> orow = new ArrayList<>();
            for (IsSelItem item : items) {
                orow.add(isItemValue(item, view, names, row));
            }
            out.add(orow);
        }
        return QueryResult.ofResultSet(cols, out);
    }

    /** 解析单个 SELECT 列项；无法解析（CASE/函数/NULL 字面量等）返回 null。 */
    private IsSelItem parseIsSelItem(String raw) {
        if (raw.isEmpty()) {
            return null;
        }
        String label = null;
        String it = raw;
        Matcher am = Pattern.compile("\\s+as\\s+`?([a-zA-Z0-9_]+)`?$", Pattern.CASE_INSENSITIVE).matcher(it);
        if (am.find()) {
            label = am.group(1);
            it = it.substring(0, am.start()).trim();
        }
        if (it.matches("(?i)count\\s*\\(\\s*\\*\\s*\\)") || it.matches("(?i)count\\s*\\(\\s*1\\s*\\)")) {
            return new IsSelItem(label != null ? label : "COUNT(*)", null, null, false, true);
        }
        // 布尔比较：[lower(] [限定符.]列 [)] = '字面量' [隐式别名]
        Matcher bm = Pattern.compile("^(?i)(lower\\s*\\(\\s*)?(?:`?[a-zA-Z0-9_]+`?\\.)?`?([a-zA-Z0-9_]+)`?\\s*\\)?\\s*=\\s*'([^']*)'(?:\\s+`?([a-zA-Z0-9_]+)`?)?$")
                .matcher(it);
        if (bm.matches()) {
            String lbl = label != null ? label
                    : (bm.group(4) != null ? bm.group(4) : it);
            return new IsSelItem(lbl, bm.group(2).toLowerCase(), bm.group(3), bm.group(1) != null, false);
        }
        // 简单列引用（可带限定符/反引号）
        if (it.matches("[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)?")) {
            String[] parts = it.split("\\.");
            String col = parts[parts.length - 1];
            return new IsSelItem(label != null ? label : col, col.toLowerCase(), null, false, false);
        }
        return null;
    }

    /** 解析单个 WHERE 条件；无法解析返回 null。 */
    private IsCond parseIsCond(String cd) {
        Matcher nm = Pattern.compile("^(?i)(?:`?[a-zA-Z0-9_]+`?\\.)?`?([a-zA-Z0-9_]+)`?\\s+is\\s+(not\\s+)?null$")
                .matcher(cd);
        if (nm.matches()) {
            return new IsCond(nm.group(1).toLowerCase(), false, null, true, nm.group(2) != null);
        }
        Matcher em = Pattern.compile("^(?i)(lower\\s*\\(\\s*)?(?:`?[a-zA-Z0-9_]+`?\\.)?`?([a-zA-Z0-9_]+)`?\\s*\\)?\\s*=\\s*'([^']*)'$")
                .matcher(cd);
        if (em.matches()) {
            return new IsCond(em.group(2).toLowerCase(), em.group(1) != null, em.group(3), false, false);
        }
        return null;
    }

    private boolean isRowMatches(List<IsCond> conds, String view, List<String> names, List<Object> row) {
        for (IsCond c : conds) {
            Object v = isColValue(names, row, c.col());
            if (v == null && !names.contains(c.col())) {
                // 视图布局里没有该列（如 statistics.index_schema）：按真 MySQL 语义兜底取值
                v = enrichIsMissing(view, c.col(), names, row);
            }
            if (c.nullCheck()) {
                boolean isNull = v == null;
                if (c.notNull() ? isNull : !isNull) {
                    return false;
                }
            } else {
                String s = v == null ? null : v.toString();
                if (s == null || !s.equalsIgnoreCase(c.eqVal())) {
                    return false;
                }
            }
        }
        return true;
    }

    private static Object isColValue(List<String> names, List<Object> row, String col) {
        int i = names.indexOf(col);
        return i < 0 ? null : row.get(i);
    }

    private Object isItemValue(IsSelItem item, String view, List<String> names, List<Object> row) {
        if (item.boolRhs() != null) {
            Object v = isColValue(names, row, item.col());
            String s = v == null ? null : (item.lowerLhs() ? v.toString().toLowerCase() : v.toString());
            return s != null && s.equalsIgnoreCase(item.boolRhs()) ? 1L : 0L;
        }
        int idx = names.indexOf(item.col());
        if (idx >= 0) {
            return row.get(idx);
        }
        return enrichIsMissing(view, item.col(), names, row);
    }

    /** DataGrip 请求了旧固定布局里没有的列（如 tables.table_collation / columns.column_type），
     *  按真 MySQL 该列的语义给缺省值。 */
    private Object enrichIsMissing(String view, String col, List<String> names, List<Object> row) {
        return switch (view + "." + col) {
            case "tables.table_collation" -> "VIEW".equals(isColValue(names, row, "table_type")) ? null : "utf8mb4_general_ci";
            case "tables.create_options" -> "";
            case "tables.auto_increment" -> null;
            case "tables.table_rows", "tables.avg_row_length", "tables.data_length", "tables.max_data_length",
                 "tables.index_length", "tables.data_free", "tables.checksum" -> 0L;
            case "tables.create_time", "tables.update_time", "tables.check_time" -> null;
            // columns 视图走 Connector/J 的 24 列布局（TABLE_CAT / TABLE_SCHEM / ...，按位置读取），
            // 里面没有 MySQL 原生的 TABLE_SCHEMA 列名。DataGrip 投影查询却用 table_schema 作 WHERE 条件，
            // 若不映射，`where table_schema='x'` 会因取不到值而把每一行都过滤掉（返回 0 行 → 表编辑器看不到列）。
            case "columns.table_schema" -> isColValue(names, row, "table_cat");
            case "columns.table_catalog" -> "def";
            case "columns.column_type" -> {
                Object tn = isColValue(names, row, "type_name");
                yield tn == null ? null : tn.toString().toLowerCase();
            }
            case "columns.column_default" -> isColValue(names, row, "column_def");
            case "columns.column_comment" -> isColValue(names, row, "remarks");
            case "statistics.index_schema" -> isColValue(names, row, "table_schema");
            case "columns.extra", "columns.generation_expression", "columns.column_key" -> "";
            case "columns.privileges" -> "select,insert,update,references";
            case "columns.collation_name" -> "utf8mb4_general_ci";
            case "columns.character_set_name" -> "utf8mb4";
            default -> null;
        };
    }

    /** 按分隔符切分顶层片段（跳过引号与括号内的分隔符）。 */
    private static List<String> splitTopLevel(String s, char sep) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (quote != 0) {
                cur.append(ch);
                if (ch == quote) {
                    quote = 0;
                }
                continue;
            }
            if (ch == '\'' || ch == '"' || ch == '`') {
                quote = ch;
            } else if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
            }
            if (ch == sep && depth == 0) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        if (!cur.toString().trim().isEmpty()) {
            out.add(cur.toString());
        }
        return out;
    }

    /** 按关键字（如 and）切分顶层片段（跳过引号与括号内）。 */
    private static List<String> splitTopLevelWord(String s, String word) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        char quote = 0;
        String lower = s.toLowerCase();
        int wl = word.length();
        int i = 0;
        while (i < s.length()) {
            char ch = s.charAt(i);
            if (quote != 0) {
                cur.append(ch);
                if (ch == quote) {
                    quote = 0;
                }
                i++;
                continue;
            }
            if (ch == '\'' || ch == '"' || ch == '`') {
                quote = ch;
                cur.append(ch);
                i++;
                continue;
            }
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
            }
            if (depth == 0 && Character.isLetter(ch)
                    && lower.regionMatches(i, word, 0, wl)
                    && (i == 0 || !isWordChar(s.charAt(i - 1)))
                    && (i + wl >= s.length() || !isWordChar(s.charAt(i + wl)))) {
                i += wl;
                out.add(cur.toString());
                cur.setLength(0);
                continue;
            }
            cur.append(ch);
            i++;
        }
        if (!cur.toString().trim().isEmpty()) {
            out.add(cur.toString());
        }
        return out;
    }

    private static boolean isWordChar(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_';
    }

    private QueryResult routeInfoSchema(String sql) throws EngineException {
        String lower = sql.toLowerCase();
        // DataGrip 2024.x 内省器发出"自定义 SELECT 列清单"的 information_schema 查询
        //（如 columns 只要 10 列、statistics 只要 9 列），且把 `x = 'YES'`、`lower(x)='yes'`
        // 这类布尔表达式列按【整数 1/0】读取（真 MySQL 行为）。旧固定布局在同名列下返回
        // 字符串 "YES"/"Yes"，DataGrip getInt 即抛 NumberFormatException（2026-09-19 实测）。
        // 可解析的列清单 → 按查询投影返回；解析不了（如 Connector/J 的 CASE WHEN 特征查询）
        // → 返回 null 走下方旧固定布局路径，保证既有已验证行为零回归。
        QueryResult projected = projectIsQuery(sql, lower);
        if (projected != null) {
            return projected;
        }
        String view;
        if (lower.contains("schemata")) {
            view = "schemata";
        } else if (lower.contains("collations")) {
            view = "collations";
        } else if (lower.contains("character_sets")) {
            view = "character_sets";
        } else if (lower.contains("views") && !lower.contains("table_constraints") && !lower.contains("key_column_usage")) {
            view = "views";
        } else if (lower.contains("referential_constraints")) {
            view = "referential_constraints";
        } else if (lower.contains("table_constraints")) {
            view = "table_constraints";
        } else if (lower.contains("tables")) {
            view = "tables";
        } else if (lower.contains("columns")) {
            view = "columns";
        } else if (lower.contains("engines")) {
            view = "engines";
        } else if (lower.contains("key_column_usage")) {
            view = "key_column_usage";
        } else if (lower.contains("statistics")) {
            view = "statistics";
        } else {
            // 其它 information_schema 视图（events/triggers/routines/parameters/partitions 等）：
            // 返回 0 行结果集（列名取自 SELECT 列表），避免 Navicat/DataGrip 等工具探测时报错中断连接。
            // 注意：这类查询 DataGrip 按 SELECT 列表取列，selectListLabels 列数与其一致，不会触发"列数不符"。
            return QueryResult.ofResultSet(selectListLabels(sql), List.of());
        }
        String schema = extractEq(sql, "table_schema");
        String table = extractEq(sql, "table_name");
        return buildIsViewResult(lower, view, schema, table);
    }

    /** 旧的固定布局 I_S 视图构建器：Connector/J 按位置读取的复杂特征查询（CASE/NULL 别名等）仍走此路径。 */
    private QueryResult buildIsViewResult(String lower, String view, String schema, String table) throws EngineException {
        return switch (view) {
            case "schemata" -> {
                List<ColumnMeta> cols = List.of(cm("CATALOG_NAME", ColumnType.VARCHAR), cm("SCHEMA_NAME", ColumnType.VARCHAR),
                        cm("DEFAULT_CHARACTER_SET_NAME", ColumnType.VARCHAR), cm("DEFAULT_COLLATION_NAME", ColumnType.VARCHAR),
                        cm("SQL_PATH", ColumnType.VARCHAR));
                List<List<Object>> rows = new ArrayList<>();
                for (String d : engine.listDatabases()) {
                    rows.add(Arrays.asList("def", d, "utf8mb4", "utf8mb4_general_ci", null)); // List.of 不允许 null，SQL_PATH 为 NULL 会 NPE
                }
                yield QueryResult.ofResultSet(cols, rows);
            }
            case "tables" -> {
                // Connector/J（DataGrip/Navicat 建连接后）的 DatabaseMetaData.getTables 会发一条特征查询：
                //   SELECT TABLE_SCHEMA AS TABLE_CAT, NULL AS TABLE_SCHEM, TABLE_NAME, CASE WHEN TABLE_TYPE=... AS TABLE_TYPE,
                //   TABLE_COMMENT AS REMARKS, NULL AS TYPE_CAT, ... FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME LIKE '%'
                // 它按【位置】读取结果且 TABLE_TYPE 期望 JDBC 词汇（TABLE/VIEW），不认识 MySQL 原生的 "BASE TABLE"，
                // 导致 DataGrip 刷新时表列表为空。命中特征（AS TABLE_CAT 别名）时改回 10 列 JDBC 布局。
                if (lower.contains("as table_cat") || lower.contains("as table_type")) {
                    List<ColumnMeta> cols10 = List.of(cm("TABLE_CAT", ColumnType.VARCHAR), cm("TABLE_SCHEM", ColumnType.VARCHAR),
                            cm("TABLE_NAME", ColumnType.VARCHAR), cm("TABLE_TYPE", ColumnType.VARCHAR), cm("REMARKS", ColumnType.VARCHAR),
                            cm("TYPE_CAT", ColumnType.VARCHAR), cm("TYPE_SCHEM", ColumnType.VARCHAR), cm("TYPE_NAME", ColumnType.VARCHAR),
                            cm("SELF_REFERENCING_COL_NAME", ColumnType.VARCHAR), cm("REF_GENERATION", ColumnType.VARCHAR));
                    List<List<Object>> rows10 = new ArrayList<>();
                    for (String d : engine.listDatabases()) {
                        for (String t : engine.listTables(d)) {
                            rows10.add(Arrays.asList(d, null, t, "TABLE", engine.tableComment(d, t),
                                    null, null, null, null, null));
                        }
                        for (List<Object> v : engine.listTablesWithType(d)) {
                            if ("VIEW".equals(v.get(1))) {
                                rows10.add(Arrays.asList(d, null, v.get(0), "VIEW", "", null, null, null, null, null));
                            }
                        }
                    }
                    yield QueryResult.ofResultSet(cols10, rows10);
                }
                List<ColumnMeta> cols = List.of(cm("TABLE_CATALOG", ColumnType.VARCHAR), cm("TABLE_SCHEMA", ColumnType.VARCHAR),
                        cm("TABLE_NAME", ColumnType.VARCHAR), cm("TABLE_TYPE", ColumnType.VARCHAR), cm("ENGINE", ColumnType.VARCHAR),
                        cm("VERSION", ColumnType.BIGINT), cm("ROW_FORMAT", ColumnType.VARCHAR), cm("TABLE_COMMENT", ColumnType.VARCHAR));
                String s = schema != null ? schema : session.getCurrentDatabase();
                if (s == null || !engine.databaseExists(s)) {
                    yield QueryResult.ofResultSet(cols, List.of());
                }
                List<String> tabs = engine.listTables(s);
                List<List<Object>> rows = new ArrayList<>();
                for (String t : tabs) {
                    rows.add(List.of("def", s, t, "BASE TABLE", "KUN-DB", 10L, "Fixed", engine.tableComment(s, t)));
                }
                for (List<Object> v : engine.listTablesWithType(s)) {
                    if ("VIEW".equals(v.get(1))) {
                        // 用 Arrays.asList 而非 List.of：TABLE_TYPE 之外的列（ENGINE/VERSION/ROW_FORMAT/COMMENT）为 NULL，List.of 不允许 null 会 NPE
                        rows.add(Arrays.asList("def", s, v.get(0), "VIEW", null, null, null, ""));  // 视图无表注释
                    }
                }
                yield QueryResult.ofResultSet(cols, rows);
            }
            case "columns" -> {
                // Connector/J 的 getColumns 直接发一条 24 列的 CASE/别名查询（见 dev-tools/_dgq.log 中
                // "FROM INFORMATION_SCHEMA.COLUMNS" 那一行），并按【列位置】读取结果（而非按标签）。
                // 因此本分支必须返回与那条查询【完全一致的 24 列布局与类型】，否则第 7 列 COLUMN_SIZE
                // 会被误当成 IS_NULLABLE('NO') 而抛 NumberFormatException。下面按 Connector/J 的 CASE 逻辑
                // 由引擎列定义直接计算 24 个值，列序/类型严格对齐。
                String s = schema != null ? schema : session.getCurrentDatabase();
                List<ColumnMeta> cols = List.of(
                        cm("TABLE_CAT", ColumnType.VARCHAR), cm("TABLE_SCHEM", ColumnType.VARCHAR),
                        cm("TABLE_NAME", ColumnType.VARCHAR), cm("COLUMN_NAME", ColumnType.VARCHAR),
                        cm("DATA_TYPE", ColumnType.BIGINT), cm("TYPE_NAME", ColumnType.VARCHAR),
                        cm("COLUMN_SIZE", ColumnType.BIGINT), cm("BUFFER_LENGTH", ColumnType.BIGINT),
                        cm("DECIMAL_DIGITS", ColumnType.BIGINT), cm("NUM_PREC_RADIX", ColumnType.BIGINT),
                        cm("NULLABLE", ColumnType.BIGINT), cm("REMARKS", ColumnType.VARCHAR),
                        cm("COLUMN_DEF", ColumnType.VARCHAR), cm("SQL_DATA_TYPE", ColumnType.BIGINT),
                        cm("SQL_DATETIME_SUB", ColumnType.BIGINT), cm("CHAR_OCTET_LENGTH", ColumnType.BIGINT),
                        cm("ORDINAL_POSITION", ColumnType.BIGINT), cm("IS_NULLABLE", ColumnType.VARCHAR),
                        cm("SCOPE_CATALOG", ColumnType.VARCHAR), cm("SCOPE_SCHEMA", ColumnType.VARCHAR),
                        cm("SCOPE_TABLE", ColumnType.VARCHAR), cm("SOURCE_DATA_TYPE", ColumnType.BIGINT),
                        cm("IS_AUTOINCREMENT", ColumnType.VARCHAR), cm("IS_GENERATEDCOLUMN", ColumnType.VARCHAR));
                List<List<Object>> rows = new ArrayList<>();
                if (s != null && engine.databaseExists(s)) {
                    List<String> tbls = (table == null || table.equals("%")) ? engine.listTables(s) : List.of(table);
                    for (String t : tbls) {
                        try {
                            List<ColumnMeta> defs = engine.describe(s, t);
                            int ord = 1;
                            for (ColumnMeta c : defs) {
                                rows.add(Arrays.asList(iColumnsRow24(s, t, c, ord)));
                                ord++;
                            }
                        } catch (EngineException ignore) {
                        }
                    }
                }
                yield QueryResult.ofResultSet(cols, rows);
            }
            case "engines" -> {
                // 标准 information_schema.ENGINES 列；返回 InnoDB 等常见引擎（NDB=NO），
                // 供 Navicat/ConnectorJ 连接期 COUNT(*) 探测正常返回（ndb=0）。
                List<ColumnMeta> cols = List.of(cm("ENGINE", ColumnType.VARCHAR), cm("SUPPORT", ColumnType.VARCHAR),
                        cm("COMMENT", ColumnType.VARCHAR), cm("TRANSACTIONS", ColumnType.VARCHAR), cm("XA", ColumnType.VARCHAR),
                        cm("SAVEPOINTS", ColumnType.VARCHAR));
                List<List<Object>> rows = new ArrayList<>();
                rows.add(Arrays.asList("InnoDB", "DEFAULT", "Supports transactions, row-level locking, and foreign keys", "YES", "YES", "YES"));
                rows.add(Arrays.asList("CSV", "YES", "CSV storage engine", "NO", "NO", "NO"));
                rows.add(Arrays.asList("MEMORY", "YES", "Hash based, stored in memory, useful for temporary tables", "NO", "NO", "NO"));
                rows.add(Arrays.asList("MyISAM", "YES", "MyISAM storage engine", "NO", "NO", "NO"));
                rows.add(Arrays.asList("NDB", "NO", "Clustered, fault-tolerant, memory-based tables", "NO", "NO", "NO"));
                yield QueryResult.ofResultSet(cols, rows);
            }
            case "collations" -> {
                // 标准 information_schema.COLLATIONS 列（Connector-J 建连时取排序规则缓存）
                List<ColumnMeta> cols = List.of(cm("COLLATION_NAME", ColumnType.VARCHAR), cm("CHARACTER_SET_NAME", ColumnType.VARCHAR),
                        cm("ID", ColumnType.BIGINT), cm("IS_DEFAULT", ColumnType.VARCHAR), cm("IS_COMPILED", ColumnType.VARCHAR),
                        cm("SORTLEN", ColumnType.BIGINT));
                List<List<Object>> rows = new ArrayList<>();
                rows.add(Arrays.asList("utf8mb4_general_ci", "utf8mb4", 45L, "Yes", "Yes", 1L));
                rows.add(Arrays.asList("utf8mb4_bin", "utf8mb4", 46L, "", "Yes", 1L));
                rows.add(Arrays.asList("utf8_general_ci", "utf8", 33L, "Yes", "Yes", 1L));
                rows.add(Arrays.asList("latin1_swedish_ci", "latin1", 8L, "Yes", "Yes", 1L));
                rows.add(Arrays.asList("binary", "binary", 63L, "Yes", "Yes", 1L));
                yield QueryResult.ofResultSet(cols, rows);
            }
            case "character_sets" -> {
                // 标准 information_schema.CHARACTER_SETS 列（Connector-J 建连时取字符集缓存）
                List<ColumnMeta> cols = List.of(cm("CHARACTER_SET_NAME", ColumnType.VARCHAR), cm("DEFAULT_COLLATE_NAME", ColumnType.VARCHAR),
                        cm("DESCRIPTION", ColumnType.VARCHAR), cm("MAXLEN", ColumnType.BIGINT));
                List<List<Object>> rows = new ArrayList<>();
                rows.add(Arrays.asList("utf8mb4", "utf8mb4_general_ci", "UTF-8 Unicode", 4L));
                rows.add(Arrays.asList("utf8", "utf8_general_ci", "UTF-8 Unicode", 3L));
                rows.add(Arrays.asList("latin1", "latin1_swedish_ci", "cp1252 West European", 1L));
                rows.add(Arrays.asList("binary", "binary", "Binary pseudo charset", 1L));
                rows.add(Arrays.asList("ascii", "ascii_general_ci", "US ASCII", 1L));
                yield QueryResult.ofResultSet(cols, rows);
            }
            case "views" -> {
                // 标准 information_schema.VIEWS 列（DataGrip/Navicat 取视图定义走此视图）。
                List<ColumnMeta> cols = List.of(cm("TABLE_CATALOG", ColumnType.VARCHAR), cm("TABLE_SCHEMA", ColumnType.VARCHAR),
                        cm("TABLE_NAME", ColumnType.VARCHAR), cm("VIEW_DEFINITION", ColumnType.TEXT), cm("CHECK_OPTION", ColumnType.VARCHAR),
                        cm("IS_UPDATABLE", ColumnType.VARCHAR), cm("DEFINER", ColumnType.VARCHAR), cm("SECURITY_TYPE", ColumnType.VARCHAR),
                        cm("CHARACTER_SET_CLIENT", ColumnType.VARCHAR), cm("COLLATION_CONNECTION", ColumnType.VARCHAR));
                String s = schema != null ? schema : session.getCurrentDatabase();
                List<List<Object>> rows = new ArrayList<>();
                if (s != null && engine.databaseExists(s)) {
                    for (List<String> v : engine.listViews(s)) {
                        rows.add(Arrays.asList("def", s, v.get(0), v.get(1), "NONE", "NO", "root@localhost", "DEFINER",
                                "utf8mb4", "utf8mb4_general_ci"));
                    }
                }
                yield QueryResult.ofResultSet(cols, rows);
            }
            case "table_constraints" -> {
                // 标准 information_schema.TABLE_CONSTRAINTS 列（DataGrip 取主键/外键约束走此视图）。
                List<ColumnMeta> cols = List.of(cm("CONSTRAINT_CATALOG", ColumnType.VARCHAR), cm("CONSTRAINT_SCHEMA", ColumnType.VARCHAR),
                        cm("CONSTRAINT_NAME", ColumnType.VARCHAR), cm("TABLE_SCHEMA", ColumnType.VARCHAR), cm("TABLE_NAME", ColumnType.VARCHAR),
                        cm("CONSTRAINT_TYPE", ColumnType.VARCHAR), cm("ENFORCED", ColumnType.VARCHAR));
                String s = schema != null ? schema : session.getCurrentDatabase();
                List<List<Object>> rows = new ArrayList<>();
                if (s != null && engine.databaseExists(s)) {
                    List<String> tbls = (table == null || table.equals("%")) ? engine.listTables(s) : List.of(table);
                    for (String t : tbls) {
                        try {
                            List<ColumnMeta> defs = engine.describe(s, t);
                            boolean hasPk = defs.stream().anyMatch(ColumnMeta::primaryKey);
                            if (hasPk) {
                                rows.add(Arrays.asList("def", s, "PRIMARY", s, t, "PRIMARY KEY", "YES"));
                            }
                        } catch (EngineException ignore) {
                        }
                    }
                }
                yield QueryResult.ofResultSet(cols, rows);
            }
            case "referential_constraints" -> {
                // 外键约束视图：列出真实外键（KUN-DB 引擎已支持 CREATE TABLE ... FOREIGN KEY）。
                List<ColumnMeta> cols = List.of(cm("CONSTRAINT_CATALOG", ColumnType.VARCHAR), cm("CONSTRAINT_SCHEMA", ColumnType.VARCHAR),
                        cm("CONSTRAINT_NAME", ColumnType.VARCHAR), cm("UNIQUE_CONSTRAINT_CATALOG", ColumnType.VARCHAR),
                        cm("UNIQUE_CONSTRAINT_SCHEMA", ColumnType.VARCHAR), cm("UNIQUE_CONSTRAINT_NAME", ColumnType.VARCHAR),
                        cm("MATCH_OPTION", ColumnType.VARCHAR), cm("UPDATE_RULE", ColumnType.VARCHAR), cm("DELETE_RULE", ColumnType.VARCHAR),
                        cm("TABLE_NAME", ColumnType.VARCHAR), cm("REFERENCED_TABLE_NAME", ColumnType.VARCHAR));
                String s = schema != null ? schema : session.getCurrentDatabase();
                List<List<Object>> rows = new ArrayList<>();
                if (s != null && engine.databaseExists(s)) {
                    List<String> tbls = (table == null || table.equals("%")) ? engine.listTables(s) : List.of(table);
                    for (String t : tbls) {
                        try {
                            int idx = 1;
                            for (String[] fk : engine.listForeignKeys(s, t)) {
                                String fkName = t + "_ibfk_" + idx;
                                rows.add(Arrays.asList("def", s, fkName, "def", s, "PRIMARY",
                                        "NONE", "RESTRICT", "RESTRICT", t, fk[1]));
                                idx++;
                            }
                        } catch (EngineException ignore) {
                        }
                    }
                }
                yield QueryResult.ofResultSet(cols, rows);
            }
            case "key_column_usage" -> {
                // 标准 information_schema.KEY_COLUMN_USAGE 列（12 列）；主键行 + 外键行（REFERENCED_* 非空）。
                // DataGrip/Navicat 的 getPrimaryKeys/getImportedKeys 均依赖此视图按位置读取。
                String s = schema != null ? schema : session.getCurrentDatabase();
                List<ColumnMeta> cols = List.of(cm("CONSTRAINT_CATALOG", ColumnType.VARCHAR), cm("CONSTRAINT_SCHEMA", ColumnType.VARCHAR),
                        cm("CONSTRAINT_NAME", ColumnType.VARCHAR), cm("TABLE_CATALOG", ColumnType.VARCHAR), cm("TABLE_SCHEMA", ColumnType.VARCHAR),
                        cm("TABLE_NAME", ColumnType.VARCHAR), cm("COLUMN_NAME", ColumnType.VARCHAR), cm("ORDINAL_POSITION", ColumnType.BIGINT),
                        cm("POSITION_IN_UNIQUE_CONSTRAINT", ColumnType.BIGINT), cm("REFERENCED_TABLE_SCHEMA", ColumnType.VARCHAR),
                        cm("REFERENCED_TABLE_NAME", ColumnType.VARCHAR), cm("REFERENCED_COLUMN_NAME", ColumnType.VARCHAR));
                List<List<Object>> rows = new ArrayList<>();
                if (s != null && engine.databaseExists(s)) {
                    List<String> tbls = (table == null || table.equals("%")) ? engine.listTables(s) : List.of(table);
                    for (String t : tbls) {
                        try {
                            List<ColumnMeta> defs = engine.describe(s, t);
                            int ord = 1;
                            for (ColumnMeta c : defs) {
                                if (c.primaryKey()) {
                                    rows.add(Arrays.asList("def", s, "PRIMARY", "def", s, t, c.name(), (long) ord, null, null, null, null));
                                    ord++;
                                }
                            }
                            // 外键列：REFERENCED 信息非空，供 getImportedKeys / 外键树显示
                            int fkIdx = 1;
                            for (String[] fk : engine.listForeignKeys(s, t)) {
                                rows.add(Arrays.asList("def", s, t + "_ibfk_" + fkIdx, "def", s, t,
                                        fk[0], (long) fkIdx, 1L, s, fk[1], fk[2]));
                                fkIdx++;
                            }
                        } catch (EngineException ignore) {
                        }
                    }
                }
                yield QueryResult.ofResultSet(cols, rows);
            }
            default -> { // statistics / key_column_usage：主键 + 二级索引，按 Connector/J 探测类型返回不同列布局
                // Connector/J 的 getPrimaryKeys 走 STATISTICS(INDEX_NAME='PRIMARY') 期望 6 列；
                // getIndexInfo 走 STATISTICS 期望 13 列；SHOW INDEX 等通用场景期望 17 列（MySQL statistics 标准）。
                // 三者都按【列位置】读取，故分别给出对应布局。注意：行用 Arrays.asList（允许 null），
                // 绝不可用 List.of（List.of 含 null 会直接 NPE，导致 getPrimaryKeys/getIndexInfo 报"服务端异常"）。
                String s = schema != null ? schema : session.getCurrentDatabase();
                boolean isPk = lower.contains("pk_name");
                boolean isIdx = lower.contains("non_unique") && lower.contains("index_qualifier");
                List<ColumnMeta> cols;
                if (isPk) {
                    cols = List.of(cm("TABLE_CAT", ColumnType.VARCHAR), cm("TABLE_SCHEM", ColumnType.VARCHAR),
                            cm("TABLE_NAME", ColumnType.VARCHAR), cm("COLUMN_NAME", ColumnType.VARCHAR),
                            cm("KEY_SEQ", ColumnType.BIGINT), cm("PK_NAME", ColumnType.VARCHAR));
                } else if (isIdx) {
                    cols = List.of(cm("TABLE_CAT", ColumnType.VARCHAR), cm("TABLE_SCHEM", ColumnType.VARCHAR),
                            cm("TABLE_NAME", ColumnType.VARCHAR), cm("NON_UNIQUE", ColumnType.BIGINT),
                            cm("INDEX_QUALIFIER", ColumnType.VARCHAR), cm("INDEX_NAME", ColumnType.VARCHAR),
                            cm("TYPE", ColumnType.BIGINT), cm("ORDINAL_POSITION", ColumnType.BIGINT),
                            cm("COLUMN_NAME", ColumnType.VARCHAR), cm("ASC_OR_DESC", ColumnType.VARCHAR),
                            cm("CARDINALITY", ColumnType.BIGINT), cm("PAGES", ColumnType.BIGINT),
                            cm("FILTER_CONDITION", ColumnType.VARCHAR));
                } else {
                    cols = List.of(cm("TABLE_CATALOG", ColumnType.VARCHAR), cm("TABLE_SCHEMA", ColumnType.VARCHAR),
                            cm("TABLE_NAME", ColumnType.VARCHAR), cm("NON_UNIQUE", ColumnType.BIGINT), cm("INDEX_NAME", ColumnType.VARCHAR),
                            cm("SEQ_IN_INDEX", ColumnType.BIGINT), cm("COLUMN_NAME", ColumnType.VARCHAR), cm("COLLATION", ColumnType.VARCHAR),
                            cm("CARDINALITY", ColumnType.BIGINT), cm("SUB_PART", ColumnType.BIGINT), cm("PACKED", ColumnType.VARCHAR),
                            cm("NULLABLE", ColumnType.VARCHAR), cm("INDEX_TYPE", ColumnType.VARCHAR), cm("COMMENT", ColumnType.VARCHAR),
                            cm("INDEX_COMMENT", ColumnType.VARCHAR), cm("IS_VISIBLE", ColumnType.VARCHAR), cm("EXPRESSION", ColumnType.VARCHAR));
                }
                List<List<Object>> rows = new ArrayList<>();
                if (s != null && engine.databaseExists(s)) {
                    List<String> tbls = (table == null || table.equals("%")) ? engine.listTables(s) : List.of(table);
                    for (String t : tbls) {
                        try {
                            List<ColumnMeta> defs = engine.describe(s, t);
                            int seq = 1;
                            for (ColumnMeta c : defs) {
                                if (c.primaryKey()) {
                                    if (isPk) {
                                        rows.add(Arrays.asList(s, null, t, c.name(), (long) seq, "PRIMARY"));
                                    } else if (isIdx) {
                                        rows.add(Arrays.asList(s, null, t, 0L, null, "PRIMARY", 3L, (long) seq, c.name(), "A", 0L, 0L, null));
                                    } else {
                                        rows.add(Arrays.asList("def", s, t, 0L, "PRIMARY", (long) seq, c.name(), "A", 0L,
                                                null, "", c.nullable() ? "YES" : "NO", "BTREE", "", "", "YES", null));
                                    }
                                    seq++;
                                }
                            }
                            // 二级索引（CREATE INDEX 建立的内存哈希索引）：仅通用 statistics / getIndexInfo 场景输出
                            for (List<String> idx : engine.listIndexes(s, t)) {
                                if (!isPk) {
                                    if (isIdx) {
                                        rows.add(Arrays.asList(s, null, t, 0L, null, idx.get(0), 3L, 1L, idx.get(1), "A", 0L, 0L, null));
                                    } else {
                                        rows.add(Arrays.asList("def", s, t, 0L, idx.get(0), 1L, idx.get(1), "A", 0L,
                                                null, "", "YES", "HASH", "", "", "YES", null));
                                    }
                                }
                            }
                        } catch (EngineException ignore) {
                        }
                    }
                }
                yield QueryResult.ofResultSet(cols, rows);
            }
        };
    }

    private QueryResult metaColumns(String db, String table, boolean full) throws EngineException {
        List<ColumnMeta> defs = engine.describe(db, table);
        List<ColumnMeta> cols = showColumnsCols(full);
        List<List<Object>> rows = new ArrayList<>();
        for (ColumnMeta c : defs) {
            boolean str = c.type() == ColumnType.VARCHAR || c.type() == ColumnType.CHAR || c.type() == ColumnType.TEXT;
            if (full) {
                rows.add(Arrays.asList(c.name(), c.type().name(), str ? "utf8mb4_general_ci" : null,
                        c.nullable() ? "YES" : "NO", c.primaryKey() ? "PRI" : "", "", "",
                        "select,insert,update,references", c.comment()));
            } else {
                rows.add(List.of(c.name(), c.type().name(), c.nullable() ? "YES" : "NO", c.primaryKey() ? "PRI" : "", "", ""));
            }
        }
        return QueryResult.ofResultSet(cols, rows);
    }

    /** SHOW [FULL] COLUMNS 的空结果骨架（表名通配 % / 表不存在时用），列定义与 metaColumns 共用一份。 */
    private QueryResult metaColumnsEmpty(boolean full) {
        return QueryResult.ofResultSet(showColumnsCols(full), List.of());
    }

    /**
     * SHOW [FULL] COLUMNS 的列定义（metaColumns 与 metaColumnsEmpty 共用一份，
     * 列数须与 MySQL 标准一致，否则 DataGrip 取列信息会报 "too few columns"；FULL 比非 FULL 多 Privileges / Comment 两列）。
     */
    private static List<ColumnMeta> showColumnsCols(boolean full) {
        return full
                ? List.of(cm("Field", ColumnType.VARCHAR), cm("Type", ColumnType.VARCHAR), cm("Collation", ColumnType.VARCHAR),
                        cm("Null", ColumnType.VARCHAR), cm("Key", ColumnType.VARCHAR), cm("Default", ColumnType.VARCHAR),
                        cm("Extra", ColumnType.VARCHAR), cm("Privileges", ColumnType.VARCHAR), cm("Comment", ColumnType.VARCHAR))
                : List.of(cm("Field", ColumnType.VARCHAR), cm("Type", ColumnType.VARCHAR),
                        cm("Null", ColumnType.VARCHAR), cm("Key", ColumnType.VARCHAR), cm("Default", ColumnType.VARCHAR),
                        cm("Extra", ColumnType.VARCHAR));
    }

    private QueryResult metaIndex(String db, String table) throws EngineException {
        List<ColumnMeta> defs = engine.describe(db, table);
        List<ColumnMeta> cols = showIndexCols();
        List<List<Object>> rows = new ArrayList<>();
        int seq = 1;
        for (ColumnMeta c : defs) {
            if (c.primaryKey()) {
                rows.add(List.of(table, 0L, "PRIMARY", (long) seq, c.name(), "A", 0L, "", "", c.nullable() ? "YES" : "NO",
                        "BTREE", "", "", "YES", ""));
                seq++;
            }
        }
        // 二级索引（CREATE INDEX 建立的内存哈希索引）
        for (List<String> idx : engine.listIndexes(db, table)) {
            rows.add(List.of(table, 0L, idx.get(0), 1L, idx.get(1), "A", 0L, "", "", "YES",
                    "HASH", "", "", "YES", ""));
            seq++;
        }
        return QueryResult.ofResultSet(cols, rows);
    }

    /** SHOW [INDEX|KEYS] 的空结果骨架（表名通配 % / 表不存在时用），列定义与 metaIndex 共用一份。 */
    private QueryResult metaIndexEmpty() {
        return QueryResult.ofResultSet(showIndexCols(), List.of());
    }

    /**
     * 把引擎内部列定义翻译成 Connector/J getColumns 需要的【24 列】布局（列序、类型严格对齐
     * dev-tools/_dgq.log 中 "FROM INFORMATION_SCHEMA.COLUMNS" 那条查询的 SELECT 列表）。
     * 关键：Connector/J 按列位置读取，第 5 列 DATA_TYPE 必须是 JDBC 类型代码（整数），
     * 第 7 列 COLUMN_SIZE 必须是整数，第 11 列 NULLABLE 必须是 0/1/2（整数），否则会抛 NumberFormatException。
     */
    private static Object[] iColumnsRow24(String schema, String table, ColumnMeta c, int ord) {
        String typeName = switch (c.type()) {
            case INT -> "INT";
            case BIGINT -> "BIGINT";
            case DOUBLE -> "DOUBLE";
            case DECIMAL -> "DECIMAL";
            case VARCHAR -> "VARCHAR";
            case CHAR -> "CHAR";
            case BOOLEAN -> "TINYINT";
            case DATE -> "DATE";
            case DATETIME -> "DATETIME";
            case TEXT -> "TEXT";
            default -> c.type().name().toUpperCase();
        };
        long jdbc = switch (c.type()) {
            case INT -> 4L;
            case BIGINT -> -5L;
            case DOUBLE -> 8L;
            case DECIMAL -> 3L;
            case VARCHAR -> 12L;
            case CHAR -> 1L;
            case BOOLEAN -> -7L;
            case DATE -> 91L;
            case DATETIME -> 93L;
            case TEXT -> -1L;
            default -> 1111L;
        };
        long columnSize = switch (c.type()) {
            case VARCHAR -> 255L;
            case CHAR -> 1L;
            case TEXT -> 255L;
            case INT -> 10L;
            case BIGINT -> 19L;
            case DOUBLE -> 22L;
            case DECIMAL -> 10L;
            case BOOLEAN -> 1L;
            case DATE -> 10L;
            case DATETIME -> 19L;
            default -> 0L;
        };
        Long decimalDigits = switch (c.type()) {
            case DOUBLE -> 0L;
            default -> null;
        };
        boolean str = c.type() == ColumnType.VARCHAR || c.type() == ColumnType.CHAR || c.type() == ColumnType.TEXT;
        Long charOctet = str ? (c.type() == ColumnType.CHAR ? 4L : 1020L) : null;
        return new Object[]{
                schema, null, table, c.name(), jdbc, typeName, columnSize, 65535L, decimalDigits, 10L,
                c.nullable() ? 1L : 0L,
                // 第 12 列 REMARKS：Connector/J getColumns 直接读它；DataGrip 投影列 column_comment
                // 也经 enrichIsMissing(...) 映射到这里，故列注释必须落在这一格。
                c.comment(), "", 0L, 0L, charOctet, (long) ord,
                c.nullable() ? "YES" : "NO", null, null, null, null, "NO", "NO"
        };
    }

    // ===================== 工具 =====================

    private String resolveDb(String db) throws EngineException {
        if (db != null && !db.isEmpty()) {
            return db;
        }
        String cur = session.getCurrentDatabase();
        if (cur == null) {
            throw new EngineException(ErrorCode.UNKNOWN_DATABASE, "未选择数据库，请先 USE db");
        }
        return cur;
    }

    private static String extractEq(String sql, String key) {
        // 容忍 lower(...) 包裹与表别名前缀：lower(c.table_schema) = 'x' / t.table_name = 'y' / table_schema = 'z'
        Matcher m = Pattern.compile("(?i)(?:lower\\(\\s*)?(?:[a-z_]*\\.\\s*)?" + key + "\\s*\\)?\\s*=\\s*'([^']+)'").matcher(sql);
        return m.find() ? m.group(1) : null;
    }

    /**
     * 从 SELECT 语句提取列标签（用于 0 行元数据结果集，如 mysql.* 系统库 / information_schema 次要视图）：
     * 取 SELECT 与顶层 FROM 之间的列表，按括号深度 0 的逗号切分，
     * 每项取尾部别名（`x AS a` / `x a`）或表达式原文作列名。
     */
    private static List<ColumnMeta> selectListLabels(String sql) {
        Matcher mf = Pattern.compile("\\bfrom\\b", Pattern.CASE_INSENSITIVE).matcher(sql);
        if (!mf.find()) {
            return List.of(cm("Result", ColumnType.VARCHAR));
        }
        List<ColumnMeta> cols = new ArrayList<>();
        String list = sql.substring(sql.toLowerCase().indexOf("select") + 6, mf.start()).trim();
        int depth = 0;
        boolean inS = false, inD = false;
        List<String> items = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < list.length(); i++) {
            char c = list.charAt(i);
            if (inS) {
                cur.append(c);
                if (c == '\'') {
                    inS = false;
                }
            } else if (inD) {
                cur.append(c);
                if (c == '"') {
                    inD = false;
                }
            } else if (c == '\'') {
                inS = true;
                cur.append(c);
            } else if (c == '"') {
                inD = true;
                cur.append(c);
            } else if (c == '(') {
                depth++;
                cur.append(c);
            } else if (c == ')') {
                depth--;
                cur.append(c);
            } else if (c == ',' && depth == 0) {
                items.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        items.add(cur.toString());
        for (String item : items) {
            String it = item.trim();
            if (it.isEmpty()) {
                continue;
            }
            // 尾部别名：... AS a 或 ... a（尾部是标识符时）；表达式以 ) 或引号结尾则用原文
            Matcher ma = Pattern.compile("(?is)^(.*?\\bas\\s+)?([a-zA-Z_][a-zA-Z0-9_]*)\\s*$").matcher(it);
            String label = it;
            if (ma.matches() && ma.group(1) != null) {
                label = ma.group(2); // 显式 AS 别名
            } else if (it.matches("(?is).*[a-zA-Z0-9_)']\\s+[a-zA-Z_][a-zA-Z0-9_]*")
                    && !it.matches("(?is).*\\)\\s*$")) {
                int sp = it.lastIndexOf(' ');
                label = it.substring(sp + 1).trim(); // 隐式别名
            }
            cols.add(cm(label, ColumnType.VARCHAR));
        }
        if (cols.isEmpty()) {
            cols.add(cm("Result", ColumnType.VARCHAR));
        }
        return cols;
    }

    private static String stripBackticks(String s) {
        return s.replace('`', ' ');
    }

    private static String stripComments(String s) {
        return s.replaceAll("/\\*.*?\\*/", " ").replaceAll("--.*?(\n|$)", " ");
    }

    private static ColumnMeta cm(String n, ColumnType t) {
        return new ColumnMeta(n, t, false, false);
    }

    /** SHOW [INDEX|KEYS] 的 15 列定义（MySQL 8：末列 Expression 为 8.0 新增；DataGrip 按列名读取，缺列会触发内省异常）。 */
    private static List<ColumnMeta> showIndexCols() {
        return List.of(cm("Table", ColumnType.VARCHAR), cm("Non_unique", ColumnType.BIGINT),
                cm("Key_name", ColumnType.VARCHAR), cm("Seq_in_index", ColumnType.BIGINT), cm("Column_name", ColumnType.VARCHAR),
                cm("Collation", ColumnType.VARCHAR), cm("Cardinality", ColumnType.BIGINT), cm("Sub_part", ColumnType.VARCHAR),
                cm("Packed", ColumnType.VARCHAR), cm("Null", ColumnType.VARCHAR), cm("Index_type", ColumnType.VARCHAR),
                cm("Comment", ColumnType.VARCHAR), cm("Index_comment", ColumnType.VARCHAR), cm("Visible", ColumnType.VARCHAR),
                cm("Expression", ColumnType.VARCHAR));
    }

    /**
     * 自更新已移除后的静态状态行（列名与旧 UpdateManager.statusResult 保持一致，
     * Qt 客户端按列名取值，不依赖列顺序）。
     */
    private static QueryResult updateRemovedStub() {
        List<ColumnMeta> cols = List.of(
                cm("current_version", ColumnType.VARCHAR), cm("latest_version", ColumnType.VARCHAR),
                cm("update_available", ColumnType.VARCHAR), cm("status", ColumnType.VARCHAR),
                cm("downloaded", ColumnType.VARCHAR), cm("download_url", ColumnType.VARCHAR),
                cm("sha256", ColumnType.VARCHAR), cm("changelog", ColumnType.TEXT),
                cm("size_bytes", ColumnType.VARCHAR), cm("error", ColumnType.VARCHAR));
        List<Object> row = List.of(currentVersion(), "", "false", "REMOVED", "false", "", "",
                "自动更新功能已移除，请到官网下载新版本安装包覆盖升级。", "0", "");
        return QueryResult.ofResultSet(cols, List.of(row));
    }

    /** 读取构建期注入的版本号（Maven 资源过滤写入 kundb-version.properties）；缺失时返回 "unknown"。 */
    private static String currentVersion() {
        try (java.io.InputStream in = MysqlServerHandler.class.getClassLoader()
                .getResourceAsStream("kundb-version.properties")) {
            if (in != null) {
                java.util.Properties p = new java.util.Properties();
                p.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
                String v = p.getProperty("kundb.version");
                if (v != null && !v.isBlank() && !v.startsWith("${")) {
                    return v.trim();
                }
            }
        } catch (Exception ignore) {
            // 版本号读不到不影响服务
        }
        return "unknown";
    }

    private static String readNulString(ByteBuf p) {
        int start = p.readerIndex();
        int end = p.indexOf(start, p.writerIndex(), (byte) 0);
        if (end < 0) {
            int len = p.readableBytes();
            byte[] b = new byte[len];
            p.readBytes(b);
            return new String(b, StandardCharsets.UTF_8);
        }
        int len = end - start;
        byte[] b = new byte[len];
        p.readBytes(b);
        p.readByte(); // 消费 NUL
        return new String(b, StandardCharsets.UTF_8);
    }

    private static byte[] readLenEncBytes(ByteBuf p) {
        long len = readLenEncInt(p);
        byte[] b = new byte[(int) len];
        p.readBytes(b);
        return b;
    }

    private static long readLenEncInt(ByteBuf p) {
        int first = p.readUnsignedByte();
        if (first < 251) {
            return first;
        }
        if (first == 0xFC) {
            return p.readUnsignedShortLE();
        }
        if (first == 0xFD) {
            return p.readUnsignedMediumLE();
        }
        if (first == 0xFE) {
            return p.readLongLE();
        }
        return first;
    }
}
