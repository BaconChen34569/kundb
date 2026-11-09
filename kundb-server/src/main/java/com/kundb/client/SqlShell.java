package com.kundb.client;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * KUN-DB 交互式命令行客户端（MySQL 线协议）。
 *
 * <p>与 {@code kundb/test_client.py} 行为一致，但用 Java 实现，可直接塞进 fat jar，
 * 使安装包自带一个“命令行数据库连接工具”，无需依赖本机 Python。</p>
 *
 * <p>原理：KUN-DB 对外是标准 MySQL 线协议子集；本类即一个最小 MySQL 客户端——
 * 收 Handshake v10 → 回 HandshakeResponse41（mysql_native_password 挑战应答）→
 * 收 OK 即登录成功 → 每行 SQL 用 COM_QUERY(0x03) 发出并解析 OK/ERR/结果集；
 * 特殊命令 {@code ping} 发 COM_PING(0x0E) 探活。</p>
 *
 * <p>用法：
 * <pre>
 *   java -cp kundb-server-*-all.jar com.kundb.client.SqlShell
 *   java ... SqlShell &lt;host&gt; &lt;port&gt;
 *   java ... SqlShell &lt;host&gt; &lt;port&gt; &lt;user&gt; &lt;pwd&gt;
 * </pre>
 * 连上后逐行输入 SQL（回车提交），输入 exit / quit 退出。</p>
 */
public final class SqlShell {

    private static final int CLIENT_LONG_PASSWORD = 0x00000001;
    private static final int CLIENT_PROTOCOL_41 = 0x00000200;
    private static final int CLIENT_SECURE_CONNECTION = 0x00008000;
    private static final int CLIENT_PLUGIN_AUTH = 0x00080000;

    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_PORT = 8020;
    private static final String DEFAULT_USER = "root";
    private static final String DEFAULT_PWD = "123456";

    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : DEFAULT_HOST;
        // 端口跟随：显式给了第二个参数就用它；否则读服务端落盘的 kundb.port（改过端口也能连上）
        int port = args.length > 1 ? Integer.parseInt(args[1]) : detectServerPort();
        String user = args.length > 2 ? args[2] : DEFAULT_USER;
        String pwd = args.length > 3 ? args[3] : DEFAULT_PWD;

        Socket sock;
        try {
            sock = connectAndAuth(host, port, user, pwd);
        } catch (IOException e) {
            System.out.println("[KUN-DB] 连接/认证失败：" + e.getMessage());
            System.out.println("         默认账号是 root / 123456；如需指定：");
            System.out.println("         SqlShell <host> <port> <user> <password>");
            System.exit(2);
            return;
        }

