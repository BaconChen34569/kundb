package com.kundb.server.mysql;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * MySQL 握手包（Protocol::Handshake v10）构造，以及本协议启用的能力标志。
 */
public final class MysqlHandshake {

    // 能力标志（Client/Server Capabilities）
    public static final int CLIENT_LONG_PASSWORD = 0x00000001;
    public static final int CLIENT_CONNECT_WITH_DB = 0x00000008;
    public static final int CLIENT_PROTOCOL_41 = 0x00000200;
    public static final int CLIENT_SECURE_CONNECTION = 0x00008000;
    public static final int CLIENT_PLUGIN_AUTH = 0x00080000;

    // 必须是「数字开头」的标准版本号：DataGrip / Connector/J 等驱动会解析版本号决定协议行为，
    // 若写成 "KUN-DB-0.1" 会被解析成 0.1（远小于 4.1），导致鉴权协商异常而报 Access denied。
    public static final String SERVER_VERSION = "8.0.32-KUN-DB-0.1";
    public static final String AUTH_PLUGIN = "mysql_native_password";

    private MysqlHandshake() {
    }

    /**
     * 生成 20 字节握手 scramble（auth-plugin-data）。
     *
     * 关键约束：scramble 必须全部落在「可打印 ASCII」区间（0x21..0x7E，不含 NUL 与 >=0x80）。
     * 原因：Connector/J（DataGrip / 官方 MySQL 驱动底层）在解析握手包时，会将
     * auth-plugin-data-part-2 当作 ASCII 字符串读出（readString(STRING_FIXED, "ASCII", ...)），
     * 再用 this.seed.getBytes("ASCII") 还原去算 token。若 scramble 含 >=0x80 的非 ASCII 字节，
     * ASCII 读→写往返会损坏这些高位字节，导致客户端算出的 token 与服务器对不上，报
     * [28000][1045] Access denied。PyMySQL 读原始字节故不受影响。
     * 因此这里强制只用可打印 ASCII，保证任意驱动读到的值与原始字节逐字节一致。
     */
    public static byte[] newScramble() {
        SecureRandom rnd = new SecureRandom();
        byte[] s = new byte[20];
        for (int i = 0; i < s.length; i++) {
            s[i] = (byte) (0x21 + rnd.nextInt(0x7E - 0x21 + 1));
        }
        return s;
    }

    /** 构造握手包 payload（不含 4 字节帧头）。 */
    public static ByteBuf build(int connectionId, byte[] scramble) {
        int caps = CLIENT_LONG_PASSWORD | CLIENT_CONNECT_WITH_DB | CLIENT_PROTOCOL_41
                | CLIENT_SECURE_CONNECTION | CLIENT_PLUGIN_AUTH;

        ByteBuf b = Unpooled.buffer();
        b.writeByte(0x0a); // 协议版本 10
        b.writeBytes((SERVER_VERSION + "\0").getBytes(StandardCharsets.UTF_8));
        b.writeIntLE(connectionId);
        b.writeBytes(scramble, 0, 8); // auth-plugin-data-part-1
        b.writeByte(0x00);            // filler
        b.writeShortLE(caps & 0xFFFF);       // 能力低 16 位
        b.writeByte(MysqlCodec.CHARSET_UTF8MB4); // 字符集
        b.writeShortLE(0);                   // 状态标志
        b.writeShortLE((caps >> 16) & 0xFFFF); // 能力高 16 位
        b.writeByte(21);                      // auth-plugin-data 长度
        b.writeZero(10);                      // 保留
        b.writeBytes(scramble, 8, 12);        // auth-plugin-data-part-2 (12 字节)
        b.writeByte(0x00);                     // part-2 的 NUL 结尾
        b.writeBytes((AUTH_PLUGIN + "\0").getBytes(StandardCharsets.UTF_8));
        return b;
    }
}
