package com.kundb.server.mysql;

import io.netty.buffer.ByteBuf;

/**
 * 一个 MySQL 协议数据包：解帧后的 payload + 其序列号（seq）。
 * 序列号由 {@link MysqlServerHandler} 在收发时维护（见 docs/mysql-protocol.md）。
 */
public final class MysqlPacket {

    public final ByteBuf payload;
    public final int seq;

    public MysqlPacket(ByteBuf payload, int seq) {
        this.payload = payload;
        this.seq = seq;
    }
}
