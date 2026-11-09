package com.kundb.common.protocol;

/**
 * 规范列类型名（docs/protocol.md §6）。服务端在 columns[].type 中给出此枚举的 name()，
 * 客户端据此把 rows 中的 JSON 原生值映射到自身控件。
 * <p>注意：DECIMAL / DATE / DATETIME 在线上的 JSON 表示均为字符串，用于保精度与统一解析。</p>
 */
public enum ColumnType {

    INT,
    BIGINT,
    DOUBLE,
    DECIMAL,
    VARCHAR,
    CHAR,
    BOOLEAN,
    DATE,
    DATETIME,
    TEXT;

    public static ColumnType fromName(String s) {
        if (s == null) {
            return null;
        }
        try {
            return valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown column type: " + s);
        }
    }
}
