package com.kundb.server.mysql;

import com.kundb.common.protocol.ColumnType;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * MySQL 线协议编码工具：长度编码整数/字符串、OK/ERR/EOF 包、列定义包，以及统一的 send()。
 * 全部按文本协议（text protocol）规则：结果集中的值一律以"长度编码字符串"发送，NULL 用 0xFB 标记。
 */
public final class MysqlCodec {

    // ---- MySQL 列类型（protocol MYSQL_TYPE_*）----
    public static final int TYPE_DECIMAL = 0;
    public static final int TYPE_TINY = 1;       // BOOLEAN
    public static final int TYPE_LONG = 3;       // INT
    public static final int TYPE_DOUBLE = 5;
    public static final int TYPE_LONGLONG = 8;   // BIGINT
    public static final int TYPE_DATE = 10;
    public static final int TYPE_DATETIME = 12;
    public static final int TYPE_STRING = 254;
    public static final int TYPE_VAR_STRING = 253; // VARCHAR/CHAR/TEXT/DATE/DATETIME/DECIMAL

    public static final int CHARSET_UTF8MB4 = 45; // utf8mb4_general_ci
    public static final int CHARSET_BINARY = 63;   // 数值/布尔用二进制字符集

    public static final int SERVER_STATUS_AUTOCOMMIT = 0x0002;
    public static final int SERVER_MORE_RESULTS_EXISTS = 0x0008; // 多结果集：仍有后续结果
    public static final int SERVER_STATUS_CURSOR_EXISTS = 0x0040; // 服务端游标未关闭
    public static final int SERVER_STATUS_LAST_ROW_SENT = 0x0080; // 游标已取到最后一行

    private MysqlCodec() {
    }

    // ===================== 长度编码 =====================

    public static void writeLenEncInt(ByteBuf b, long v) {
        if (v < 251) {
            b.writeByte((int) v);
        } else if (v <= 0xFFFFL) {
            b.writeByte(0xFC);
            b.writeShortLE((int) v);
        } else if (v <= 0xFFFFFFL) {
            b.writeByte(0xFD);
            b.writeMediumLE((int) v);
        } else {
            b.writeByte(0xFE);
            b.writeLongLE(v);
        }
    }

    public static void writeLenEncStr(ByteBuf b, byte[] s) {
        writeLenEncInt(b, s.length);
        b.writeBytes(s);
    }

    public static void writeLenEncStr(ByteBuf b, String s) {
        writeLenEncStr(b, s.getBytes(StandardCharsets.UTF_8));
    }

    // ===================== 基础包 =====================

    /** OK 包：affectedRows / lastInsertId / statusFlags / 可选 info。 */
    public static ByteBuf okPacket(long affectedRows, long lastInsertId, int statusFlags, String info) {
        ByteBuf b = Unpooled.buffer();
        b.writeByte(0x00);
        writeLenEncInt(b, affectedRows);
        writeLenEncInt(b, lastInsertId);
        b.writeShortLE(statusFlags);
        b.writeShortLE(0); // warnings
        if (info != null) {
            writeLenEncStr(b, info);
        }
        return b;
    }

    /** ERR 包：标准结构 [0xFF][error_code:2][#][sql_state:5][message]。 */
    public static ByteBuf errPacket(int code, String sqlState, String message) {
        ByteBuf b = Unpooled.buffer();
        b.writeByte(0xFF);
        b.writeShortLE(code);
        b.writeByte('#');
        byte[] st = sqlState.getBytes(StandardCharsets.UTF_8);
        b.writeBytes(st);
        b.writeBytes(message.getBytes(StandardCharsets.UTF_8));
        return b;
    }

    /** EOF 包（仅在未启用 CLIENT_DEPRECATE_EOF 时使用）。 */
    public static ByteBuf eofPacket(int warnings, int statusFlags) {
        ByteBuf b = Unpooled.buffer();
        b.writeByte(0xFE);
        b.writeShortLE(warnings);
        b.writeShortLE(statusFlags);
        return b;
    }

