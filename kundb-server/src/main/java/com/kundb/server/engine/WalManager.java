package com.kundb.server.engine;

import com.kundb.server.JsonUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 重做日志（WAL, Write-Ahead Log）管理器 —— 课程《开发要求》可选功能「日志系统」的实现。
 *
 * <p><b>为什么要有它：</b>内存里改了数据还没写到磁盘就断电/崩溃，重启后磁盘上还是旧数据，
 * 这一次修改就丢了。WAL 的做法是：<b>动手改数据之前，先把"我打算做什么"按顺序写进一个只追加的日志文件</b>。
 * 崩溃后重启，把日志里"已经提交但可能没落盘"的操作重新做一遍（redo），数据就回来了。</p>
 *
 * <p>本实现的要点：</p>
 * <ol>
 *   <li><b>LSN</b>（Log Sequence Number）：每条记录一个单调递增的序号，是日志的"时间轴"；</li>
 *   <li><b>行级记录</b>：INSERT 记新行、DELETE 记被删行、UPDATE 记改前/改后行，因此 redo 有足够信息重放；</li>
 *   <li><b>事务号 txn</b>：同一事务的记录共享一个 txn；只有见到 COMMIT 记录的事务才会被 redo，
 *       见到 ROLLBACK 或没结尾的事务一律丢弃（这就是"崩溃时未提交事务自动回滚"）；</li>
 *   <li><b>CHECKPOINT</b>：把内存中的脏数据全部落盘，并在日志里打一个检查点。
 *       下次恢复只需从最后一个检查点往后扫，不必从头读日志；同时旧日志归档，避免无限增长。</li>
 * </ol>
 * <p>日志格式：一行一条 JSON（便于人工查看与课程演示），字段见 {@link WalRecord}。</p>
 */
public class WalManager {

    // ---- 操作类型 ----
    public static final String OP_BEGIN = "BEGIN";
    public static final String OP_COMMIT = "COMMIT";
    public static final String OP_ROLLBACK = "ROLLBACK";
    /** undo 记录：某行被改动<b>前</b>的影像（before-image），用于事务回滚与崩溃时撤销未提交事务。 */
    public static final String OP_UNDO = "UNDO";
    /** 回滚到保存点：<b>事务本身并不结束</b>，因此不能与 ROLLBACK 混为一谈。 */
    public static final String OP_ROLLBACK_TO_SAVEPOINT = "ROLLBACK_TO_SAVEPOINT";
    public static final String OP_SAVEPOINT = "SAVEPOINT";
    public static final String OP_CHECKPOINT = "CHECKPOINT";
    public static final String OP_INSERT = "INSERT";
    public static final String OP_UPDATE = "UPDATE";
    public static final String OP_DELETE = "DELETE";
    public static final String OP_CREATE_DB = "CREATE_DB";
    public static final String OP_DROP_DB = "DROP_DB";
    public static final String OP_CREATE_TABLE = "CREATE_TABLE";
    public static final String OP_DROP_TABLE = "DROP_TABLE";
    public static final String OP_CREATE_VIEW = "CREATE_VIEW";
    public static final String OP_DROP_VIEW = "DROP_VIEW";
    public static final String OP_CREATE_INDEX = "CREATE_INDEX";
    public static final String OP_DROP_INDEX = "DROP_INDEX";
    public static final String OP_CREATE_USER = "CREATE_USER";
    public static final String OP_GRANT = "GRANT";
    public static final String OP_REVOKE = "REVOKE";
    public static final String OP_CREATE_TABLESPACE = "CREATE_TABLESPACE";
    public static final String OP_DROP_TABLESPACE = "DROP_TABLESPACE";
    public static final String OP_EXTEND_TABLESPACE = "EXTEND_TABLESPACE";
    public static final String OP_OPTIMIZE_TABLE = "OPTIMIZE_TABLE";

    /** 一条日志记录。用 public 字段以便 Jackson 直接序列化为 JSON 对象。 */
    public static final class WalRecord {
        public long lsn;        // 日志序号，全局单调递增
        public long ts;         // 写入时间戳（毫秒）
        public String user;     // 执行者（会话用户）
        public String txn;      // 事务号；autocommit 下为 null
        public String op;       // 操作类型，见上面的常量
        public String db;       // 库名（可空）
        public String table;    // 表名（可空）
        public int rows;        // 影响行数
        public String detail;   // 人类可读描述（如 SQL 摘要）
        public Map<String, Object> payload; // 行级数据：insert/new/old/row/columns/select 等
    }

    /** 保留的归档日志文件个数（超出按时间从旧到新删除）。 */
    private static final int KEEP_ARCHIVES = 3;

    private final Path walPath;
    private final Path archiveDir;
    private long lsn;              // 当前已分配到的最大 LSN
    private long checkpointLsn;    // 最后一个检查点的 LSN（恢复起点）
    private long recordCount;      // 当前日志文件的记录条数

