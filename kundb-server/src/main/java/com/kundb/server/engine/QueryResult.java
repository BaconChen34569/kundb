package com.kundb.server.engine;

import java.util.List;

/**
 * 一次 SQL 执行的结果。{@code resultSet=true} 表示有行列（查询），
 * {@code resultSet=false} 表示仅影响行数（写操作/DDL）。
 * <p>rows 中的单元格已是"JSON 原生友好"的 Java 值：Integer/Long/Double 数字、String 字符串、
 * Boolean 布尔、null；DECIMAL/DATE/DATETIME 以 String 保精度，便于直接序列化进协议 RESULT。</p>
 */
public class QueryResult {

    private final boolean resultSet;
    private final List<ColumnMeta> columns;
    private final List<List<Object>> rows;
    private final int affectedRows;

    private QueryResult(boolean resultSet, List<ColumnMeta> columns, List<List<Object>> rows, int affectedRows) {
        this.resultSet = resultSet;
        this.columns = columns;
        this.rows = rows;
        this.affectedRows = affectedRows;
    }

    public static QueryResult ofResultSet(List<ColumnMeta> columns, List<List<Object>> rows) {
        return new QueryResult(true, columns, rows, 0);
    }

    public static QueryResult ofAffected(int affectedRows) {
        return new QueryResult(false, List.of(), List.of(), affectedRows);
    }

    public boolean isResultSet() {
        return resultSet;
    }

    public List<ColumnMeta> columns() {
        return columns;
    }

    public List<List<Object>> rows() {
        return rows;
    }

    public int affectedRows() {
        return affectedRows;
    }
}
