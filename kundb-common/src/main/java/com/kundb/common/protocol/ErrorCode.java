package com.kundb.common.protocol;

/**
 * 机器可读错误码（docs/protocol.md §7）。服务端回 ERROR 时使用其 name() 作为 code 字段，
 * 部分错误附带仿 SQL 标准的 5 位 sqlState 便于客户端高级展示。
 */
public enum ErrorCode {

    ACCESS_DENIED("28000"),
    PRIVILEGE_DENIED("42000"),
    AUTH_REQUIRED(null),
    SYNTAX_ERROR("42000"),
    UNKNOWN_DATABASE(null),
    UNKNOWN_TABLE(null),
    UNKNOWN_COLUMN(null),
    DUPLICATE_KEY(null),
    TYPE_MISMATCH(null),
    FK_VIOLATION(null),
    TXN_CONFLICT(null),
    /** 锁等待超时（MySQL 1205）：等别人的锁超过 innodb_lock_wait_timeout。 */
    LOCK_WAIT_TIMEOUT("HY000"),
    /** 死锁（MySQL 1213）：wait-for 图成环，本事务被选为受害者回滚。 */
    DEADLOCK("40001"),
    PROTOCOL_ERROR(null),
    SERVER_ERROR(null);

    private final String sqlState;

    ErrorCode(String sqlState) {
        this.sqlState = sqlState;
    }

    /** 仿 SQL 标准的 5 位状态码；无对应值时为 null。 */
    public String sqlState() {
        return sqlState;
    }
}