    /**
     * 列定义包（Protocol::ColumnDefinition41）。
     * @param columnLength 显示用长度提示
     * @param type         MYSQL_TYPE_*
     * @param flags        列标志（NOT_NULL=1, PRI_KEY=2, ...）
     */
    public static ByteBuf columnDef(String schema, String table, String orgTable,
                                    String name, String orgName,
                                    int charset, long columnLength, int type, int flags) {
        ByteBuf b = Unpooled.buffer();
        writeLenEncStr(b, "def");        // catalog
        writeLenEncStr(b, schema);       // schema (库)
        writeLenEncStr(b, table);        // table
        writeLenEncStr(b, orgTable);     // org_table
        writeLenEncStr(b, name);         // name (结果集列名)
        writeLenEncStr(b, orgName);      // org_name
        writeLenEncInt(b, 0x0c);         // 固定字段长度
        b.writeShortLE(charset);
        b.writeIntLE((int) columnLength);
        b.writeByte(type);
        b.writeShortLE(flags);
        b.writeByte(0);                  // decimals
        b.writeShortLE(0);               // filler
        return b;
    }

    /** 结果集里的一行：每个单元格是长度编码字符串；NULL 用 0xFB。 */
    public static void writeRowValue(ByteBuf b, Object v) {
        if (v == null) {
            b.writeByte(0xFB);
            return;
        }
        String s;
        if (v instanceof Boolean) {
            s = ((Boolean) v) ? "1" : "0";
        } else {
            s = String.valueOf(v);
        }
        writeLenEncStr(b, s);
    }

    // ===================== 二进制协议（预处理语句结果集）=====================

    /**
     * 二进制结果集中的一行：[0x00][NULL 位图][各列按类型的定长/长度编码值]。
     * NULL 位图大小 = (列数 + 7 + 2) / 8，第 i 列对应第 (i+2) 个比特（前 2 位留给包首部的 0x00 预留位）。
     * NULL 列只在位图里置位，不额外写出字节。
     */
    public static void writeBinaryRow(ByteBuf b, List<Object> row, List<Integer> types) {
        int n = row.size();
        b.writeByte(0x00);                       // 行首标志
        byte[] bitmap = new byte[(n + 7 + 2) / 8];
        for (int i = 0; i < n; i++) {
            if (row.get(i) == null) {
                bitmap[(i + 2) / 8] |= (byte) (1 << ((i + 2) % 8));
            }
        }
        b.writeBytes(bitmap);
        for (int i = 0; i < n; i++) {
            Object v = row.get(i);
            if (v == null) {
                continue;
            }
            writeBinaryValue(b, v, types.get(i));
        }
    }

    /** 单个值按 MYSQL_TYPE_* 写出二进制形式；落不到精确类型的（DECIMAL/字符串等）一律长度编码字符串。 */
    public static void writeBinaryValue(ByteBuf b, Object v, int type) {
        switch (type) {
            case TYPE_TINY -> b.writeByte((int) (toLong(v) & 0xFFL));
            case TYPE_LONG -> {                  // MySQL 规定 INT24/INT 在二进制协议里都占 4 字节
                long x = toLong(v);
                b.writeIntLE((int) x);
            }
            case TYPE_LONGLONG -> b.writeLongLE(toLong(v));
            case TYPE_DOUBLE -> b.writeDoubleLE(toDouble(v));
            case TYPE_DATE -> writeBinaryDate(b, v);
            case TYPE_DATETIME -> writeBinaryDateTime(b, v);
            default -> writeLenEncStr(b, textOf(v)); // DECIMAL / VAR_STRING / 其它
        }
    }

    /** 二进制 DATE：长度 4 的 [年(2)][月][日]；全零日期写长度 0。 */
    private static void writeBinaryDate(ByteBuf b, Object v) {
        int[] dt = parseDateTime(textOf(v));
        if (dt == null || (dt[0] == 0 && dt[1] == 0 && dt[2] == 0)) {
            writeLenEncInt(b, 0);
            return;
        }
        writeLenEncInt(b, 4);
        b.writeShortLE(dt[0]);
        b.writeByte(dt[1]);
        b.writeByte(dt[2]);
    }

    /** 二进制 DATETIME：长度 7 的 [年(2)][月][日][时][分][秒]；全零时间写长度 0。 */
    private static void writeBinaryDateTime(ByteBuf b, Object v) {
        int[] dt = parseDateTime(textOf(v));
        if (dt == null || dt[0] + dt[1] + dt[2] + dt[3] + dt[4] + dt[5] == 0) {
            writeLenEncInt(b, 0);
            return;
        }
        writeLenEncInt(b, 7);
        b.writeShortLE(dt[0]);
        b.writeByte(dt[1]);
        b.writeByte(dt[2]);
        b.writeByte(dt[3]);
        b.writeByte(dt[4]);
        b.writeByte(dt[5]);
    }

