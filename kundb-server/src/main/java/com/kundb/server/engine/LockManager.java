package com.kundb.server.engine;

import com.kundb.common.protocol.ErrorCode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 锁管理器：表级意向锁 / 共享锁 / 排他锁 + 行级 S/X 锁。
 *
 * <h3>为什么要有锁</h3>
 * <p>MVCC 解决了"读不阻塞写、写不阻塞读"，但两个事务<b>同时改同一行</b>必须有互斥，
 * 否则后写的会覆盖先写的（丢失更新）。锁负责这件事。</p>
 *
 * <h3>锁模式与兼容矩阵</h3>
 * <pre>
 *         请求 →   IS    IX    S     X
 *   持有 IS       ✓     ✓    ✓     ✗
 *   持有 IX       ✓     ✓    ✗     ✗
 *   持有 S        ✓     ✗    ✓     ✗
 *   持有 X        ✗     ✗    ✗     ✗
 * </pre>
 * <p>IS/IX 是"意向锁"：只表示"我打算锁这张表里的某些行"，彼此兼容，
 * 用于快速判断表级 S/X 锁能否授予（不必逐行检查）。</p>
 *
 * <h3>等待、超时与死锁</h3>
 * <p>请求的锁与已授予的锁冲突时，请求方进入等待队列：
 * <ul>
 *   <li><b>超时</b>：等待超过 {@code timeoutMs} → 抛 {@code LOCK_WAIT_TIMEOUT}（MySQL 1205）</li>
 *   <li><b>死锁</b>：加锁前先沿 wait-for 图做 DFS，若会成环 → 抛 {@code DEADLOCK}（MySQL 1213），
 *       由调用方回滚本事务释放锁</li>
 * </ul>
 */
public class LockManager {

    public enum Mode {
        IS, IX, S, X;

        /** 请求模式 {@code req} 能否与已持有模式 {@code held} 共存。 */
        static boolean compatible(Mode held, Mode req) {
            if (held == null) {
                return true;
            }
            return switch (held) {
                case IS -> req == IS || req == IX || req == S;
                case IX -> req == IS || req == IX;
                case S  -> req == IS || req == S;
                case X  -> false;
            };
        }

        /** 合并后该资源的最强模式（用于展示与后续兼容判断）。 */
        static Mode strongest(Mode a, Mode b) {
            if (a == null) {
                return b;
            }
            if (b == null) {
                return a;
            }
            return a.ordinal() >= b.ordinal() ? a : b;
        }
    }

    /** 一个资源（表或行）上的锁状态。 */
    static final class LockEntry {
        /** 已授予：owner(sessionId) -> 模式。 */
        final Map<String, Mode> owners = new LinkedHashMap<>();
        /** 等待队列（FIFO 倾向，实际按"能否授予"扫描）。 */
        final Deque<Waiter> waiters = new ArrayDeque<>();

        /** 当前授予的最强模式（无人为 null）。 */
        Mode granted() {
            Mode m = null;
            for (Mode v : owners.values()) {
                m = Mode.strongest(m, v);
            }
            return m;
        }
    }

    /** 一个正在等待锁的请求。 */
    static final class Waiter {
        final String sessionId;
        final String resId;
        final Mode mode;
        boolean granted;

        Waiter(String sessionId, String resId, Mode mode) {
            this.sessionId = sessionId;
            this.resId = resId;
            this.mode = mode;
        }
    }

    private final Object mu = new Object();
    /** 资源 -> 锁状态。资源 id：表 = {@code db.table}，行 = {@code db.table#slot}。 */
    private final Map<String, LockEntry> entries = new LinkedHashMap<>();
    /** 等待中的会话 -> 它的等待请求（wait-for 图的边）。 */
    private final Map<String, Waiter> waiting = new LinkedHashMap<>();
    /** 死锁历史（供 SHOW ENGINE INNODB STATUS 展示）。 */
    private final List<String> deadlockLog = new ArrayList<>();
    /** 最近一次死锁的描述。 */
    private volatile String lastDeadlock = "";

    // ===================== 加锁 / 释放 =====================

