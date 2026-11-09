package com.kundb.common.protocol;

/**
 * 服务端监听端口常量。
 * <p>KUN-DB 使用标准 <b>MySQL 线协议</b>（见 docs/mysql-protocol.md）。原先"自定义帧"协议的
 * MAGIC / HEADER_LENGTH / MAX_PAYLOAD / MAX_SQL_LENGTH 等常量已随协议切换全部删除（无任何引用）。</p>
 */
public final class FrameConstants {

    /**
     * 服务端默认监听端口（MySQL 线协议，可经 --port 覆盖）。
     * 8020 为项目约定端口（开发要求选定），与标准 MySQL 3306 区分开；
     * 本机 3306 已被真实 MySQL 8.0.41（记账系统）占用，故不使用 3306。
     */
    public static final int DEFAULT_PORT = 8020;

    private FrameConstants() {
    }
}