    /** 解析 "yyyy-MM-dd" 或 "yyyy-MM-dd HH:mm:ss[.f]"，返回 {年,月,日,时,分,秒}；无法解析返回 null。 */
    private static int[] parseDateTime(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            String day = s.trim().split(" ")[0];
            String[] d = day.split("-");
            String[] t = s.trim().split(" ");
            int hh = 0, mi = 0, se = 0;
            if (t.length > 1) {
                String[] hms = t[1].split(":");
                if (hms.length > 0) {
                    hh = Integer.parseInt(hms[0]);
                }
                if (hms.length > 1) {
                    mi = Integer.parseInt(hms[1]);
                }
                if (hms.length > 2) {
                    se = (int) Double.parseDouble(hms[2]);
                }
            }
            return new int[]{Integer.parseInt(d[0]), d.length > 1 ? Integer.parseInt(d[1]) : 0,
                             d.length > 2 ? Integer.parseInt(d[2]) : 0, hh, mi, se};
        } catch (Exception e) {
            return null;
        }
    }

    private static long toLong(Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v instanceof Boolean bo) {
            return bo ? 1 : 0;
        }
        try {
            return Long.parseLong(textOf(v).trim());
        } catch (Exception e) {
            try {
                return (long) Double.parseDouble(textOf(v).trim());
            } catch (Exception e2) {
                return 0L;
            }
        }
    }

    private static double toDouble(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(textOf(v).trim());
        } catch (Exception e) {
            return 0d;
        }
    }

    private static String textOf(Object v) {
        if (v instanceof Boolean b) {
            return b ? "1" : "0";
        }
        return String.valueOf(v);
    }

    // ===================== ColumnType → MySQL 类型映射 =====================

    /** @return {mysqlType, charset, columnLength, flags} */
    public static int[] typeMapping(ColumnType ct, boolean nullable, boolean pk) {
        int flags = (nullable ? 0 : 1) | (pk ? 2 : 0);
        switch (ct) {
            case INT -> { return new int[]{TYPE_LONG, CHARSET_BINARY, 11, flags}; }
            case BIGINT -> { return new int[]{TYPE_LONGLONG, CHARSET_BINARY, 20, flags}; }
            case DOUBLE -> { return new int[]{TYPE_DOUBLE, CHARSET_BINARY, 22, flags}; }
            case BOOLEAN -> { return new int[]{TYPE_TINY, CHARSET_BINARY, 1, flags}; }
            case DECIMAL -> { return new int[]{TYPE_DECIMAL, CHARSET_UTF8MB4, 30, flags}; }
            case DATE -> { return new int[]{TYPE_DATE, CHARSET_UTF8MB4, 10, flags}; }
            case DATETIME -> { return new int[]{TYPE_DATETIME, CHARSET_UTF8MB4, 19, flags}; }
            case CHAR -> { return new int[]{TYPE_VAR_STRING, CHARSET_UTF8MB4, 255, flags}; }
            case TEXT -> { return new int[]{TYPE_VAR_STRING, CHARSET_UTF8MB4, 65535, flags}; }
            case VARCHAR -> { return new int[]{TYPE_VAR_STRING, CHARSET_UTF8MB4, 255, flags}; }
            default -> { return new int[]{TYPE_VAR_STRING, CHARSET_UTF8MB4, 255, flags}; }
        }
    }

    // ===================== 发送 =====================

    /** 把一个 payload 包上帧（[3字节长度][1字节seq]）并写出，自动维护 seq（引用数组的第 0 位）。 */
    public static void send(ChannelHandlerContext ctx, int[] seqRef, ByteBuf payload) {
        int len = payload.readableBytes();
        ByteBuf header = ctx.alloc().buffer(4);
        header.writeMediumLE(len);
        header.writeByte(seqRef[0] & 0xFF);
        seqRef[0] = (seqRef[0] + 1) & 0xFF;
        ctx.write(header);
        ctx.write(payload);
        ctx.flush();
    }
}