    /**
     * 请求一个资源的锁，冲突则等待（直到超时）。
     *
     * @param resId     资源标识（{@code db.table} 或 {@code db.table#slot}）
     * @param sessionId 请求方会话
     * @param mode      锁模式
     * @param timeoutMs 最长等待毫秒数
     * @throws EngineException 超时（1205）或会构成死锁（1213）
     */
    public void acquire(String resId, String sessionId, Mode mode, long timeoutMs) throws EngineException {
        long deadline = System.currentTimeMillis() + Math.max(0, timeoutMs);
        synchronized (mu) {
            LockEntry e = entries.computeIfAbsent(resId, k -> new LockEntry());
            while (!canGrant(e, sessionId, mode)) {
                // 先判死锁：若本次等待会让 wait-for 图成环，直接拒绝
                if (createsCycle(sessionId, e)) {
                    String desc = "死锁：" + sessionId + " 等待 " + mode + " 锁 " + resId
                            + "，持有者为 " + e.owners.keySet();
                    lastDeadlock = desc;
                    deadlockLog.add(desc);
                    if (deadlockLog.size() > 20) {
                        deadlockLog.remove(0);
                    }
                    throw new EngineException(ErrorCode.DEADLOCK,
                            "Deadlock found when trying to get lock; try restarting transaction");
                }
                Waiter w = new Waiter(sessionId, resId, mode);
                e.waiters.add(w);
                waiting.put(sessionId, w);
                long remain = deadline - System.currentTimeMillis();
                try {
                    if (remain <= 0) {
                        e.waiters.remove(w);
                        waiting.remove(sessionId, w);
                        throw new EngineException(ErrorCode.LOCK_WAIT_TIMEOUT,
                                "Lock wait timeout exceeded; try restarting transaction (等待 " + mode
                                        + " 锁 " + resId + " 超过 " + timeoutMs + "ms)");
                    }
                    mu.wait(Math.min(remain, 100));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    e.waiters.remove(w);
                    waiting.remove(sessionId, w);
                    throw new EngineException(ErrorCode.LOCK_WAIT_TIMEOUT, "锁等待被中断");
                }
                // 被唤醒后重新检查：可能已被授予，也可能仍未轮到
                if (!w.granted) {
                    e.waiters.remove(w);
                    waiting.remove(sessionId, w);
                }
            }
            grant(e, sessionId, mode);
            mu.notifyAll();
        }
    }

    /** 释放某会话在某资源上的锁。 */
    public void release(String resId, String sessionId) {
        synchronized (mu) {
            LockEntry e = entries.get(resId);
            if (e != null) {
                e.owners.remove(sessionId);
                if (e.owners.isEmpty() && e.waiters.isEmpty()) {
                    entries.remove(resId);
                }
            }
            mu.notifyAll();
        }
    }

    /**
     * 释放某会话持有的全部锁，但<b>保留</b>{@code keep} 里列出的资源。
     * 用于 {@code LOCK TABLES}：显式表锁必须跨语句保留到 UNLOCK TABLES，
     * 而语句结束时的自动清理只能回收它临时申请的行锁。
     */
    public void releaseAllExcept(String sessionId, Set<String> keep) {
        synchronized (mu) {
            List<String> empty = new ArrayList<>();
            for (Map.Entry<String, LockEntry> en : entries.entrySet()) {
                if (keep.contains(en.getKey())) {
                    continue;   // 显式锁住的表：连碰都不碰
                }
                en.getValue().owners.remove(sessionId);
                if (en.getValue().owners.isEmpty() && en.getValue().waiters.isEmpty()) {
                    empty.add(en.getKey());
                }
            }
            entries.keySet().removeAll(empty);
            waiting.remove(sessionId);
            mu.notifyAll();
        }
    }

    /** 释放某会话持有的<b>全部</b>锁（COMMIT / ROLLBACK / 连接断开时调用）。 */
    public void releaseAll(String sessionId) {
        synchronized (mu) {
            List<String> empty = new ArrayList<>();
            for (Map.Entry<String, LockEntry> en : entries.entrySet()) {
                en.getValue().owners.remove(sessionId);
                if (en.getValue().owners.isEmpty() && en.getValue().waiters.isEmpty()) {
                    empty.add(en.getKey());
                }
            }
            entries.keySet().removeAll(empty);
            waiting.remove(sessionId);
            mu.notifyAll();
        }
    }