        System.out.println("已连接到 " + host + ":" + port + " | 用户 " + user
                + " | 协议 MySQL 线协议（握手 OK）");
        System.out.println("输入 SQL 执行（exit/quit 退出；ping 探活；-- 开头为注释）：");

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        try {
            while (true) {
                System.out.print("KUN-DB> ");
                System.out.flush();
                String sql;
                try {
                    sql = in.readLine();
                } catch (IOException e) {
                    break;
                }
                if (sql == null) {
                    break; // EOF（管道/重定向结束）
                }
                sql = sql.trim();
                if (sql.isEmpty()) {
                    continue;
                }
                if (sql.startsWith("--") || sql.startsWith("#")) {
                    System.out.println("  -- " + sql.replaceAll("^[-#\\s]+", ""));
                    continue;
                }
                if (sql.equalsIgnoreCase("exit") || sql.equalsIgnoreCase("quit")) {
                    break;
                }
                if (sql.equalsIgnoreCase("ping")) {
                    System.out.println(ping(sock) ? "PONG" : "PING 失败");
                    continue;
                }
                try {
                    long t0 = System.nanoTime();
                    Result res = execute(sock, sql);
                    render(res);
                    if (res.kind == Kind.RESULT) {
                        System.out.printf("耗时 %.1f ms%n", (System.nanoTime() - t0) / 1e6);
                    }
                } catch (IOException e) {
                    System.out.println("执行/读取异常：" + e.getMessage());
                    break;
                }
            }
        } finally {
            try {
                sock.close();
            } catch (IOException ignore) {
                // 忽略
            }
            System.out.println("已断开连接。");
        }
    }

    // ============ 端口自动跟随 ============
    /**
     * 探测本机 KUN-DB 服务端实际监听的端口：读服务端每次启动成功绑定后写在
     * 数据基目录的 kundb.port（内容为端口号）。候选顺序：
     * ① 系统属性 kundb.port；② %ProgramData%/KUN-DB/kundb.port（Program Files 安装）；
     * ③ 本 jar 所在应用根的 kundb.port（便携布局）。都读不到则回退默认 8020。
     */
    private static int detectServerPort() {
        String p = System.getProperty("kundb.port");
        if (p != null && !p.trim().isEmpty()) {
            try {
                return Integer.parseInt(p.trim());
            } catch (NumberFormatException ignore) {
                // 属性非法，继续读端口文件
            }
        }
        List<Path> candidates = new ArrayList<>();
        String programData = System.getenv("ProgramData");
        if (programData != null && !programData.isEmpty()) {
            candidates.add(Path.of(programData, "KUN-DB", "kundb.port"));
        }
        try {
            Path jar = Path.of(SqlShell.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            Path dir = jar.getParent();
            if (dir != null && (dir.endsWith("lib") || dir.endsWith("app"))) {
                dir = dir.getParent();
            }
            if (dir != null) {
                candidates.add(dir.resolve("kundb.port"));
            }
        } catch (Exception ignore) {
            // 拿不到 jar 位置就只用上面的候选
        }
        for (Path c : candidates) {
            try {
                if (Files.isRegularFile(c)) {
                    String s = new String(Files.readAllBytes(c), StandardCharsets.UTF_8).trim();
                    int v = Integer.parseInt(s);
                    if (v > 0 && v <= 65535) {
                        return v;
                    }
                }
            } catch (Exception ignore) {
                // 单个候选损坏/不可读，继续下一个
            }
        }
        return DEFAULT_PORT;
    }

    // ============ 底层收发 ============
    private static byte[] recvExact(InputStream is, int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = is.read(buf, off, n - off);
            if (r < 0) {
                throw new EOFException("连接被关闭");
            }
            off += r;
        }
        return buf;
    }

    private static Packet readPacket(DataInputStream dis) throws IOException {
        byte[] hdr = recvExact(dis, 4);
        int length = (hdr[0] & 0xFF) | ((hdr[1] & 0xFF) << 8) | ((hdr[2] & 0xFF) << 16);
        int seq = hdr[3] & 0xFF;
        return new Packet(seq, recvExact(dis, length));
    }

    private static void sendPacket(OutputStream os, int seq, byte[] payload) throws IOException {
        byte[] hdr = new byte[]{
                (byte) (payload.length & 0xFF),
                (byte) ((payload.length >> 8) & 0xFF),
                (byte) ((payload.length >> 16) & 0xFF),
                (byte) (seq & 0xFF)
        };
        os.write(hdr);
        os.write(payload);
        os.flush();
    }

    // ============ 长度编码（Length-Encoded） ============
    private static byte[] lenencInt(long n) {
        if (n < 251) {
            return new byte[]{(byte) n};
        }
        if (n <= 0xFFFFL) {
            byte[] b = new byte[3];
            b[0] = (byte) 0xFC;
            b[1] = (byte) (n & 0xFF);
            b[2] = (byte) ((n >> 8) & 0xFF);
            return b;
        }
        if (n <= 0xFFFFFFL) {
            byte[] b = new byte[4];
            b[0] = (byte) 0xFD;
            b[1] = (byte) (n & 0xFF);
            b[2] = (byte) ((n >> 8) & 0xFF);
            b[3] = (byte) ((n >> 16) & 0xFF);
            return b;
        }
        byte[] b = new byte[9];
        b[0] = (byte) 0xFE;
        for (int i = 0; i < 8; i++) {
            b[1 + i] = (byte) ((n >> (8 * i)) & 0xFF);
        }
        return b;
    }

    private static long readLenencInt(byte[] b, int[] pos) {
        int f = b[pos[0]] & 0xFF;
        pos[0]++;
        if (f < 251) {
            return f;
        }
        if (f == 0xFC) {
            long v = (b[pos[0]] & 0xFF) | ((b[pos[0] + 1] & 0xFF) << 8);
            pos[0] += 2;
            return v;
        }
        if (f == 0xFD) {
            long v = (b[pos[0]] & 0xFF) | ((b[pos[0] + 1] & 0xFF) << 8) | ((b[pos[0] + 2] & 0xFF) << 16);
            pos[0] += 3;
            return v;
        }
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v |= ((long) (b[pos[0] + i] & 0xFF)) << (8 * i);
        }
        pos[0] += 8;
        return v;
    }

    private static String readLenencStr(byte[] b, int[] pos) {
        long n = readLenencInt(b, pos);
        String s = new String(b, pos[0], (int) n, StandardCharsets.UTF_8);
        pos[0] += (int) n;
        return s;
    }

    // ============ mysql_native_password 挑战应答 ============
    private static byte[] nativeToken(byte[] scramble, String pwd) throws Exception {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] s1 = sha1.digest(pwd.getBytes(StandardCharsets.UTF_8));
        byte[] s2 = sha1.digest(s1);
        byte[] raw = sha1.digest(concat(scramble, s2));
        byte[] out = new byte[s1.length];
        for (int i = 0; i < s1.length; i++) {
            out[i] = (byte) (s1[i] ^ raw[i]);
        }
        return out;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] c = new byte[a.length + b.length];
        System.arraycopy(a, 0, c, 0, a.length);
        System.arraycopy(b, 0, c, a.length, b.length);
        return c;
    }

    // ============ 连接与鉴权 ============
    private static Socket connectAndAuth(String host, int port, String user, String pwd) throws IOException {
        Socket sock = new Socket(host, port);
        sock.setSoTimeout(10000);
        try {
            InputStream rawIs = sock.getInputStream();
            DataInputStream dis = new DataInputStream(rawIs);
            OutputStream os = sock.getOutputStream();

            Packet hp = readPacket(dis); // Handshake v10
            if ((hp.payload[0] & 0xFF) != 0x0A) {
                throw new IOException("首包不是 MySQL 握手包（proto=" + (hp.payload[0] & 0xFF) + "）");
            }
            byte[] p = hp.payload;
            int pos = 1;
            while (p[pos] != 0) {
                pos++; // server version（NUL 结尾）
            }
            pos++;
            pos += 4; // connection id
            byte[] sp1 = new byte[8];
            System.arraycopy(p, pos, sp1, 0, 8);
            pos += 8; // auth-plugin-data 第一部分
            pos += 1; // filler
            pos += 2; // capability 低 16 位
            pos += 1; // charset
            pos += 2; // status
            pos += 2; // capability 高 16 位
            pos += 1; // auth-plugin-data-len
            pos += 10; // reserved
            byte[] sp2 = new byte[12];
            System.arraycopy(p, pos, sp2, 0, 12);
            pos += 12; // auth-plugin-data 第二部分
            byte[] scramble = concat(sp1, sp2);

            int caps = CLIENT_LONG_PASSWORD | CLIENT_PROTOCOL_41
                    | CLIENT_SECURE_CONNECTION | CLIENT_PLUGIN_AUTH;
            // 布局：caps(4) + maxPacket(4) + charset(1) + reserved(23) + user + NUL(1)
            //        + token长度(1) + token(20) + authPlugin(21) + NUL(1)
            byte[] resp = new byte[4 + 4 + 1 + 23 + user.length() + 1 + 1 + 20 + 21 + 1];
            int rp = 0;
            rp = putInt(resp, rp, caps);
            rp = putInt(resp, rp, 0x01000000); // max packet size = 16MB
            resp[rp++] = 45; // charset = utf8mb4
            for (int i = 0; i < 23; i++) {
                resp[rp++] = 0; // reserved
            }
            byte[] ub = user.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(ub, 0, resp, rp, ub.length);
            rp += ub.length;
            resp[rp++] = 0; // NUL
            byte[] token = nativeToken(scramble, pwd);
            resp[rp++] = (byte) (token.length & 0xFF); // 长度编码（短形式）的 1 字节长度
            System.arraycopy(token, 0, resp, rp, token.length);
            rp += token.length;
            byte[] plugin = "mysql_native_password".getBytes(StandardCharsets.UTF_8);
            System.arraycopy(plugin, 0, resp, rp, plugin.length);
            rp += plugin.length;
            resp[rp++] = 0; // NUL

            sendPacket(os, 1, resp);

            Packet ap = readPacket(dis);
            if ((ap.payload[0] & 0xFF) == 0xFF) {
                int code = (ap.payload[1] & 0xFF) | ((ap.payload[2] & 0xFF) << 8);
                String msg = new String(ap.payload, 3, ap.payload.length - 3, StandardCharsets.UTF_8);
                throw new IOException("认证失败 [MySQL errno " + code + "] " + msg);
            }
            if ((ap.payload[0] & 0xFF) != 0x00) {
                throw new IOException("登录未收到 OK 包（首字节 0x"
                        + Integer.toHexString(ap.payload[0] & 0xFF) + "）");
            }
            // 把 dis/os 包进一个可复用的对象
            sock.setSoTimeout(0);
            // 保存流引用到附件（用线程本地不可行，这里改为在全局持有）
            STREAMS.set(dis);
            STREAMS_OS.set(os);
            return sock;
        } catch (IOException e) {
            sock.close();
            throw e;
        } catch (Exception e) {
            sock.close();
            throw new IOException("认证过程异常：" + e.getMessage(), e);
        }
    }

    // 用 ThreadLocal 持有输入输出流，execute/ping 复用
    private static final ThreadLocal<DataInputStream> STREAMS = new ThreadLocal<>();
    private static final ThreadLocal<OutputStream> STREAMS_OS = new ThreadLocal<>();

    private static int putInt(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
        b[off + 2] = (byte) ((v >> 16) & 0xFF);
        b[off + 3] = (byte) ((v >> 24) & 0xFF);
        return off + 4;
    }

    // ============ 执行 SQL ============
    private static Result execute(Socket sock, String sql) throws IOException {
        DataInputStream dis = STREAMS.get();
        OutputStream os = STREAMS_OS.get();
        sendPacket(os, 0, concat(new byte[]{0x03}, sql.getBytes(StandardCharsets.UTF_8)));
        Packet p = readPacket(dis);

        int first = p.payload[0] & 0xFF;
        if (first == 0x00) { // OK 包
            long affected = readLenencInt(p.payload, new int[]{1});
            return new Result(Kind.OK, affected, null, null);
        }
        if (first == 0xFF) { // ERR 包
            int code = (p.payload[1] & 0xFF) | ((p.payload[2] & 0xFF) << 8);
            String msg = new String(p.payload, 3, p.payload.length - 3, StandardCharsets.UTF_8);
            return new Result(Kind.ERROR, 0, code, msg);
        }
        if (first == 0xFE && p.payload.length <= 9) { // 空 EOF
            return new Result(Kind.OK, 0, null, null);
        }

        // 结果集
        int ncol = (int) readLenencInt(p.payload, new int[]{0});
        List<String> columns = new ArrayList<>();
        for (int i = 0; i < ncol; i++) {
            Packet cp = readPacket(dis);
            int[] cpos = new int[]{0};
            for (int k = 0; k < 4; k++) { // 跳过 catalog/schema/table/org_table
                readLenencStr(cp.payload, cpos);
            }
            columns.add(readLenencStr(cp.payload, cpos)); // 第 5 个才是列名
        }
        readPacket(dis); // EOF（列定义之后）

        List<String[]> rows = new ArrayList<>();
        while (true) {
            Packet rp = readPacket(dis);
            if ((rp.payload[0] & 0xFF) == 0xFE && rp.payload.length <= 9) {
                break; // EOF（行结束）
            }
            int[] rpos = new int[]{0};
            String[] row = new String[ncol];
            for (int i = 0; i < ncol; i++) {
                if ((rp.payload[rpos[0]] & 0xFF) == 0xFB) {
                    row[i] = null;
                    rpos[0]++;
                } else {
                    row[i] = readLenencStr(rp.payload, rpos);
                }
            }
            rows.add(row);
        }
        return new Result(Kind.RESULT, 0, null, null, columns, rows);
    }

    private static boolean ping(Socket sock) {
        try {
            OutputStream os = STREAMS_OS.get();
            DataInputStream dis = STREAMS.get();
            sendPacket(os, 0, new byte[]{0x0E}); // COM_PING
            Packet p = readPacket(dis);
            return (p.payload[0] & 0xFF) == 0x00;
        } catch (IOException e) {
            return false;
        }
    }

    // ============ 渲染 ============
    private static void render(Result res) {
        if (res.kind == Kind.OK) {
            System.out.println("OK | 影响行数=" + res.affected);
            return;
        }
        if (res.kind == Kind.ERROR) {
            System.out.println("错误[errno " + res.code + "]: " + res.msg);
            return;
        }
        List<String> cols = res.columns;
        List<String[]> rows = res.rows;
        int[] widths = new int[cols.size()];
        for (int i = 0; i < cols.size(); i++) {
            widths[i] = cols.get(i).length();
        }
        for (String[] r : rows) {
            for (int i = 0; i < cols.size(); i++) {
                String v = (r[i] == null) ? "NULL" : r[i];
                widths[i] = Math.max(widths[i], v.length());
            }
        }
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) {
                line.append(" | ");
            }
            line.append(pad(cols.get(i), widths[i]));
        }
        System.out.println(line);
        System.out.println(repeat("-", line.length()));
        for (String[] r : rows) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < cols.size(); i++) {
                if (i > 0) {
                    sb.append(" | ");
                }
                String v = (r[i] == null) ? "NULL" : r[i];
                sb.append(pad(v, widths[i]));
            }
            System.out.println(sb);
        }
        System.out.println("(" + rows.size() + " 行)");
    }

    private static String pad(String s, int w) {
        if (s.length() >= w) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < w) {
            sb.append(' ');
        }
        return sb.toString();
    }

    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(s);
        }
        return sb.toString();
    }

    private enum Kind { OK, ERROR, RESULT }

    private static final class Result {
        final Kind kind;
        final long affected;
        final Integer code;
        final String msg;
        final List<String> columns;
        final List<String[]> rows;

        Result(Kind kind, long affected, Integer code, String msg) {
            this(kind, affected, code, msg, null, null);
        }

        Result(Kind kind, long affected, Integer code, String msg,
               List<String> columns, List<String[]> rows) {
            this.kind = kind;
            this.affected = affected;
            this.code = code;
            this.msg = msg;
            this.columns = columns;
            this.rows = rows;
        }
    }

    private static final class Packet {
        final int seq;
        final byte[] payload;

        Packet(int seq, byte[] payload) {
            this.seq = seq;
            this.payload = payload;
        }
    }
}
