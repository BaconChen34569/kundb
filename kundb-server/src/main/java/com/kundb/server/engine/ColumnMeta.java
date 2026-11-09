package com.kundb.server.engine;

import com.kundb.common.protocol.ColumnType;

/**
 * 引擎内部的列定义（含类型与约束），是 {@code QueryResult} 的组成部分。
 * 与协议层的 {@code com.kundb.server.protocol.message.ColumnMeta} 区分：
 * 此处持有强类型 {@link ColumnType}，协议层只持有字符串类型名。
 */
public class ColumnMeta {

    private final String name;
    private final ColumnType type;
    private final boolean nullable;
    private final boolean primaryKey;
    /** 列注释（CREATE TABLE / ALTER TABLE ... MODIFY COLUMN ... COMMENT 'x'）。
     *  供 information_schema.COLUMNS.COLUMN_COMMENT 与 SHOW FULL COLUMNS.Comment 使用。 */
    private final String comment;

    public ColumnMeta(String name, ColumnType type, boolean nullable, boolean primaryKey) {
        this(name, type, nullable, primaryKey, null);
    }

    public ColumnMeta(String name, ColumnType type, boolean nullable, boolean primaryKey, String comment) {
        this.name = name;
        this.type = type;
        this.nullable = nullable;
        this.primaryKey = primaryKey;
        this.comment = comment == null ? "" : comment;
    }

    public String name() {
        return name;
    }

    public ColumnType type() {
        return type;
    }

    public boolean nullable() {
        return nullable;
    }

    public boolean primaryKey() {
        return primaryKey;
    }

    /** 列注释；无注释返回空串（不返回 null，避免协议层组装结果集时 NPE）。 */
    public String comment() {
        return comment;
    }
}