    // ===================== 内部：授予判定与死锁检测 =====================

    /** 能否把 {@code mode} 授予 {@code sessionId}（已持有者中包含自己也视为兼容）。 */
    private boolean canGrant(LockEntry e, String sessionId, Mode mode) {
        for (Map.Entry<String, Mode> o : e.owners.entrySet()) {
            if (o.getKey().equals(sessionId)) {
                continue;   // 自己已持有的锁不与自己冲突
            }
            if (!Mode.compatible(o.getValue(), mode)) {
                return false;
            }
        }
        return true;
    }

    private void grant(LockEntry e, String sessionId, Mode mode) {
        Mode cur = e.owners.get(sessionId);
        e.owners.put(sessionId, Mode.strongest(cur, mode));
    }

    /**
     * wait-for 图成环检测：从 {@code starter} 出发，沿"等待者 → 资源持有者"的边深度优先搜索，
     * 若能回到 {@code starter} 说明会形成死锁。
     */
    private boolean createsCycle(String starter, LockEntry first) {
        Set<String> seen = new LinkedHashSet<>();
        return dfsWaitFor(starter, first, seen);
    }

    private boolean dfsWaitFor(String starter, LockEntry e, Set<String> seen) {
        for (String owner : e.owners.keySet()) {
            if (owner.equals(starter)) {
                return true;
            }
            if (seen.contains(owner)) {
                continue;
            }
            Waiter w = waiting.get(owner);   // 该持有者正在等别的资源
            if (w == null) {
                continue;
            }
            seen.add(owner);
            LockEntry next = entries.get(w.resId);
            if (next != null && dfsWaitFor(starter, next, seen)) {
                return true;
            }
        }
        return false;
    }

    // ===================== 观测（SHOW PROCESSLIST / SHOW ENGINE INNODB STATUS）=====================

    /** 当前所有已授予的锁：{@code [资源, 模式, 持有者(逗号分隔), 等待者数]}。 */
    public List<List<Object>> snapshotLocks() {
        List<List<Object>> out = new ArrayList<>();
        synchronized (mu) {
            for (Map.Entry<String, LockEntry> en : entries.entrySet()) {
                LockEntry e = en.getValue();
                if (e.owners.isEmpty()) {
                    continue;
                }
                for (Map.Entry<String, Mode> o : e.owners.entrySet()) {
                    List<Object> row = new ArrayList<>();
                    row.add(en.getKey());
                    row.add(o.getValue().name());
                    row.add(o.getKey());
                    row.add(e.waiters.size());
                    out.add(row);
                }
            }
        }
        return out;
    }

    /** 当前正在等待锁的会话：{@code [会话, 资源, 模式, 持有者]}。 */
    public List<List<Object>> snapshotWaits() {
        List<List<Object>> out = new ArrayList<>();
        synchronized (mu) {
            for (Map.Entry<String, Waiter> en : waiting.entrySet()) {
                Waiter w = en.getValue();
                LockEntry e = entries.get(w.resId);
                List<Object> row = new ArrayList<>();
                row.add(w.sessionId);
                row.add(w.resId);
                row.add(w.mode.name());
                row.add(e == null ? "" : String.join(",", e.owners.keySet()));
                out.add(row);
            }
        }
        return out;
    }

    public List<String> deadlockLog() {
        synchronized (mu) {
            return new ArrayList<>(deadlockLog);
        }
    }

    public String lastDeadlock() {
        return lastDeadlock;
    }

    /** 当前持有锁的资源数（诊断用）。 */
    public int lockedResourceCount() {
        synchronized (mu) {
            return entries.size();
        }
    }

    /** 某资源当前授予的最强模式（无锁返回 null）。 */
    public Mode grantedMode(String resId) {
        synchronized (mu) {
            LockEntry e = entries.get(resId);
            return e == null ? null : e.granted();
        }
    }

    public Set<String> heldResources() {
        synchronized (mu) {
            return Collections.unmodifiableSet(new LinkedHashSet<>(entries.keySet()));
        }
    }
}