    public WalManager(Path dataRoot) {
        this.walPath = dataRoot.resolve("kundb.log");
        this.archiveDir = dataRoot.resolve("_wal_archive");
    }

    // ===================== 生命周期 =====================

    /** 打开（必要时创建）日志，并恢复 LSN / 检查点等运行时状态。 */
    public void open() throws EngineException {
        try {
            Files.createDirectories(archiveDir);
            if (!Files.exists(walPath)) {
                Files.createFile(walPath);
                return;
            }
            List<WalRecord> all = readAll();
            // 兼容：早期版本写的日志只有 op/detail/ts，没有 LSN/txn，无法用于恢复。
            // 检测到这类"残缺记录"就把旧日志归档，从空日志重新开始。
            boolean legacy = false;
            for (WalRecord r : all) {
                if (r.lsn <= 0) {
                    legacy = true;
                    break;
                }
            }
            if (legacy) {
                Files.move(walPath, archiveDir.resolve("legacy-" + System.currentTimeMillis() + ".log"),
                        StandardCopyOption.REPLACE_EXISTING);
                Files.createFile(walPath);
                lsn = 0;
                checkpointLsn = 0;
                recordCount = 0;
                return;
            }
            long lastCkpt = 0;
            for (WalRecord r : all) {
                if (r.lsn > lsn) {
                    lsn = r.lsn;
                }
                if (OP_CHECKPOINT.equals(r.op) && r.lsn > lastCkpt) {
                    lastCkpt = r.lsn;
                    checkpointLsn = effectiveLsnOf(r);   // 活跃事务会把恢复起点往前推
                }
            }
            recordCount = all.size();
        } catch (IOException e) {
            throw new EngineException(com.kundb.common.protocol.ErrorCode.SERVER_ERROR,
                    "打开日志文件失败: " + e.getMessage());
        }
    }

    // ===================== 写 =====================

