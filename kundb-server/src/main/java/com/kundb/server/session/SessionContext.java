package com.kundb.server.session;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单条 TCP 连接对应的会话（docs/protocol.md §8）。
 * <p>一次成功 AUTH 后，连接绑定：当前用户 + 当前库 + 事务状态。
 * QUERY 帧不携带身份，服务端按"连接 → 会话"映射识别，越权由服务端回 ACCESS_DENIED。</p>
 *
 * <h3>并发控制相关（2026-09-21 新增）</h3>
 * <p>会话持有：事务号 {@code trxId}（进入事务时才分配，用于 MVCC 版本可见性判断）、
 * 隔离级别、锁等待超时、以及本会话已持有的显式表锁（{@code LOCK TABLES}）。</p>
 */
public class SessionContext {

    private static final AtomicLong SEQ = new AtomicLong(0);

    /** 隔离级别（对应 MySQL 的四种；默认与 InnoDB 一致用 REPEATABLE READ）。 */
    public enum Isolation {
        READ_UNCOMMITTED("READ-UNCOMMITTED"),
        READ_COMMITTED("READ-COMMITTED"),
        REPEATABLE_READ("REPEATABLE-READ"),
        SERIALIZABLE("SERIALIZABLE");

        private final String sqlName;

        Isolation(String sqlName) {
            this.sqlName = sqlName;
        }

        public String sqlName() {
            return sqlName;
        }
    }

    private final String sessionId;
    private volatile boolean authed = false;
    private volatile String user;
    private volatile String currentDatabase;
    private volatile boolean autocommit = true;
    private volatile boolean inTransaction = false;

    // ---- 并发控制 ----
    /** 事务号：进入事务（BEGIN / 首条 DML）时由引擎分配，单调递増。0 表示当前不在事务中。 */
    private volatile long trxId = 0L;
    /** 会话级隔离级别（SET SESSION TRANSACTION ISOLATION LEVEL 修改）。 */
    private volatile Isolation isolation = Isolation.REPEATABLE_READ;
    /** 锁等待超时（毫秒）；超时报错误码 1205。 */
    private volatile long lockWaitTimeoutMs = 10000L;
    /** 本会话持有的显式表锁：{@code 库.表 -> READ/WRITE}（LOCK TABLES 设置，UNLOCK TABLES 清空）。 */
    private final Map<String, String> explicitTableLocks = new LinkedHashMap<>();
    /** 事务内是否发生过死锁并被回滚（用于提示客户端"当前事务已被回滚"）。 */
    private volatile boolean deadlockVictim = false;

    public SessionContext() {
        this.sessionId = "sess-" + UUID.randomUUID().toString().substring(0, 8)
                + "-" + SEQ.incrementAndGet();
    }

    public String getSessionId() {
        return sessionId;
    }

    public boolean isAuthed() {
        return authed;
    }

    public void setAuthed(boolean authed) {
        this.authed = authed;
    }

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user;
    }

    public String getCurrentDatabase() {
        return currentDatabase;
    }

    public void setCurrentDatabase(String currentDatabase) {
        this.currentDatabase = currentDatabase;
    }

    public boolean isAutocommit() {
        return autocommit;
    }

    public void setAutocommit(boolean autocommit) {
        this.autocommit = autocommit;
    }

    public boolean isInTransaction() {
        return inTransaction;
    }

    public void setInTransaction(boolean inTransaction) {
        this.inTransaction = inTransaction;
    }

    // ===================== 并发控制 =====================

    public long getTrxId() {
        return trxId;
    }

    public void setTrxId(long trxId) {
        this.trxId = trxId;
    }

    public Isolation getIsolation() {
        return isolation;
    }

    public void setIsolation(Isolation isolation) {
        this.isolation = isolation;
    }

    /** SQL 里的隔离级别写法（含空格/横线）→ 枚举。识别不了返回 null。 */
    public static Isolation parseIsolation(String s) {
        if (s == null) {
            return null;
        }
        String n = s.trim().toUpperCase().replace('-', ' ').replace('_', ' ');
        n = n.replaceAll("\\s+", " ");
        return switch (n) {
            case "READ UNCOMMITTED" -> Isolation.READ_UNCOMMITTED;
            case "READ COMMITTED" -> Isolation.READ_COMMITTED;
            case "REPEATABLE READ" -> Isolation.REPEATABLE_READ;
            case "SERIALIZABLE" -> Isolation.SERIALIZABLE;
            default -> null;
        };
    }

    public long getLockWaitTimeoutMs() {
        return lockWaitTimeoutMs;
    }

    public void setLockWaitTimeoutMs(long ms) {
        this.lockWaitTimeoutMs = Math.max(0, ms);
    }

    public Map<String, String> getExplicitTableLocks() {
        return explicitTableLocks;
    }

    public boolean isDeadlockVictim() {
        return deadlockVictim;
    }

    public void setDeadlockVictim(boolean v) {
        this.deadlockVictim = v;
    }
}
