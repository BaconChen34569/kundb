package com.kundb.server.mysql;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

/**
 * MySQL 线协议解帧器：把 TCP 字节流切成 [3字节小端长度][1字节 seq][payload] 的数据包。
 * 处理粘包/拆包：长度不够时回退 readerIndex 等待更多数据。单包上限 16MB，超限直接断开。
 */
public class MysqlPacketDecoder extends ByteToMessageDecoder {

    private static final long MAX_PACKET = 16L * 1024 * 1024;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        while (in.readableBytes() >= 4) {
            in.markReaderIndex();
            int len = in.readUnsignedMediumLE();
            int seq = in.readUnsignedByte();
            if (len > in.readableBytes()) {
                in.resetReaderIndex(); // 包体还没到齐，等下一次
                return;
            }
            if (len > MAX_PACKET) {
                ctx.close(); // 防恶意/错误膨胀
                return;
            }
            ByteBuf slice = in.readSlice(len).retain();
            out.add(new MysqlPacket(slice, seq));
        }
    }
}