    /** 追加一条日志记录（同步落盘，保证"先写日志后改数据"）。返回写入的记录（含分配到的 LSN）。 */
    public WalRecord append(String op, String db, String table, String detail, int rows,
                            String user, String txn, Map<String, Object> payload) {
        WalRecord r = new WalRecord();
        r.lsn = ++lsn;
        r.ts = System.currentTimeMillis();
        r.user = user;
        r.txn = txn;
        r.op = op;
        r.db = db;
        r.table = table;
        r.rows = rows;
        r.detail = detail;
        r.payload = payload;
        try {
            byte[] line = (JsonUtils.mapper().writeValueAsString(r) + "\n").getBytes(StandardCharsets.UTF_8);
            Files.write(walPath, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            recordCount++;
        } catch (IOException e) {
            // 日志记录失败不应阻断主流程，但必须打出告警
            System.err.println("[WAL] 写入失败: " + e.getMessage());
        }
        return r;
    }

    // ===================== 读 =====================

    /** 读取日志文件中的全部记录（按写入顺序）。 */
    public List<WalRecord> readAll() throws EngineException {
        List<WalRecord> out = new ArrayList<>();
        if (!Files.exists(walPath)) {
            return out;
        }
        try {
            for (String line : Files.readAllLines(walPath, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> m = JsonUtils.mapper().readValue(line, Map.class);
                out.add(fromMap(m));
            }
        } catch (IOException e) {
            throw new EngineException(com.kundb.common.protocol.ErrorCode.SERVER_ERROR,
                    "读取日志失败: " + e.getMessage());
        }
        return out;
    }

    /** 读取最后一个 CHECKPOINT 之后的记录 —— 崩溃恢复只需要这部分。 */
    public List<WalRecord> readSinceCheckpoint() throws EngineException {
        List<WalRecord> out = new ArrayList<>();
        for (WalRecord r : readAll()) {
            if (r.lsn > checkpointLsn) {
                out.add(r);
            }
        }
        return out;
    }

    private static WalRecord fromMap(Map<String, Object> m) {
        WalRecord r = new WalRecord();
        r.lsn = m.get("lsn") == null ? 0 : ((Number) m.get("lsn")).longValue();
        r.ts = m.get("ts") == null ? 0 : ((Number) m.get("ts")).longValue();
        r.user = (String) m.get("user");
        r.txn = (String) m.get("txn");
        r.op = (String) m.get("op");
        r.db = (String) m.get("db");
        r.table = (String) m.get("table");
        r.rows = m.get("rows") == null ? 0 : ((Number) m.get("rows")).intValue();
        r.detail = (String) m.get("detail");
        @SuppressWarnings("unchecked")
        Map<String, Object> p = (Map<String, Object>) m.get("payload");
        r.payload = p;
        return r;
    }

    // ===================== 检查点与归档 =====================

    /**
     * 写入一条 CHECKPOINT 记录，并把之前的日志归档（控制日志体积）。返回归档后的文件数。
     *
     * <p><b>关键约束：</b>如果此刻还有<b>正在进行中</b>的事务（BEGIN 之后既没有 COMMIT 也没有 ROLLBACK），
     * 就<b>不能归档</b> —— 归档会清空日志，这些事务的 undo 记录（改前影像）也就一起丢了，
     * 万一随后崩溃，重启后既不能 redo 也不能 undo，未提交的改动就永久留在磁盘上了。
     * 因此这种情况下只把"有效恢复起点"回退到最早活跃事务之前，日志留到事务结束再归档。</p>
     */
    public int checkpoint() throws EngineException {
        long minActiveLsn = oldestActiveTxnLsn();
        boolean hasActive = minActiveLsn != Long.MAX_VALUE;
        Map<String, Object> ckptPayload = new LinkedHashMap<>();
        ckptPayload.put("activeTxnMinLsn", hasActive ? minActiveLsn : -1L);
        WalRecord r = append(OP_CHECKPOINT, null, null,
                hasActive ? "checkpoint (保留活跃事务日志，暂不归档)" : "checkpoint", 0, null, null,
                ckptPayload);
        // 有活跃事务时，恢复起点必须回退到它们的第一条记录之前，否则读不到它们的 undo
        checkpointLsn = hasActive ? Math.max(0, minActiveLsn - 1) : r.lsn;
        if (hasActive) {
            return 0;
        }
        try {
            if (!Files.exists(walPath) || Files.size(walPath) == 0) {
                return 0;
            }
            Path archived = archiveDir.resolve(String.format("kundb-%d.log", System.currentTimeMillis()));
            Files.move(walPath, archived, StandardCopyOption.REPLACE_EXISTING);
            Files.createFile(walPath);
            recordCount = 0;
            // 归档文件只保留最近 KEEP_ARCHIVES 个
            List<Path> files = new ArrayList<>();
            try (var s = Files.list(archiveDir)) {
                s.filter(p -> p.getFileName().toString().endsWith(".log")).forEach(files::add);
            }
            files.sort((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()));
            for (int i = KEEP_ARCHIVES; i < files.size(); i++) {
                Files.deleteIfExists(files.get(i));
            }
            return Math.min(files.size(), KEEP_ARCHIVES);
        } catch (IOException e) {
            throw new EngineException(com.kundb.common.protocol.ErrorCode.SERVER_ERROR,
                    "日志归档失败: " + e.getMessage());
        }
    }

    /** 当前日志中"最早一个仍在进行中的事务"的首条记录 LSN；没有活跃事务返回 Long.MAX_VALUE。 */
    private long oldestActiveTxnLsn() {
        try {
            List<WalRecord> all = readAll();
            // 必须按 LSN 顺序推进：BEGIN 打开事务，COMMIT / ROLLBACK 关闭事务。
            // 不能"全局"判断某个 txn 是否结束过 —— 同一个会话 id 会被多个事务依次复用，
            // 前一个事务的 COMMIT 不能用来判定后一个事务已经结束。
            Map<String, Long> open = new LinkedHashMap<>();
            for (WalRecord x : all) {
                if (x.txn == null) {
                    continue;
                }
                if (OP_BEGIN.equals(x.op)) {
                    open.putIfAbsent(x.txn, x.lsn);
                } else if (OP_COMMIT.equals(x.op) || OP_ROLLBACK.equals(x.op)) {
                    open.remove(x.txn);      // ROLLBACK TO SAVEPOINT 不算结束事务
                }
            }
            long min = Long.MAX_VALUE;
            for (long v : open.values()) {
                min = Math.min(min, v);
            }
            return min;
        } catch (Exception ignore) {
            return Long.MAX_VALUE;      // 读不了日志就按"无活跃事务"处理，不阻断检查点
        }
    }

    /** 一条 CHECKPOINT 记录的"有效恢复起点"：有活跃事务时回退到它们之前，否则就是它自己的 LSN。 */
    private static long effectiveLsnOf(WalRecord r) {
        if (r.payload != null && r.payload.get("activeTxnMinLsn") instanceof Number n) {
            long m = n.longValue();
            if (m > 0) {
                return Math.max(0, m - 1);
            }
        }
        return r.lsn;
    }

    // ===================== 状态 =====================

    public long currentLsn() {
        return lsn;
    }

    public long checkpointLsn() {
        return checkpointLsn;
    }

    public long recordCount() {
        return recordCount;
    }

    public Path walPath() {
        return walPath;
    }

    /** 日志文件大小（字节）。 */
    public long sizeBytes() {
        try {
            return Files.exists(walPath) ? Files.size(walPath) : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    /** 归档目录下的历史日志文件数。 */
    public long archiveCount() {
        try (var s = Files.list(archiveDir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".log")).count();
        } catch (IOException e) {
            return 0;
        }
    }

    /** 构造行级 payload：单行操作（INSERT/DELETE）。 */
    public static Map<String, Object> rowPayload(String key, Object[] row) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put(key, row == null ? null : java.util.Arrays.asList(row));
        return p;
    }
}
