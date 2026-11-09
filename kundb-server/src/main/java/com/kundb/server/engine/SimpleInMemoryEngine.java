package com.kundb.server.engine;

import com.kundb.common.protocol.ColumnType;
import com.kundb.common.protocol.ErrorCode;
import com.kundb.server.JsonUtils;
import com.kundb.server.session.SessionContext;
import com.kundb.server.engine.SqlLexer.Token;
import com.kundb.server.engine.SqlLexer.TokenType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文件式自研执行引擎：内存中维持库/表/行，并在变更时落盘（每个库一个目录、每表一个 JSON 文件）。
 * 重启后从磁盘加载，数据不丢。支持的 SQL 子集足以让 DataGrip / Qt 客户端连上来建库、建表、增删查。
 * <p>后续 W3–W7 替换本类为正式 Lexer/Parser/Planner/Executor 内核时，只需保持 {@link ExecutionEngine} 接口不变。</p>
 */
public class SimpleInMemoryEngine implements ExecutionEngine {

    // ===================== 内存数据模型 =====================

    static final class ColumnDef {
        // name / type 可变：ALTER TABLE ... CHANGE COLUMN <旧> <新> <类型> 与 MODIFY COLUMN 需要就地改写列定义
        String name;
        ColumnType type;
        boolean nullable;
        boolean pk;
        /** 默认值；ALTER TABLE ... ALTER COLUMN c SET/DROP DEFAULT 需要改写，故非 final。 */
        Object def;
        /** 自增列（建表时 AUTO_INCREMENT 修饰）；一张表至多一个。 */
        boolean autoIncrement;
        /** 列级唯一约束（建表时 UNIQUE 修饰）；与表级 UNIQUE 一起参与唯一性校验。 */
        boolean unique;
        /** 列注释（COMMENT '文本'）。空串表示无注释；供 information_schema.COLUMNS.COLUMN_COMMENT 读取。 */
        String comment = "";

        ColumnDef(String name, ColumnType type, boolean nullable, boolean pk, Object def) {
            this(name, type, nullable, pk, def, false, false);
        }

        ColumnDef(String name, ColumnType type, boolean nullable, boolean pk, Object def,
                  boolean autoIncrement, boolean unique) {
            this(name, type, nullable, pk, def, autoIncrement, unique, "");
        }

        ColumnDef(String name, ColumnType type, boolean nullable, boolean pk, Object def,
                  boolean autoIncrement, boolean unique, String comment) {
            this.name = name;
            this.type = type;
            this.nullable = nullable;
            this.pk = pk;
            this.def = def;
            this.autoIncrement = autoIncrement;
            this.unique = unique;
            this.comment = comment == null ? "" : comment;
        }
    }

    /** 外键定义（建表时 FOREIGN KEY 子句）。默认 RESTRICT：子表引用值必须已存在于父表或被引用列为 NULL。 */
    static final class FkDef {
        final String col;        // 子表列
        final String refTable;   // 父表（库内）
        final String refCol;     // 父表被引用列

        FkDef(String col, String refTable, String refCol) {
            this.col = col;
            this.refTable = refTable;
            this.refCol = refCol;
        }
    }

    static final class Table {
        String name;
        final List<ColumnDef> cols = new ArrayList<>();
        /**
         * 行存储。<b>下标即槽号</b>，与三段式文件里"记录存储空间"的第 N 个位置一一对应。
         * 元素为 {@code null} 表示该槽是空位（从未用过，或被删除标记过），遍历时必须跳过 ——
         * 这正是"删除只做标记、不移动数据"在内存中的对应形态：删掉一行后，它后面的行下标一律不变。
         */
        final List<Object[]> rows = new ArrayList<>();
        /** 哪些槽是"被删除标记留下来的空位"（对应分配表里的 '2'），用于落盘时如实反映删除标记。 */
        final Map<Integer, Boolean> deletedSlots = new LinkedHashMap<>();
        final Set<String> indexCols = new LinkedHashSet<>();
        final Map<String, String> indexNames = new LinkedHashMap<>(); // 索引名 -> 列名
        final Map<String, Map<Object, List<Integer>>> indexMaps = new LinkedHashMap<>();
        boolean indexDirty = true;
        String tablespace = "kundspace";
        /** 表注释（CREATE TABLE ... COMMENT='x' / ALTER TABLE t COMMENT 'x'）。
         *  供 information_schema.TABLES.TABLE_COMMENT、SHOW TABLE STATUS.Comment、SHOW CREATE TABLE。 */
        String comment = "";

        // ---- 附加功能1：三段式记录文件的内存侧元数据 ----
        int slotSize = 0;                       // 槽大小（建表时按列定义计算）
        int slotCount = 0;                      // 文件中的槽总数
        boolean slotFileReady = false;          // 槽文件是否已就绪（未就绪则需要全量重写一次）
        RecordFileStore.Header fileHeader;      // 内存中缓存的格式信息区
        /** 本次待落盘的槽号集合：DML 只把动过的槽记下来，落盘时做随机写而不是整表重写。 */
        final Set<Integer> dirtySlots = new LinkedHashSet<>();
        /**
         * 每行<b>最后修改它的事务号</b>（槽号 -> trxId；查不到视为 0，即"从未经事务改动"）。
         * 这是 MVCC 判断版本可见性的关键：快照读时拿它和自己的 ReadView 比对，
         * 看不见就沿版本链往回找更早的版本。
         */
        final Map<Integer, Long> rowTrxId = new ConcurrentHashMap<>();
        /**
         * 行版本链（MVCC 的 roll pointer）：{@code 槽号 -> [(产生该版本的事务号, 该版本的行内容), ...]}，
         * 从旧到新追加。每次改动前把"改前的样子"压栈；快照读发现当前版本不可见时，
         * 就从后往前找第一个我能看见的版本。{@code 行内容为 null} 表示该版本下这行还不存在（新插入的）。
         * <p>真实 InnoDB 正是把 undo 日志当作版本链用的，这里同构。</p>
         */
        final Map<Integer, List<Object[]>> versions = new ConcurrentHashMap<>();

        // ---- 列约束与自增（附加功能：约束 / AUTO_INCREMENT）----
        long autoIncValue = 0;                                   // 自增列下一个待分配值
        final List<List<String>> uniqueKeys = new ArrayList<>();  // 表级唯一约束（每组一列或多列）
        final List<Pred> tableChecks = new ArrayList<>();         // 表级 CHECK 表达式
        final List<FkDef> foreignKeys = new ArrayList<>();        // 外键

        Table(String name) {
            this.name = name;
        }

        /** 有效行数（跳过空位）。 */
        int liveRows() {
            int n = 0;
            for (Object[] r : rows) {
                if (r != null) {
                    n++;
                }
            }
            return n;
        }

        int indexOf(String n) {
            for (int i = 0; i < cols.size(); i++) {
                if (cols.get(i).name.equalsIgnoreCase(n)) {
                    return i;
                }
            }
            return -1;
        }
    }

    static final class Database {
        final String name;
        final Map<String, Table> tables = new LinkedHashMap<>();
        final Map<String, String> views = new LinkedHashMap<>(); // 视图名 -> SELECT 文本

        Database(String name) {
            this.name = name;
        }
    }

    private final Map<String, Database> databases = new LinkedHashMap<>();
    private final Map<String, String> users = new LinkedHashMap<>();
    private Path dataRoot;

    // ---- 访问控制：用户与权限（users 仅存明文密码，权限见 userRegistry）----
    private final Map<String, User> userRegistry = new LinkedHashMap<>();
    private static final EnumSet<Priv> ALL_PRIVS = EnumSet.allOf(Priv.class);

    // ---- 事务：每会话一个 undo 快照（首改前对该表行做深拷贝）----
    private final Map<String, Txn> txns = new LinkedHashMap<>();
    /** 当前正在执行的会话（子查询执行时复用，避免逐层透传 session）。 */
    private SessionContext currentSession;
    /**
     * 当前 SELECT 正在使用的读视图（快照）。为 null 表示"当前读"（读最新版本），
     * UPDATE/DELETE/READ UNCOMMITTED 走这条路。用字段传递是为了避免给十几处方法加参数。
     */
    private ReadView currentReadView = null;

    // ---- 日志（WAL 重做日志）与表空间管理 ----
    private WalManager wal;
    private TablespaceManager tspaces;
    // ---- 并发控制：全局元数据锁 / 锁管理器 / 事务号 / 活跃事务 / MVCC 版本链 ----
    /**
     * 元数据锁（读写锁）：保护 {@code databases}、{@code users}、{@code userRegistry} 这些
     * <b>共享结构</b>不被并发破坏。Netty worker 是多线程的，两个连接同时建表/插数据会并发改这些容器，
     * 轻则 ConcurrentModificationException 让连接断开，重则数据丢失。
     * <p>读锁：SELECT 等纯读操作（可并行）；写锁：DDL、DML 的结构性改动（独占）。</p>
     */
    private final ReentrantReadWriteLock metaLock = new ReentrantReadWriteLock(true);
    /** 锁管理器：表级意向/共享/排他锁 + 行级 S/X 锁 + 等待超时 + 死锁检测。 */
    private final LockManager lockMgr = new LockManager();
    /** 事务号发号器（单调递增）。 */
    private final AtomicLong trxIdGen = new AtomicLong(0);
    /** 活跃事务：trxId -> Txn。ReadView 靠它判断"哪些事务的改动我不该看见"。 */
    private final Map<Long, Txn> activeTxns = new ConcurrentHashMap<>();
    /**
     * MVCC 版本链：已提交事务的 undo 记录 {@code trxId -> undo 列表}。
     * 快照读发现当前版本不可见时，就沿这里往回找该行更早的样子 ——
     * <b>undo 日志在这里被复用成了版本链</b>（真实 InnoDB 也是这么做的）。
     */
    private final Map<Long, List<UndoRecord>> versionStore = new ConcurrentHashMap<>();
    /** GLOBAL 级默认隔离级别（SET GLOBAL TRANSACTION ISOLATION LEVEL 修改）。新会话沿用。 */
    private volatile SessionContext.Isolation globalIsolation = SessionContext.Isolation.REPEATABLE_READ;
    /** GLOBAL 级锁等待超时（毫秒，默认 10 秒，与 MySQL 的 innodb_lock_wait_timeout=50 同类但更短便于演示）。 */
    private volatile long globalLockWaitTimeoutMs = 10000L;
    /** 连接/线程注册表（SHOW PROCESSLIST）：sessionId -> 运行时信息。 */
    private final Map<String, Proc> processes = new ConcurrentHashMap<>();

    /** 崩溃模拟点（仅用于课程演示崩溃恢复）：
     *  启动参数 {@code -Dkundb.crash=commit} 表示"写完 COMMIT 日志、还没来得及落盘"就立刻退出进程，
     *  用于制造"已提交但未落盘"的崩溃现场，重启后应由 WAL redo 把数据补回来。 */
    private final String crashAt = System.getProperty("kundb.crash");
    /** 上一次崩溃恢复中，靠 undo 日志撤销掉的行数（供 init 决定是否需要重新落盘）。 */
    private long lastRecoverUndone = 0;

    enum Priv { SELECT, INSERT, UPDATE, DELETE, CREATE, DROP, GRANT }

    static final class Grant {
        final String db;   // "*" 或具体库名
        final String tbl;  // "*" 或具体表名
        final EnumSet<Priv> privs;
        Grant(String db, String tbl, EnumSet<Priv> privs) {
            this.db = db;
            this.tbl = tbl;
            this.privs = privs;
        }
    }

    static final class User {
        String password;
        final List<Grant> grants = new ArrayList<>();
        User(String password) {
            this.password = password;
        }
    }

    static final class Index {
        final String column;
        final Map<Object, List<Integer>> map = new LinkedHashMap<>();
        Index(String column) {
            this.column = column;
        }
    }

    /** undo 日志记录的操作类型：标明"这次改动属于哪种 DML"，回滚时施加它的<b>逆操作</b>。 */
    enum UndoOp { INSERT, UPDATE, DELETE, TRUNCATE }

    /**
     * 一条 undo 日志记录 —— <b>改前影像（before-image）</b>：某一行在被本事务改动之前长什么样。
     *
     * <p>与"整表快照"的根本区别：快照是事务首改前把整张表复制一份（改 1 行也要复制 1 万行，
     * 且只能整表覆写回去，无法支持保存点这种部分回滚）；undo 日志则<b>只记真正动过的那几行</b>，
     * 按改动顺序追加、回滚时逆序应用，天然支持保存到任意中间点。</p>
     *
     * <p>逆操作对应关系（这是 undo 的核心语义）：</p>
     * <ul>
     *   <li>INSERT → 把该槽重新清空（若复用了删除标记空位，还要把删除标记补回去）</li>
     *   <li>DELETE → 把改前行按原槽号写回，并清掉删除标记</li>
     *   <li>UPDATE → 把改前值按原槽号写回（原地还原）</li>
     *   <li>TRUNCATE → 只还原自增计数器（行本身由逐条 DELETE 记录还原）</li>
     * </ul>
     */
    static final class UndoRecord {
        final Table table;
        final int slot;                // 行所在槽号：回滚必须落在<b>同一个槽</b>，才是真正的"原地还原"
        final UndoOp op;
        final Object[] before;         // 改前行内容（INSERT 时为 null —— 插入前该槽本来就是空位）
        final boolean wasDeletedSlot;  // INSERT 复用了"删除标记空位"时，回滚要把删除标记补回去
        final long autoIncBefore;      // 仅 TRUNCATE 用：还原自增计数器

        UndoRecord(Table table, int slot, UndoOp op, Object[] before, boolean wasDeletedSlot,
                   long autoIncBefore) {
            this.table = table;
            this.slot = slot;
            this.op = op;
            this.before = before == null ? null : before.clone();
            this.wasDeletedSlot = wasDeletedSlot;
            this.autoIncBefore = autoIncBefore;
        }
    }

    static final class Txn {
        /** undo 日志：按"改动发生的顺序"追加；回滚时<b>逆序</b>应用（后进先出，像 Ctrl+Z）。 */
        final List<UndoRecord> undo = new ArrayList<>();
        final Map<Table, String> dirty = new LinkedHashMap<>(); // 事务内被改动的表 -> 所属库名
        /** 保存点名 -> 建立该保存点时 undo 日志的长度（回滚到该点只需撤销这之后追加的记录）。 */
        final Map<String, Integer> savepoints = new LinkedHashMap<>();
        /** 事务号（进入事务时分配，单调递増）。 */
        long trxId = 0L;
        /** 本事务的读视图。REPEATABLE READ 下首次读创建后全程复用；READ COMMITTED 下每语句重建。 */
        ReadView view = null;
        /** 本事务已加过的行锁资源（{@code db.table#slot}），COMMIT/ROLLBACK 时统一释放。 */
        final Set<String> lockedRows = ConcurrentHashMap.newKeySet();
        /** 本事务已加过的表锁资源（{@code db.table}）。 */
        final Set<String> lockedTables = ConcurrentHashMap.newKeySet();
    }

    /**
     * 读视图（ReadView）—— 快照读的"时间切片"。
     *
     * <p>创建时把<b>当前正在活跃（未提交）的其他事务</b>记下来。之后判断某一行版本能否看见：</p>
     * <ul>
     *   <li>版本是我自己改的 → 可见</li>
     *   <li>版本的事务号比快照时已分配的最大号还大 → 看不见（快照之后才出现的新改动）</li>
     *   <li>版本的事务号在"快照时的活跃事务"里 → 看不见（它当时还没提交）</li>
     *   <li>其余 → 可见（早就提交了）</li>
     * </ul>
     *
     * <p>REPEATABLE READ 与 READ COMMITTED 的差别<b>只在于何时创建它</b>：
     * RR 是事务首次读时建一次、全程复用（所以两次读结果一定一致）；
     * RC 是每条语句建一次（所以能看见别人刚提交的）。</p>
     */
    static final class ReadView {
        final long creatorTrxId;
        /** 创建快照时仍处于活跃状态的其他事务号。 */
        final Set<Long> active;
        /** 创建快照时已分配到的最大事务号（>= 它的一律视为"未来"，不可见）。 */
        final long maxTrxId;

        ReadView(long creatorTrxId, Set<Long> active, long maxTrxId) {
            this.creatorTrxId = creatorTrxId;
            this.active = active;
            this.maxTrxId = maxTrxId;
        }

        /** 该 trxId 产生的版本，对我是否可见。 */
        boolean sees(long trx) {
            if (trx == 0L) {
                return true;                   // 从未被事务改过（建表时的初始数据）
            }
            if (trx == creatorTrxId) {
                return true;                   // 我自己改的，当然看得见
            }
            if (trx > maxTrxId) {
                return false;                  // 快照之后才开始的事务
            }
            return !active.contains(trx);      // 快照时它还活跃（未提交）→ 看不见
        }
    }

    /** SHOW PROCESSLIST 的一行：一条连接当前的运行状态。 */
    static final class Proc {
        final String sessionId;
        final String user;
        volatile String database = "";
        volatile String command = "Sleep";
        volatile String state = "";
        volatile String info = "";
        volatile long trxId = 0L;
        volatile String isolation = "";
        final long connectAt = System.currentTimeMillis();
        volatile long lastActiveAt = System.currentTimeMillis();

        Proc(String sessionId, String user) {
            this.sessionId = sessionId;
            this.user = user;
        }
    }

    // 注：表空间的数据模型与算法已抽到独立的 {@link TablespaceManager}（页/区/数据文件/自动扩展），
    //     日志相关抽到 {@link WalManager}（LSN/行级记录/检查点/归档/恢复）。
    //     这里只持有引用，保持引擎类聚焦在 SQL 语义上。

    // ===================== ExecutionEngine 接口 =====================

    @Override
    public void init() throws Exception {
        dataRoot = Path.of(System.getProperty("kundb.datadir", "kundb-data")).toAbsolutePath();
        Files.createDirectories(dataRoot);
        wal = new WalManager(dataRoot);
        wal.open();                 // 打开重做日志，恢复 LSN / 检查点
        tspaces = new TablespaceManager(dataRoot);
        tspaces.load();             // 加载表空间与数据文件分配信息
        users.put("root", "123456");
        userRegistry.put("root", new User("123456"));
        userRegistry.get("root").grants.add(new Grant("*", "*", ALL_PRIVS.clone()));
        loadAuth();      // 加载持久化用户/权限（root 始终保留 *.* 全权限）
        loadAll();       // 从磁盘加载库/表
        // 崩溃恢复：重放上次检查点之后"已提交"的日志（未提交的事务自动丢弃）
        long redo = recoverFromWal();
        rebuildAllIndexes();
        if (databases.isEmpty()) {
            seedDemo();
            persistAll();
        } else if (redo > 0 || lastRecoverUndone > 0) {
            persistAll();   // 把 redo 补回来 / undo 撤销掉的结果固化到磁盘
        }
        persistAuth();
        System.out.println("[KUN-DB] WAL: lsn=" + wal.currentLsn() + ", checkpoint=" + wal.checkpointLsn()
                + ", 本次恢复重放 " + redo + " 条");
    }

    @Override
    public boolean authenticate(String user, String password) throws EngineException {
        String p = users.get(user);
        return p != null && p.equals(password);
    }

    @Override
    public String lookupPassword(String user) {
        return users.get(user);
    }

    @Override
    public QueryResult execute(SessionContext session, String sql) throws EngineException {
        List<Token> toks = SqlLexer.tokenize(sql);
        Ts ts = new Ts(toks);
        currentSession = session;
        if (!ts.hasNext()) {
            throw syn("空语句");
        }
        String kw = ts.peek().text.toUpperCase();
        // 元数据锁：DDL 会改动 databases/users 这些共享结构，必须独占；
        // DML 与 SELECT 只取读锁（彼此可并发），行/表级互斥交给 LockManager，这样才有真正的并发度。
        boolean structural = kw.equals("CREATE") || kw.equals("DROP") || kw.equals("ALTER")
                || kw.equals("GRANT") || kw.equals("REVOKE") || kw.equals("TRUNCATE");
        if (structural) {
            metaLock.writeLock().lock();
        } else {
            metaLock.readLock().lock();
        }
        markProc(session, "Query", sql);
        try {
            switch (kw) {
                case "CREATE" -> { return doCreate(session, ts); }
                case "DROP" -> { return doDrop(session, ts); }
                case "USE" -> { return doUse(session, ts); }
                case "INSERT" -> { QueryResult r = doInsert(session, ts); invalidateAllStats(); return r; }
                case "SELECT" -> { return doSelect(session, ts); }
                case "EXPLAIN" -> { return doExplain(session, ts); }
                case "ANALYZE" -> { return doAnalyze(session, ts); }
                case "UPDATE" -> { QueryResult r = doUpdate(session, ts); invalidateAllStats(); return r; }
                case "DELETE" -> { QueryResult r = doDelete(session, ts); invalidateAllStats(); return r; }
                case "SHOW" -> { return doShow(session, ts); }
                case "DESC" -> { return doDesc(session, ts); }
                case "BEGIN", "START" -> { return doTransaction(session, ts); }
                case "COMMIT" -> { return doCommit(session); }
                case "ROLLBACK" -> { return doRollback(session, ts); }
                case "SAVEPOINT" -> { return doSavepoint(session, ts); }
                case "RELEASE" -> { return doReleaseSavepoint(session, ts); }
                case "GRANT" -> { return doGrant(session, ts); }
                case "REVOKE" -> { return doRevoke(session, ts); }
                case "CHECKPOINT" -> { return doCheckpoint(session); }
                case "ALTER" -> { QueryResult r = doAlter(session, ts); invalidateAllStats(); return r; }
                case "OPTIMIZE" -> { QueryResult r = doOptimize(session, ts); invalidateAllStats(); return r; }
                case "TRUNCATE" -> { QueryResult r = doTruncate(session, ts); invalidateAllStats(); return r; }
                case "LOCK" -> { return doLockTables(session, ts); }
                case "UNLOCK" -> { return doUnlockTables(session, ts); }
                case "SET" -> { return doSet(session, ts); }
                default -> throw new EngineException(ErrorCode.SYNTAX_ERROR, "不支持的语句: " + kw);
            }
        } finally {
            if (structural) {
                metaLock.writeLock().unlock();
            } else {
                metaLock.readLock().unlock();
            }
            // autocommit 语句（不在显式事务中）：语句结束即"隐式提交"，
            // 必须立刻释放它临时申请的锁并注销事务号 —— 否则活跃事务表里会留下永不结束的僵尸事务，
            // 导致其他事务的快照永远看不见它的改动。
            if (session != null && !session.isInTransaction()) {
                endTransaction(session, true);   // 内部会保留 LOCK TABLES 的显式锁
                if (session.getExplicitTableLocks().isEmpty()) {
                    lockMgr.releaseAll(session.getSessionId());
                }
            }
            markProc(session, "Sleep", "");
        }
    }

    @Override
    public boolean databaseExists(String db) {
        return databases.containsKey(db);
    }

    @Override
    public List<String> listDatabases() {
        return new ArrayList<>(databases.keySet());
    }

    @Override
    public List<String> listTables(String db) throws EngineException {
        Database d = databases.get(db);
        if (d == null) {
            throw new EngineException(ErrorCode.UNKNOWN_DATABASE, "库不存在: " + db);
        }
        return new ArrayList<>(d.tables.keySet());
    }

    @Override
    public List<ColumnMeta> describe(String db, String table) throws EngineException {
        Database d = databases.get(db);
        if (d == null) {
            throw new EngineException(ErrorCode.UNKNOWN_DATABASE, "库不存在: " + db);
        }
        Table t = d.tables.get(table);
        if (t == null) {
            String body = d.views.get(table);
            if (body != null) {
                Table vt = materializeView(adminSession(db), table, body);
                List<ColumnMeta> out = new ArrayList<>();
                for (ColumnDef c : vt.cols) {
                    out.add(new ColumnMeta(c.name, c.type, c.nullable, c.pk, c.comment));
                }
                return out;
            }
            throw new EngineException(ErrorCode.UNKNOWN_TABLE, "表不存在: " + table);
        }
        List<ColumnMeta> out = new ArrayList<>();
        for (ColumnDef c : t.cols) {
            out.add(new ColumnMeta(c.name, c.type, c.nullable, c.pk, c.comment));
        }
        return out;
    }

    @Override
    public String tableComment(String db, String table) {
        Database d = databases.get(db);
        if (d == null) {
            return "";
        }
        Table t = d.tables.get(table);
        return t == null || t.comment == null ? "" : t.comment;
    }

    /** 列出表的外键（每个外键返回 [子表列, 父表名, 父表列]），供元数据内省（information_schema.key_column_usage / referential_constraints）。 */
    @Override
    public List<String[]> listForeignKeys(String db, String table) throws EngineException {
        Database d = databases.get(db);
        if (d == null) {
            throw new EngineException(ErrorCode.UNKNOWN_DATABASE, "库不存在: " + db);
        }
        Table t = d.tables.get(table);
        if (t == null) {
            throw new EngineException(ErrorCode.UNKNOWN_TABLE, "表不存在: " + table);
        }
        List<String[]> out = new ArrayList<>();
        for (FkDef fk : t.foreignKeys) {
            out.add(new String[]{fk.col, fk.refTable, fk.refCol});
        }
        return out;
    }

    /** 物化视图/描述元数据用的管理员会话（root 身份，绕过权限校验）。 */
    private SessionContext adminSession(String db) {
        SessionContext sc = new SessionContext();
        sc.setAuthed(true);
        sc.setUser("root");
        sc.setCurrentDatabase(db);
        return sc;
    }

    @Override
    public String createTableDdl(String db, String table) throws EngineException {
        Database d = databases.get(db);
        if (d != null) {
            String body = d.views.get(table);
            if (body != null) {
                // 视图 DDL：使用与 MySQL `SHOW CREATE VIEW` 一致的 "CREATE VIEW `name` AS ..." 形式。
                return "CREATE VIEW `" + table + "` AS " + body;
            }
        }
        Table t = requireTable(db, table);
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE ").append(table).append(" (\n");
        for (int i = 0; i < t.cols.size(); i++) {
            ColumnDef c = t.cols.get(i);
            sb.append("  ").append(c.name).append(" ").append(c.type.name());
            if (!c.nullable) {
                sb.append(" NOT NULL");
            }
            if (c.pk) {
                sb.append(" PRIMARY KEY");
            }
            if (c.comment != null && !c.comment.isEmpty()) {
                sb.append(" COMMENT '").append(c.comment.replace("'", "''")).append("'");
            }
            if (i < t.cols.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append(")");
        if (t.comment != null && !t.comment.isEmpty()) {
            sb.append(" COMMENT='").append(t.comment.replace("'", "''")).append("'");
        }
        return sb.toString();
    }

    // ===================== DDL / 会话 =====================

    private QueryResult doUse(SessionContext session, Ts ts) throws EngineException {
        ts.expectKw("USE");
        String db = ts.expect(TokenType.IDENT, "库名").text;
        if (!databases.containsKey(db)) {
            throw new EngineException(ErrorCode.UNKNOWN_DATABASE, "库不存在: " + db);
        }
        session.setCurrentDatabase(db);
        return QueryResult.ofAffected(0);
    }

    private QueryResult doCreate(SessionContext session, Ts ts) throws EngineException {
        ts.expectKw("CREATE");
        if (ts.eatKw("USER")) {
            return doCreateUser(session, ts);
        }
        if (ts.eatKw("DATABASE") || ts.eatKw("SCHEMA")) {
            requirePriv(session, "*", "*", Priv.CREATE);
            boolean ifNotExists = ts.eatKw("IF") && ts.eatKw("NOT") && ts.eatKw("EXISTS");
            String name = ts.expect(TokenType.IDENT, "库名").text;
            if (databases.containsKey(name)) {
                if (ifNotExists) {
                    return QueryResult.ofAffected(0);
                }
                throw new EngineException(ErrorCode.SYNTAX_ERROR, "库已存在: " + name);
            }
            databases.putIfAbsent(name, new Database(name));
            persistDatabase(name);
            walLog(session, WalManager.OP_CREATE_DB, name, null, "create database", 0, null);
            return QueryResult.ofAffected(0);
        }
        if (ts.eatKw("TABLESPACE")) {
            requirePriv(session, "*", "*", Priv.CREATE);
            String name = ts.expect(TokenType.IDENT, "表空间名").text;
            long size = TablespaceManager.EXTENT_SIZE;
            boolean autoExtend = true;
            if (ts.eatKw("SIZE")) {
                size = Long.parseLong(ts.expect(TokenType.NUMBER, "初始大小").text);
            }
            if (ts.eatKw("AUTOEXTEND")) {
                ts.eatKw("=");
                if (ts.eatKw("OFF")) {
                    autoExtend = false;
                } else {
                    ts.eatKw("ON");
                }
            }
            tspaces.create(name, size, autoExtend);
            walLog(session, WalManager.OP_CREATE_TABLESPACE, null, name,
                    "create tablespace size=" + size + " autoextend=" + (autoExtend ? "ON" : "OFF"), 0, null);
            return QueryResult.ofAffected(0);
        }
        if (ts.eatKw("TABLE")) {
            boolean ifNotExists = ts.eatKw("IF") && ts.eatKw("NOT") && ts.eatKw("EXISTS");
            // 表名允许带库前缀（DataGrip 生成的 DDL 常写 `db`.`t`，stripBackticks 后成为 db . t）
            String first = ts.expect(TokenType.IDENT, "表名").text;
            String tname = first;
            Database db;
            if (ts.peek().type == TokenType.DOT) {
                ts.next();
                tname = ts.expect(TokenType.IDENT, "表名").text;
                db = databases.get(first);
                if (db == null) {
                    throw new EngineException(ErrorCode.UNKNOWN_DATABASE, "库不存在: " + first);
                }
            } else {
                db = requireDb(session);
            }
            requirePriv(session, db.name, "*", Priv.CREATE);
            if (db.tables.containsKey(tname)) {
                if (ifNotExists) {
                    return QueryResult.ofAffected(0);
                }
                throw new EngineException(ErrorCode.SYNTAX_ERROR, "表已存在: " + tname);
            }
            Table t = new Table(tname);
            ts.expect(TokenType.LPAREN, "(");
            while (true) {
                // 表级约束（可能夹在列定义之间）：PRIMARY KEY / UNIQUE / FOREIGN KEY / CHECK / CONSTRAINT <名>
                if (parseIfTableConstraint(t, ts)) {
                    if (ts.eat(TokenType.COMMA)) {
                        continue;
                    }
                    break;
                }
                // 列定义
                String cname = ts.expect(TokenType.IDENT, "列名").text;
                String tnameRaw = ts.expect(TokenType.IDENT, "类型").text.toUpperCase();
                if (ts.peek().type == TokenType.LPAREN) {
                    ts.next();
                    if (ts.peek().type == TokenType.NUMBER) {
                        ts.next();
                        if (ts.eat(TokenType.COMMA)) {
                            ts.expect(TokenType.NUMBER, "精度");
                        }
                    }
                    ts.expect(TokenType.RPAREN, ")");
                }
                ColumnType ct = ColumnType.fromName(tnameRaw);
                boolean pk = false;
                boolean nullable = true;
                boolean autoInc = false;
                boolean uniq = false;
                Object def = null;
                String comment = "";
                while (true) {
                    if (ts.eatKw("PRIMARY")) {
                        ts.expectKw("KEY");
                        pk = true;
                        nullable = false;
                    } else if (ts.eatKw("NOT")) {
                        ts.expectKw("NULL");
                        nullable = false;
                    } else if (ts.eatKw("NULL")) {
                        nullable = true;
                    } else if (ts.eatKw("DEFAULT")) {
                        def = parseLiteral(ts.next());
                    } else if (ts.eatKw("UNIQUE")) {
                        uniq = true;
                        nullable = false;
                    } else if (ts.eatKw("AUTO_INCREMENT")) {
                        autoInc = true;
                    } else if (ts.eatKw("CHECK")) {
                        ts.expect(TokenType.LPAREN, "(");
                        t.tableChecks.add(parsePred(ts));
                        ts.expect(TokenType.RPAREN, ")");
                    } else if (ts.eatKw("COMMENT")) {
                        comment = tokenText(ts.next());
                    } else if (!parseCommonColumnModifier(ts)) {
                        break;
                    }
                }
                t.cols.add(new ColumnDef(cname, ct, nullable, pk, def, autoInc, uniq, comment));
                if (ts.eat(TokenType.COMMA)) {
                    continue;
                }
                break;
            }
            ts.expect(TokenType.RPAREN, ")");
            // 表选项：TABLESPACE 〈名〉（本项目扩展）与 MySQL 标准项（ENGINE / CHARSET / COLLATE / COMMENT / ...）。
            // DataGrip「修改表」对话框生成的 DDL 会带上这些子句，解析不了就会把合法语句误判成 1064。
            String[] tsBox = {TablespaceManager.DEFAULT_TS};
            parseTableOptions(ts, t, tsBox);
            t.tablespace = tsBox[0];
            db.tables.put(tname, t);
            if (tspaces != null) {
                tspaces.assign(db.name + "." + tname, tsBox[0]);
            }
            persistTable(db.name, t);
            walLog(session, WalManager.OP_CREATE_TABLE, db.name, tname, "create table", 0, tablePayload(t));
            return QueryResult.ofAffected(0);
        }
        if (ts.eatKw("VIEW")) {
            Database db = requireDb(session);
            requirePriv(session, db.name, "*", Priv.CREATE);
            String vname = ts.expect(TokenType.IDENT, "视图名").text;
            createView(session, ts, db, vname);
            return QueryResult.ofAffected(0);
        }
        if (ts.eatKw("INDEX") || ts.eatKw("UNIQUE")) {
            Database db = requireDb(session);
            String iname = ts.expect(TokenType.IDENT, "索引名").text;
            createIndex(session, ts, db, iname);
            return QueryResult.ofAffected(0);
        }
        throw new EngineException(ErrorCode.SYNTAX_ERROR, "CREATE 仅支持 DATABASE / TABLE / VIEW / INDEX / USER");
    }

    /** 解析表级约束（夹在列定义之间的 PRIMARY KEY / UNIQUE / FOREIGN KEY / CHECK / CONSTRAINT 〈名〉）。
     *  命中返回 true（已消费），未命中返回 false（调用方继续当列定义处理）。 */
    private boolean parseIfTableConstraint(Table t, Ts ts) throws EngineException {
        if (ts.eatKw("CONSTRAINT")) {
            if (ts.peek().type == TokenType.IDENT && !isClauseKeyword(ts.peek().text)) {
                ts.next(); // 约束名（本引擎忽略，仅参与解析）
            }
        }
        if (ts.eatKw("PRIMARY")) {
            ts.expectKw("KEY");
            for (String c : parseColumnRefList(ts)) {
                int ci = t.indexOf(c);
                if (ci >= 0) {
                    t.cols.get(ci).pk = true;
                    t.cols.get(ci).nullable = false;
                }
            }
            return true;
        }
        if (ts.eatKw("UNIQUE")) {
            t.uniqueKeys.add(parseColumnRefList(ts));
            return true;
        }
        if (ts.eatKw("FOREIGN")) {
            parseForeignClause(t, ts);
            return true;
        }
        if (ts.eatKw("CHECK")) {
            ts.expect(TokenType.LPAREN, "(");
            t.tableChecks.add(parsePred(ts));
            ts.expect(TokenType.RPAREN, ")");
            return true;
        }
        return false;
    }

    private List<String> parseColumnRefList(Ts ts) throws EngineException {
        List<String> cols = new ArrayList<>();
        ts.expect(TokenType.LPAREN, "(");
        while (true) {
            cols.add(ts.expect(TokenType.IDENT, "列名").text);
            if (ts.eat(TokenType.COMMA)) {
                continue;
            }
            break;
        }
        ts.expect(TokenType.RPAREN, ")");
        return cols;
    }

    private QueryResult doDrop(SessionContext session, Ts ts) throws EngineException {
        ts.expectKw("DROP");
        if (ts.eatKw("DATABASE") || ts.eatKw("SCHEMA")) {
            requirePriv(session, "*", "*", Priv.DROP);
            boolean ifExists = ts.eatKw("IF") && ts.eatKw("EXISTS");
            String name = ts.expect(TokenType.IDENT, "库名").text;
            Database removed = databases.remove(name);
            if (removed == null) {
                if (ifExists) {
                    return QueryResult.ofAffected(0);
                }
                throw new EngineException(ErrorCode.UNKNOWN_DATABASE, "库不存在: " + name);
            }
            if (tspaces != null) {
                for (String tn : removed.tables.keySet()) {
                    tspaces.release(name + "." + tn);   // 归还该库所有表占用的表空间
                }
            }
            deleteDatabase(name);
            walLog(session, WalManager.OP_DROP_DB, name, null, "drop database", 0, null);
            return QueryResult.ofAffected(0);
        }
        if (ts.eatKw("TABLESPACE")) {
            requirePriv(session, "*", "*", Priv.DROP);
            String name = ts.expect(TokenType.IDENT, "表空间名").text;
            tspaces.drop(name);
            walLog(session, WalManager.OP_DROP_TABLESPACE, null, name, "drop tablespace", 0, null);
            return QueryResult.ofAffected(0);
        }
        if (ts.eatKw("TABLE")) {
            Database db = requireDb(session);
            requirePriv(session, db.name, "*", Priv.DROP);
            boolean ifExists = ts.eatKw("IF") && ts.eatKw("EXISTS");
            String tname = ts.expect(TokenType.IDENT, "表名").text;
            if (db.tables.remove(tname) == null) {
                if (ifExists) {
                    return QueryResult.ofAffected(0);
                }
                throw new EngineException(ErrorCode.UNKNOWN_TABLE, "表不存在: " + tname);
            }
            deleteTable(db.name, tname);
            walLog(session, WalManager.OP_DROP_TABLE, db.name, tname, "drop table", 0, null);
            return QueryResult.ofAffected(0);
        }
        if (ts.eatKw("VIEW")) {
            Database db = requireDb(session);
            requirePriv(session, db.name, "*", Priv.DROP);
            String vname = ts.expect(TokenType.IDENT, "视图名").text;
            dropView(session, ts, db, vname);
            return QueryResult.ofAffected(0);
        }
        if (ts.eatKw("INDEX")) {
            Database db = requireDb(session);
            requirePriv(session, db.name, "*", Priv.DROP);
            dropIndex(session, ts, db);
            return QueryResult.ofAffected(0);
        }
        throw new EngineException(ErrorCode.SYNTAX_ERROR, "DROP 仅支持 DATABASE / TABLE / VIEW / INDEX");
    }

    private QueryResult doInsert(SessionContext session, Ts ts) throws EngineException {
        ts.expectKw("INSERT");
        ts.expectKw("INTO");
        String[] dt = parseQualifiedTable(session, ts);
        String dbName = dt[0];
        Table t = requireTable(dbName, dt[1]);
        requirePriv(session, dbName, t.name, Priv.INSERT);
        List<String> colNames = null;
        if (ts.eat(TokenType.LPAREN)) {
            colNames = new ArrayList<>();
            while (true) {
                colNames.add(ts.expect(TokenType.IDENT, "列名").text);
                if (ts.eat(TokenType.COMMA)) {
                    continue;
                }
                break;
            }
            ts.expect(TokenType.RPAREN, ")");
        }
        ts.eatKw("VALUES");
        // 并发控制：INSERT 会改动行数组结构（追加/复用空位），需要表级排他锁（X），
        // 持到事务结束才释放 —— 否则两个事务同时插同一张表会互相踩踏。
        lockTable(session, dbName, t, LockManager.Mode.X);
        int inserted = 0;
        boolean[] wasDeleted = new boolean[1];   // 承接 allocSlot 回传的"改前是否为删除标记空位"
        while (true) {
            ts.expect(TokenType.LPAREN, "(");
            List<Token> vals = new ArrayList<>();
            while (true) {
                vals.add(ts.next());
                if (ts.eat(TokenType.COMMA)) {
                    continue;
                }
                break;
            }
            ts.expect(TokenType.RPAREN, ")");
            Object[] newRow = buildRow(t, colNames, vals);
            // 记录随机读写策略之"插入"：先找文件里的空位（被删除标记留下的位置优先复用），
            // 找到就住进去（槽号不变），找不到才追加到末尾。
            int slot = allocSlot(t, wasDeleted);
            // undo 日志：插入前该槽是空位（before==null），回滚时把它重新清空即可
            recordUndo(session, dbName, t, UndoOp.INSERT, slot, null, wasDeleted[0], -1);
            t.rows.set(slot, newRow);
            t.dirtySlots.add(slot);
            inserted++;
            // 行级 redo 日志：记录插入的完整行，崩溃后可按主键（无主键则整行）幂等补回
            walLog(session, WalManager.OP_INSERT, dbName, t.name, "insert row", 1,
                    WalManager.rowPayload("row", newRow));
            if (ts.eat(TokenType.COMMA)) {
                continue;
            }
            break;
        }
        touch(session, dbName, t);
        return QueryResult.ofAffected(inserted);
    }

    // ===================== SELECT =====================

    private QueryResult doSelect(SessionContext session, Ts ts) throws EngineException {
        ts.expectKw("SELECT");
        SelectPlan p = parseSelectPlan(ts);
        if (hasTopLevelUnion(ts)) {
            return doUnion(session, ts, p);
        }
        return execPlan(session, p);
    }

    /** 解析一条 SELECT。parseTail=false 时不消费尾随的 ORDER BY / LIMIT（UNION 各分支用，
     *  尾随排序/分页属于整个 UNION，由 doUnion 在合并后统一处理）。 */
    private SelectPlan parseSelectPlan(Ts ts) throws EngineException {
        return parseSelectPlan(ts, true);
    }

    private SelectPlan parseSelectPlan(Ts ts, boolean parseTail) throws EngineException {
        SelectPlan p = new SelectPlan();
        p.distinct = ts.eatKw("DISTINCT");
        p.items = parseSelectList(ts);
        p.from = ts.eatKw("FROM") ? parseFrom(ts) : null; // FROM 可选（MySQL `SELECT 1` 双省略写法）
        p.where = ts.eatKw("WHERE") ? parseWhere(ts) : null;
        if (ts.eatKw("GROUP")) {
            ts.expectKw("BY");
            while (true) {
                p.groupCols.add(readColRef(ts));
                if (ts.eat(TokenType.COMMA)) {
                    continue;
                }
                break;
            }
        }
        p.having = ts.eatKw("HAVING") ? parseWhere(ts) : null;
        if (parseTail) {
            // ORDER BY（解析阶段消费并保存，execPlan 阶段应用；支持按列名或位置序号）
            if (ts.eatKw("ORDER")) {
                ts.expectKw("BY");
                p.orderRefs = new ArrayList<>();
                p.orderAsc = new ArrayList<>();
                while (true) {
                    p.orderRefs.add(readColRef(ts));
                    boolean asc = true;
                    if (ts.eatKw("DESC")) {
                        asc = false;
                    } else {
                        ts.eatKw("ASC");
                    }
                    p.orderAsc.add(asc);
                    if (ts.eat(TokenType.COMMA)) {
                        continue;
                    }
                    break;
                }
            }
            // LIMIT（MySQL 三种写法：LIMIT 行数 | LIMIT 偏移,行数 | LIMIT 行数 OFFSET 偏移）
            if (ts.eatKw("LIMIT")) {
                int first = Integer.parseInt(ts.expect(TokenType.NUMBER, "行数").text);
                int offset = 0;
                int limit = first;
                if (ts.eat(TokenType.COMMA)) {
                    offset = first;
                    limit = Integer.parseInt(ts.expect(TokenType.NUMBER, "行数").text);
                } else if (ts.eatKw("OFFSET")) {
                    offset = Integer.parseInt(ts.expect(TokenType.NUMBER, "偏移量").text);
                }
                p.hasLimit = true;
                p.limitOffset = Math.max(0, offset);
                p.limitCount = limit;
            }
            // 显式加锁读：FOR UPDATE 加排他锁、LOCK IN SHARE MODE 加共享锁。
            // 二者都是"当前读"——绕过快照直接读最新版本，这正是为随后的 UPDATE 占位所必需的。
            if (ts.eatKw("FOR")) {
                ts.expectKw("UPDATE");
                p.forUpdate = true;
            } else if (ts.eatKw("LOCK")) {
                ts.expectKw("IN");
                ts.expectKw("SHARE");
                ts.expectKw("MODE");
                p.lockInShare = true;
            }
        }
        return p;
    }

    /**
     * 执行一条 SELECT：外壳负责按隔离级别建立（或复用）读视图，内层做真正的执行。
     * <p>子查询会嵌套调用本方法，所以视图必须用 try/finally 保存与恢复 ——
     * 否则内层退出后会把外层的快照带着一起改掉。</p>
     */
    private QueryResult execPlan(SessionContext session, SelectPlan p) throws EngineException {
        ReadView saved = currentReadView;
        currentReadView = readViewFor(session);
        try {
            return execPlanInner(session, p);
        } finally {
            currentReadView = saved;
        }
    }

    private QueryResult execPlanInner(SessionContext session, SelectPlan p) throws EngineException {
        currentSession = session;
        // 访问控制：SELECT 也需对应表的 SELECT 权限（root 在 requirePriv 内直接放行）。
        // 同时按隔离级别/显式要求决定读锁：
        //   FOR UPDATE → 表级 X；LOCK IN SHARE MODE → 表级 S；SERIALIZABLE 的普通读 → 表级 S。
        LockManager.Mode readLock = null;
        if (p.forUpdate) {
            readLock = LockManager.Mode.X;
        } else if (p.lockInShare) {
            readLock = LockManager.Mode.S;
        } else if (session != null && session.getIsolation() == SessionContext.Isolation.SERIALIZABLE
                && session.isInTransaction()) {
            readLock = LockManager.Mode.S;
        }
        for (TableFactor tf : collectTableFactors(p.from)) {
            int dot = tf.name.indexOf('.');
            String db = dot < 0 ? requireDb(session).name : tf.name.substring(0, dot);
            String tbl = dot < 0 ? tf.name : tf.name.substring(dot + 1);
            requirePriv(session, db, tbl, Priv.SELECT);
            Table t = databases.get(db) != null ? databases.get(db).tables.get(tbl) : null;
            if (t == null) {
                continue;
            }
            if (readLock != null) {
                lockTable(session, db, t, readLock);
            } else {
                // 快照读本来不加任何锁（这样读才不会阻塞写）。
                // 但如果有别的会话用 LOCK TABLES 显式独占了该表，读也必须排队 —— 这是 MySQL 的语义。
                LockManager.Mode held = lockMgr.grantedMode(db + "." + t.name);
                if (held == LockManager.Mode.X || held == LockManager.Mode.S) {
                    lockTable(session, db, t, LockManager.Mode.IS);  // IS 与 S 兼容、被 X 挡住
                }
            }
        }
        // 加锁读是"当前读"：不看快照，直接读最新版本（否则锁住的是旧版本，没有意义）
        if (p.forUpdate || p.lockInShare) {
            currentReadView = null;
        }

        Combined combined = buildCombined(session, p.from);
        List<Object[]> rows;
        // 走基于代价的优化器；优化阶段任何异常都回退到原有执行路径，保证语义不变
        OptimizedQuery oq = null;
        try {
            oq = optimize(combined, p.from, p.where);
        } catch (Exception ignore) {
            oq = null;
        }
        if (oq != null && oq.plan != null) {
            rows = materialize(combined, oq.plan);
            if (!oq.residual.isEmpty()) {
                rows = filterRows(combined, rows, andAll(oq.residual));
            }
        } else if (p.from instanceof TableFactor && p.where != null) {
            rows = filterTableRows(lastTables.get(0), combined, p.where);
        } else {
            rows = evaluateFrom(combined, p.from);
            if (p.where != null) {
                rows = filterRows(combined, rows, p.where);
            }
        }

        if (p.orderRefs != null) {
            rows = orderByRefs(combined, rows, p.orderRefs, p.orderAsc);
        }
        if (p.hasLimit) {
            int offset = p.limitOffset;
            int limit = p.limitCount;
            int fromIdx = Math.min(offset, rows.size());
            int toIdx = limit < 0 ? rows.size() : Math.min(fromIdx + limit, rows.size());
            rows = new ArrayList<>(rows.subList(fromIdx, toIdx));
        }

        boolean hasAgg = p.items.stream()
                .anyMatch(it -> it.kind != SelectKind.STAR && it.kind != SelectKind.COL
                        && it.kind != SelectKind.SUBQ && it.kind != SelectKind.LITERAL);
        List<List<Object>> outRows;
        if (hasAgg || !p.groupCols.isEmpty()) {
            outRows = aggregate(combined, rows, p.items, p.groupCols);
            if (p.having != null) {
                outRows = filterAggregate(combined, p.items, outRows, p.having);
            }
        } else {
            outRows = new ArrayList<>();
            for (Object[] r : rows) {
                outRows.add(projectRow(combined, r, p.items));
            }
            if (p.distinct) {
                outRows = dedupe(outRows);
            }
        }
        return buildResult(combined, p.items, outRows);
    }

    /** 顶层 UNION / UNION ALL：拆分各分支、分别执行后按列合并（UNION 去重，UNION ALL 不去重）。 */
    private QueryResult doUnion(SessionContext session, Ts ts, SelectPlan first) throws EngineException {
        List<QueryResult> branches = new ArrayList<>();
        List<Boolean> allFlags = new ArrayList<>();
        branches.add(execPlan(session, first));
        allFlags.add(false); // 首分支前无 ALL
        while (hasTopLevelUnion(ts)) {
            ts.eatKw("UNION");
            boolean all = ts.eatKw("ALL");
            ts.expectKw("SELECT"); // 各 UNION 分支以 SELECT 开头；parseSelectPlan 不消费该关键字
            // 分支不消费尾随 ORDER BY / LIMIT：它们属于整个 UNION，合并后由下方统一处理。
            SelectPlan p = parseSelectPlan(ts, false);
            branches.add(execPlan(session, p));
            allFlags.add(all);
        }
        QueryResult firstR = branches.get(0);
        int ncol = firstR.columns().size();
        List<List<Object>> merged = new ArrayList<>();
        for (int b = 0; b < branches.size(); b++) {
            QueryResult br = branches.get(b);
            if (br.columns().size() != ncol) {
                throw syn("UNION 各分支列数不一致: 期望 " + ncol + " 实际 " + br.columns().size());
            }
            boolean all = allFlags.get(b);
            for (List<Object> r : br.rows()) {
                if (!all && containsRow(merged, r)) {
                    continue;
                }
                merged.add(r);
            }
        }
        List<ColumnMeta> cols = firstR.columns();
        // 全局 ORDER BY / LIMIT（UNION 末尾可选）
        if (ts.eatKw("ORDER")) {
            ts.expectKw("BY");
            List<String> refs = new ArrayList<>();
            List<Boolean> asc = new ArrayList<>();
            while (true) {
                refs.add(readColRef(ts));
                boolean a = true;
                if (ts.eatKw("DESC")) {
                    a = false;
                } else {
                    ts.eatKw("ASC");
                }
                asc.add(a);
                if (ts.eat(TokenType.COMMA)) {
                    continue;
                }
                break;
            }
            merged = orderRowsByLabels(cols, merged, refs, asc);
        }
        if (ts.eatKw("LIMIT")) {
            int limFirst = Integer.parseInt(ts.expect(TokenType.NUMBER, "行数").text);
            int offset = 0;
            int limit = limFirst;
            if (ts.eat(TokenType.COMMA)) {
                offset = limFirst;
                limit = Integer.parseInt(ts.expect(TokenType.NUMBER, "行数").text);
            } else if (ts.eatKw("OFFSET")) {
                offset = Integer.parseInt(ts.expect(TokenType.NUMBER, "偏移量").text);
            }
            int fromIdx = Math.min(Math.max(0, offset), merged.size());
            int toIdx = limit < 0 ? merged.size() : Math.min(fromIdx + limit, merged.size());
            merged = new ArrayList<>(merged.subList(fromIdx, toIdx));
        }
        return QueryResult.ofResultSet(cols, merged);
    }

    private static boolean containsRow(List<List<Object>> rows, List<Object> target) {
        for (List<Object> r : rows) {
            if (r.size() == target.size()) {
                boolean same = true;
                for (int i = 0; i < r.size(); i++) {
                    if (!eq(r.get(i), target.get(i))) {
                        same = false;
                        break;
                    }
                }
                if (same) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 扫描 token 流，判断是否还存在顶层（不在任何括号内）的 UNION 关键字。 */
    private boolean hasTopLevelUnion(Ts ts) {
        int depth = 0;
        int k = 0;
        while (true) {
            Token tk = ts.peekAt(k);
            if (tk.type == TokenType.EOF) {
                break;
            }
            if (tk.type == TokenType.LPAREN) {
                depth++;
            } else if (tk.type == TokenType.RPAREN) {
                depth--;
            } else if (depth == 0 && tk.type == TokenType.IDENT
                    && tk.text.equalsIgnoreCase("UNION")) {
                return true;
            }
            k++;
        }
        return false;
    }

    /** 解析一个被括号包裹的子查询：`( SELECT ... )`，返回解析计划（含其内 ORDER BY/LIMIT）。 */
    private SelectPlan parseSubqueryAfterParen(Ts ts) throws EngineException {
        ts.expect(TokenType.LPAREN, "(");
        ts.expectKw("SELECT");
        SelectPlan plan = parseSelectPlan(ts);
        ts.expect(TokenType.RPAREN, ")");
        return plan;
    }

    private QueryResult doShow(SessionContext session, Ts ts) throws EngineException {
        ts.expectKw("SHOW");
        // 并发观测：SHOW PROCESSLIST / SHOW ENGINE INNODB STATUS
        if (ts.eatKw("PROCESSLIST")) {
            return showProcessList();
        }
        if (ts.eatKw("ENGINE")) {
            ts.expectKw("INNODB");   // SHOW ENGINE INNODB STATUS
            ts.expectKw("STATUS");
            return showInnodbStatus(session);
        }
        if (ts.eatKw("DATABASES") || ts.eatKw("SCHEMAS")) {
            List<ColumnMeta> cols = List.of(new ColumnMeta("Database", ColumnType.VARCHAR, false, false));
            List<List<Object>> rows = new ArrayList<>();
            for (String db : databases.keySet()) {
                rows.add(List.of(db));
            }
            return QueryResult.ofResultSet(cols, rows);
        }
        if (ts.eatKw("TABLES")) {
            Database db = requireDb(session);
            List<ColumnMeta> cols = List.of(new ColumnMeta("Table", ColumnType.VARCHAR, false, false));
            List<List<Object>> rows = new ArrayList<>();
            for (String t : db.tables.keySet()) {
                rows.add(List.of(t));
            }
            return QueryResult.ofResultSet(cols, rows);
        }
        if (ts.eatKw("TABLESPACES")) {
            List<ColumnMeta> cols = List.of(
                    new ColumnMeta("Name", ColumnType.VARCHAR, false, false),
                    new ColumnMeta("Directory", ColumnType.VARCHAR, false, false),
                    new ColumnMeta("Files", ColumnType.BIGINT, false, false),
                    new ColumnMeta("Extent_size", ColumnType.BIGINT, false, false),
                    new ColumnMeta("Allocated", ColumnType.BIGINT, false, false),
                    new ColumnMeta("Used", ColumnType.BIGINT, false, false),
                    new ColumnMeta("Used_pct", ColumnType.DOUBLE, false, false),
                    new ColumnMeta("Autoextend", ColumnType.VARCHAR, false, false),
                    new ColumnMeta("Extends", ColumnType.BIGINT, false, false));
            return QueryResult.ofResultSet(cols, tspaces.rows());
        }
        if (ts.eatKw("TABLESPACE")) {
            String name = ts.expect(TokenType.IDENT, "表空间名").text;
            List<ColumnMeta> cols = List.of(
                    new ColumnMeta("Database", ColumnType.VARCHAR, false, false),
                    new ColumnMeta("Table", ColumnType.VARCHAR, false, false),
                    new ColumnMeta("Datafile", ColumnType.VARCHAR, false, false),
                    new ColumnMeta("Used", ColumnType.BIGINT, false, false),
                    new ColumnMeta("Allocated", ColumnType.BIGINT, false, false),
                    new ColumnMeta("Extents", ColumnType.BIGINT, false, false),
                    new ColumnMeta("Pages", ColumnType.BIGINT, false, false));
            return QueryResult.ofResultSet(cols, tspaces.fileRows(name));
        }
        if (ts.eatKw("TABLE") && ts.eatKw("FILE")) {
            return showTableFile(session, ts);
        }
        if (ts.eatKw("FILE") && ts.eatKw("MAP")) {
            return showFileMap(session, ts);
        }
        if (ts.eatKw("WAL")) {
            ts.eatKw("STATUS");
            ts.eatKw("LOGS");
            return walStatus();
        }
        if (ts.eatKw("LOGS")) {
            int n = 20;
            if (ts.peek().type == TokenType.NUMBER) {
                n = Integer.parseInt(ts.next().text);
            }
            return walLogs(n);
        }
        throw new EngineException(ErrorCode.SYNTAX_ERROR,
                "SHOW 仅支持 DATABASES / TABLES / TABLESPACES / TABLESPACE <名> / TABLE FILE <名> "
                        + "/ FILE MAP <名> / LOGS [n] / WAL STATUS");
    }

    // ===================== 附加功能1：三段式记录文件的内部视图 =====================

    /** 解析 〈表名〉 或 〈库名.表名〉，返回 {库名, 表名}。 */
    private String[] resolveTableName(SessionContext session, Ts ts) throws EngineException {
        String raw = ts.expect(TokenType.IDENT, "表名").text;
        int dot = raw.indexOf('.');
        String dbName = dot < 0 ? requireDb(session).name : raw.substring(0, dot);
        return new String[]{dbName, dot < 0 ? raw : raw.substring(dot + 1)};
    }

    /**
     * {@code SHOW TABLE FILE 〈表名〉}：展示三段式记录文件的<b>格式信息区</b>（段1），
     * 也就是这张表在磁盘上的物理布局参数。
     */
    private QueryResult showTableFile(SessionContext session, Ts ts) throws EngineException {
        String[] n = resolveTableName(session, ts);
        Table t = requireTable(n[0], n[1]);
        String dbName = n[0];
        RecordFileStore.Header h = t.fileHeader;
        Path f = tspaces == null ? null : tspaces.fileOfIfExists(dbName, t.name);
        long disk = 0;
        try {
            disk = f != null && Files.exists(f) ? Files.size(f) : 0;
        } catch (IOException ignore) {
            // 取文件大小失败不影响展示
        }
        List<ColumnMeta> cols = List.of(
                new ColumnMeta("Item", ColumnType.VARCHAR, false, false),
                new ColumnMeta("Value", ColumnType.VARCHAR, false, false));
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("table", dbName + "." + t.name));
        rows.add(List.of("tablespace", tsOf(dbName, t.name)));
        rows.add(List.of("datafile", f == null ? "(未创建)" : f.toString()));
        rows.add(List.of("magic", h == null ? "-" : h.magic));
        rows.add(List.of("--- 段1 格式信息区 ---", ""));
        rows.add(List.of("header_size", h == null ? "-" : String.valueOf(h.headerSize)));
        rows.add(List.of("--- 段2 记录空间分配表 ---", ""));
        rows.add(List.of("bitmap_offset", h == null ? "-" : String.valueOf(h.bitmapOffset)));
        rows.add(List.of("bitmap_size", h == null ? "-" : String.valueOf(h.bitmapSize)));
        rows.add(List.of("--- 段3 记录存储空间 ---", ""));
        rows.add(List.of("data_offset", h == null ? "-" : String.valueOf(h.dataOffset)));
        rows.add(List.of("data_size", h == null ? "-" : String.valueOf(h.dataSize)));
        rows.add(List.of("--- 记录位置 ---", ""));
        rows.add(List.of("slot_size", h == null ? "-" : String.valueOf(h.slotSize)));
        rows.add(List.of("slot_count", h == null ? "-" : String.valueOf(h.slotCount)));
        rows.add(List.of("record_count", h == null ? "-" : String.valueOf(h.recordCount)));
        rows.add(List.of("first_record", h == null ? "-" : String.valueOf(h.firstRecord)));
        rows.add(List.of("last_record", h == null ? "-" : String.valueOf(h.lastRecord)));
        rows.add(List.of("free_slots", h == null ? "-" : String.valueOf(h.freeCount)));
        rows.add(List.of("--- 文件 ---", ""));
        rows.add(List.of("file_size_logical", h == null ? "-" : String.valueOf(h.fileSize)));
        rows.add(List.of("file_size_on_disk", String.valueOf(disk)));
        rows.add(List.of("rows_in_memory", String.valueOf(t.liveRows())));
        rows.add(List.of("deleted_slots", String.valueOf(t.deletedSlots.size())));
        return QueryResult.ofResultSet(cols, rows);
    }

    /**
     * {@code SHOW FILE MAP 〈表名〉 [n]}：展示<b>记录空间分配表</b>（段2）与每个槽在文件里的内容。
     * 这里读的是磁盘上的真实字节 —— 所以能直接看到"删除只改了标记，数据区的内容还原封不动地躺着"。
     */
    private QueryResult showFileMap(SessionContext session, Ts ts) throws EngineException {
        String[] n = resolveTableName(session, ts);
        Table t = requireTable(n[0], n[1]);
        String dbName = n[0];
        int limit = 24;
        if (ts.peek().type == TokenType.NUMBER) {
            limit = Integer.parseInt(ts.next().text);
        }
        Path f = tspaces == null ? null : tspaces.fileOfIfExists(dbName, t.name);
        RecordFileStore.Header h = t.fileHeader;
        char[] flags = null;
        if (f != null && h != null) {
            try {
                flags = RecordFileStore.readBitmap(f, h);
            } catch (IOException ignore) {
                flags = null;
            }
        }
        List<ColumnMeta> cols = List.of(
                new ColumnMeta("Slot", ColumnType.INT, false, false),
                new ColumnMeta("State", ColumnType.VARCHAR, false, false),
                new ColumnMeta("Content_in_file", ColumnType.VARCHAR, false, false));
        List<List<Object>> rows = new ArrayList<>();
        int total = h == null ? t.rows.size() : h.slotCount;
        int shown = 0;
        for (int i = 0; i < total && shown < Math.max(1, limit); i++) {
            char fl = flags != null && i < flags.length ? flags[i] : slotFlag(t, i);
            if (fl == RecordFileStore.SLOT_EMPTY) {
                continue;                 // 没用过的槽不占篇幅，只展示有内容的和被删除的
            }
            String content = "";
            if (f != null && h != null) {
                try {
                    content = RecordFileStore.readSlotText(f, h, i);
                } catch (IOException ignore) {
                    content = "(读取失败)";
                }
            }
            String state = switch (fl) {
                case RecordFileStore.SLOT_DELETED -> "DELETED(可复用空位)";
                case RecordFileStore.SLOT_USED -> "USED";
                default -> "EMPTY";
            };
            rows.add(Arrays.asList(i, state, content.length() > 60 ? content.substring(0, 60) : content));
            shown++;
        }
        return QueryResult.ofResultSet(cols, rows);
    }

    /**
     * {@code OPTIMIZE TABLE 〈表名〉}：把"只做删除标记"留下的空位<b>真正回收</b>。
     * 有效记录被紧凑地搬到前面的槽，文件随之重写 —— 这正是"删除标记"方案的代价：
     * 平时删得快，但空间不会自己还回来，需要显式整理（和 MySQL 的 OPTIMIZE TABLE 一个道理）。
     */
    private QueryResult doOptimize(SessionContext session, Ts ts) throws EngineException {
        ts.expectKw("OPTIMIZE");
        ts.expectKw("TABLE");
        String raw = ts.expect(TokenType.IDENT, "表名").text;
        int dot = raw.indexOf('.');
        String dbName = dot < 0 ? requireDb(session).name : raw.substring(0, dot);
        Table t = requireTable(dbName, dot < 0 ? raw : raw.substring(dot + 1));
        requirePriv(session, dbName, t.name, Priv.CREATE);
        List<Object[]> compact = new ArrayList<>();
        for (Object[] r : t.rows) {
            if (r != null) {
                compact.add(r);
            }
        }
        int freed = t.rows.size() - compact.size();
        t.rows.clear();
        t.rows.addAll(compact);
        t.deletedSlots.clear();
        t.dirtySlots.clear();
        t.slotCount = Math.max(RecordFileStore.DEFAULT_SLOTS, compact.size());
        t.slotFileReady = false;      // 槽位布局变了，整文件重写一次
        rebuildIndexes(t);            // 行下标变了，索引必须重建
        persistTable(dbName, t);
        walLog(session, WalManager.OP_OPTIMIZE_TABLE, dbName, t.name,
                "optimize table: keep " + compact.size() + " rows, free " + freed + " slots", freed, null);
        return QueryResult.ofAffected(freed);
    }

    // ===================== 日志与表空间的查询命令 =====================

    /** SHOW WAL STATUS：日志系统运行状况。 */
    private QueryResult walStatus() throws EngineException {
        List<ColumnMeta> cols = List.of(
                new ColumnMeta("Item", ColumnType.VARCHAR, false, false),
                new ColumnMeta("Value", ColumnType.VARCHAR, false, false));
        List<List<Object>> rows = new ArrayList<>();
        long pending = wal.readSinceCheckpoint().size();
        rows.add(List.of("log_file", wal.walPath().toString()));
        rows.add(List.of("current_lsn", String.valueOf(wal.currentLsn())));
        rows.add(List.of("checkpoint_lsn", String.valueOf(wal.checkpointLsn())));
        rows.add(List.of("records_in_log", String.valueOf(wal.readAll().size())));
        rows.add(List.of("records_to_redo", String.valueOf(pending)));
        rows.add(List.of("log_size", TablespaceManager.human(wal.sizeBytes())));
        rows.add(List.of("archived_logs", String.valueOf(wal.archiveCount())));
        return QueryResult.ofResultSet(cols, rows);
    }

    /** SHOW LOGS [n]：查看最近 n 条日志记录（默认 20 条）。 */
    private QueryResult walLogs(int n) throws EngineException {
        List<ColumnMeta> cols = List.of(
                new ColumnMeta("LSN", ColumnType.BIGINT, false, false),
                new ColumnMeta("Time", ColumnType.VARCHAR, false, false),
                new ColumnMeta("User", ColumnType.VARCHAR, false, false),
                new ColumnMeta("Op", ColumnType.VARCHAR, false, false),
                new ColumnMeta("Object", ColumnType.VARCHAR, false, false),
                new ColumnMeta("Rows", ColumnType.INT, false, false),
                new ColumnMeta("Detail", ColumnType.VARCHAR, false, false));
        List<WalManager.WalRecord> all = wal.readAll();
        int from = Math.max(0, all.size() - Math.max(1, n));
        List<List<Object>> rows = new ArrayList<>();
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("MM-dd HH:mm:ss");
        for (int i = from; i < all.size(); i++) {
            WalManager.WalRecord r = all.get(i);
            String obj;
            if (r.db != null && r.table != null) {
                obj = r.db + "." + r.table;
            } else {
                obj = r.db != null ? r.db : (r.table != null ? r.table : "");
            }
            rows.add(Arrays.asList(r.lsn, fmt.format(new java.util.Date(r.ts)),
                    r.user == null ? "" : r.user, r.op, obj, r.rows,
                    r.detail == null ? "" : r.detail));
        }
        return QueryResult.ofResultSet(cols, rows);
    }

    /** CHECKPOINT：把内存中的脏数据全部落盘，并在日志里打检查点（同时归档旧日志）。 */
    private QueryResult doCheckpoint(SessionContext session) throws EngineException {
        persistAll();
        wal.checkpoint();
        return QueryResult.ofAffected(0);
    }

    /** ALTER TABLESPACE 〈名〉 EXTEND [区数]：手动给表空间扩容。 */
    private QueryResult doAlter(SessionContext session, Ts ts) throws EngineException {
        ts.expectKw("ALTER");
        if (ts.eatKw("TABLESPACE")) {
            requirePriv(session, "*", "*", Priv.CREATE);
            String name = ts.expect(TokenType.IDENT, "表空间名").text;
            ts.expectKw("EXTEND");
            long extents = 1;
            if (ts.peek().type == TokenType.NUMBER) {
                extents = Long.parseLong(ts.next().text);
            }
            long added = tspaces.extend(name, null, extents);
            walLog(session, WalManager.OP_EXTEND_TABLESPACE, null, name,
                    "extend " + extents + " extent(s), +" + added + " bytes", 0, null);
            return QueryResult.ofAffected(0);
        }
        if (ts.eatKw("TABLE")) {
            // 表名允许带库前缀：ALTER TABLE `db`.`t` ...（stripBackticks 后成为 db . t）
            String[] dt = parseQualifiedTable(session, ts);
            Database db = databases.get(dt[0]);
            if (db == null) {
                throw new EngineException(ErrorCode.UNKNOWN_DATABASE, "库不存在: " + dt[0]);
            }
            requirePriv(session, db.name, "*", Priv.CREATE);
            String tname = dt[1];
            Table t = requireTable(db, tname);
            // DataGrip「修改表」提交时生成的是【一条多动作】语句（动作以逗号分隔），
            // 例如 `ALTER TABLE t COMMENT='x', MODIFY COLUMN c int COMMENT 'y'`。
            // 因此这里按动作循环解析，而不是只认第一个关键字。
            while (true) {
                alterTableAction(session, db, t, tname, ts);
                if (!ts.eat(TokenType.COMMA)) {
                    break;
                }
            }
            persistTable(db.name, t);
            return QueryResult.ofAffected(0);
        }
        throw new EngineException(ErrorCode.SYNTAX_ERROR,
                "ALTER 仅支持 TABLESPACE <名> EXTEND [区数] / TABLE ...");
    }

    /** 执行 ALTER TABLE 的一个动作（可由逗号连接成多个）。 */
    private void alterTableAction(SessionContext session, Database db, Table t, String tname, Ts ts)
            throws EngineException {
        // ---- 表注释：ALTER TABLE t COMMENT '文本' / COMMENT='文本'（DataGrip 加表注释走这条）----
        if (ts.eatKw("COMMENT")) {
            ts.eat(TokenType.OP);                   // 可选的 '='
            t.comment = tokenText(ts.next());
            return;
        }
        // ---- 其它表选项（ENGINE / CHARSET / COLLATE / AUTO_INCREMENT / TABLESPACE ...）：语法放行 ----
        String[] tsBox = {tsOf(db.name, t.name)};
        if (parseOneTableOption(ts, t, tsBox)) {
            t.tablespace = tsBox[0];
            return;
        }
        // ---- ADD ----
        if (ts.eatKw("ADD")) {
            if (ts.eatKw("COLUMN")) {
                alterAddColumn(t, ts);
                return;
            }
            if (ts.eatKw("INDEX") || ts.eatKw("KEY")) {
                alterAddIndex(t, ts);
                return;
            }
            if (ts.eatKw("UNIQUE")) {
                ts.eatKw("INDEX");
                ts.eatKw("KEY");
                alterAddIndex(t, ts);
                return;
            }
            if (ts.eatKw("PRIMARY")) {
                ts.expectKw("KEY");
                alterAddPrimaryKey(t, ts);
                return;
            }
            if (ts.eatKw("CONSTRAINT")) {
                if (ts.peek().type == TokenType.IDENT && !isClauseKeyword(ts.peek().text)) {
                    ts.next();                      // 约束名（引擎不保存约束名）
                }
                if (ts.eatKw("FOREIGN")) {
                    parseForeignClause(t, ts);
                    return;
                }
                if (ts.eatKw("UNIQUE")) {
                    ts.eatKw("INDEX");
                    ts.eatKw("KEY");
                    alterAddIndex(t, ts);
                    return;
                }
                if (ts.eatKw("PRIMARY")) {
                    ts.expectKw("KEY");
                    alterAddPrimaryKey(t, ts);
                    return;
                }
                if (ts.eatKw("CHECK")) {
                    skipParens(ts);
                    return;
                }
                throw new EngineException(ErrorCode.SYNTAX_ERROR,
                        "ALTER TABLE ADD CONSTRAINT 仅支持 PRIMARY KEY / UNIQUE / FOREIGN KEY / CHECK");
            }
            if (ts.eatKw("FOREIGN")) {
                parseForeignClause(t, ts);
                return;
            }
            if (ts.eatKw("CHECK")) {
                skipParens(ts);
                return;
            }
            // 省略 COLUMN 关键字：ADD <列名> <类型>
            alterAddColumn(t, ts);
            return;
        }
        // ---- DROP ----
        if (ts.eatKw("DROP")) {
            if (ts.eatKw("COLUMN")) {
                alterDropColumn(t, ts);
                return;
            }
            if (ts.eatKw("INDEX") || ts.eatKw("KEY")) {
                alterDropIndex(t, ts);
                return;
            }
            if (ts.eatKw("PRIMARY")) {
                ts.expectKw("KEY");
                for (ColumnDef c : t.cols) {
                    c.pk = false;
                }
                return;
            }
            if (ts.eatKw("FOREIGN")) {
                ts.expectKw("KEY");
                alterDropForeignKey(t, tsExpectIdent(ts, "外键名"));
                return;
            }
            if (ts.eatKw("CONSTRAINT")) {
                alterDropForeignKey(t, tsExpectIdent(ts, "约束名"));
                return;
            }
            if (ts.eatKw("CHECK")) {
                if (ts.peek().type == TokenType.IDENT) {
                    ts.next();
                }
                return;
            }
            alterDropColumn(t, ts);
            return;
        }
        // ---- MODIFY [COLUMN]：重新定义列（DataGrip 改列注释/类型走这条）----
        if (ts.eatKw("MODIFY")) {
            ts.eatKw("COLUMN");
            String cname = tsExpectIdent(ts, "列名");
            int ci = t.indexOf(cname);
            if (ci < 0) {
                throw new EngineException(ErrorCode.UNKNOWN_COLUMN, "列不存在: " + cname);
            }
            ColumnDef cd = t.cols.get(ci);
            applyColumnTypeDef(cd, ts);
            coerceColumn(t, ci, cd);
            resyncIndexes(t);
            return;
        }
        // ---- CHANGE [COLUMN]：改名 + 重新定义 ----
        if (ts.eatKw("CHANGE")) {
            ts.eatKw("COLUMN");
            String oldC = tsExpectIdent(ts, "旧列名");
            String newC = tsExpectIdent(ts, "新列名");
            int ci = t.indexOf(oldC);
            if (ci < 0) {
                throw new EngineException(ErrorCode.UNKNOWN_COLUMN, "列不存在: " + oldC);
            }
            ColumnDef cd = t.cols.get(ci);
            applyColumnTypeDef(cd, ts);
            cd.name = newC;
            coerceColumn(t, ci, cd);
            for (Map.Entry<String, String> e : t.indexNames.entrySet()) {
                if (e.getValue().equalsIgnoreCase(oldC)) {
                    e.setValue(newC);
                }
            }
            resyncIndexes(t);
            return;
        }
        // ---- ALTER [COLUMN]：SET / DROP DEFAULT / SET|DROP NOT NULL ----
        if (ts.eatKw("ALTER")) {
            ts.eatKw("COLUMN");
            String cname = tsExpectIdent(ts, "列名");
            int ci = t.indexOf(cname);
            if (ci < 0) {
                throw new EngineException(ErrorCode.UNKNOWN_COLUMN, "列不存在: " + cname);
            }
            ColumnDef cd = t.cols.get(ci);
            if (ts.eatKw("SET")) {
                if (ts.eatKw("DEFAULT")) {
                    cd.def = parseLiteral(ts.next());
                } else if (ts.eatKw("NOT")) {
                    ts.expectKw("NULL");
                    cd.nullable = false;
                } else if (ts.eatKw("VISIBLE") || ts.eatKw("INVISIBLE")) {
                    // 索引可见性：无对应语义，语法放行
                } else {
                    throw new EngineException(ErrorCode.SYNTAX_ERROR,
                            "ALTER COLUMN ... SET 仅支持 DEFAULT / NOT NULL");
                }
            } else if (ts.eatKw("DROP")) {
                if (ts.eatKw("DEFAULT")) {
                    cd.def = null;
                } else if (ts.eatKw("NOT")) {
                    ts.expectKw("NULL");
                    cd.nullable = true;
                } else {
                    throw new EngineException(ErrorCode.SYNTAX_ERROR,
                            "ALTER COLUMN ... DROP 仅支持 DEFAULT / NOT NULL");
                }
            } else {
                throw new EngineException(ErrorCode.SYNTAX_ERROR, "ALTER COLUMN 仅支持 SET / DROP");
            }
            return;
        }
        // ---- RENAME ----
        if (ts.eatKw("RENAME")) {
            if (ts.eatKw("COLUMN")) {
                String oldC = tsExpectIdent(ts, "旧列名");
                ts.expectKw("TO");
                String newC = tsExpectIdent(ts, "新列名");
                int ci = t.indexOf(oldC);
                if (ci < 0) {
                    throw new EngineException(ErrorCode.UNKNOWN_COLUMN, "列不存在: " + oldC);
                }
                t.cols.get(ci).name = newC;
                for (Map.Entry<String, String> e : t.indexNames.entrySet()) {
                    if (e.getValue().equalsIgnoreCase(oldC)) {
                        e.setValue(newC);
                    }
                }
                resyncIndexes(t);
                return;
            }
            if (ts.eatKw("INDEX") || ts.eatKw("KEY")) {
                String oldI = tsExpectIdent(ts, "旧索引名");
                ts.expectKw("TO");
                String newI = tsExpectIdent(ts, "新索引名");
                String col = t.indexNames.remove(oldI);
                if (col == null) {
                    throw new EngineException(ErrorCode.UNKNOWN_COLUMN, "索引不存在: " + oldI);
                }
                t.indexNames.put(newI, col);
                return;
            }
            if (ts.eatKw("TO") || ts.eatKw("AS")) {
                String newName = tsExpectIdent(ts, "新表名");
                Database db2 = requireDb(session);
                db2.tables.remove(tname);
                t.name = newName;
                db2.tables.put(newName, t);
                try {
                    Files.deleteIfExists(dataRoot.resolve(db2.name).resolve(tname + ".json"));
                } catch (IOException ignored) {
                    // 旧 JSON 删除失败不致命
                }
                if (tspaces != null) {
                    tspaces.release(db2.name + "." + tname);
                    tspaces.assign(db2.name + "." + newName, t.tablespace);
                }
                return;
            }
            throw new EngineException(ErrorCode.SYNTAX_ERROR,
                    "RENAME 仅支持 TO <新表名> / COLUMN <旧> TO <新> / INDEX <旧> TO <新>");
        }
        throw new EngineException(ErrorCode.SYNTAX_ERROR,
                "ALTER TABLE 仅支持 ADD [COLUMN] / DROP [COLUMN] / MODIFY [COLUMN] / CHANGE [COLUMN] / "
                        + "ALTER COLUMN / RENAME / COMMENT 等子句");
    }

    /** 解析列定义中的类型与修饰符（ALTER 的 ADD / MODIFY / CHANGE 与 CREATE 共用），就地写回 {@code cd}。
     *  与 MySQL 一致：MODIFY 里没写 COMMENT 就等于清空注释（覆盖式语义）。 */
    private void applyColumnTypeDef(ColumnDef cd, Ts ts) throws EngineException {
        if (ts.eatKw("IF") && ts.eatKw("NOT") && ts.eatKw("EXISTS")) {
            return;                                  // ADD COLUMN IF NOT EXISTS（调用方已处理，这里兜底）
        }
        String tnameRaw = ts.expect(TokenType.IDENT, "类型").text.toUpperCase();
        if (ts.peek().type == TokenType.LPAREN) {
            ts.next();
            if (ts.peek().type == TokenType.NUMBER) {
                ts.next();
                if (ts.eat(TokenType.COMMA)) {
                    ts.expect(TokenType.NUMBER, "精度");
                }
            }
            ts.expect(TokenType.RPAREN, ")");
        }
        cd.type = ColumnType.fromName(tnameRaw);
        while (true) {
            if (ts.eatKw("NOT")) {
                ts.expectKw("NULL");
                cd.nullable = false;
            } else if (ts.eatKw("NULL")) {
                cd.nullable = true;
            } else if (ts.eatKw("DEFAULT")) {
                cd.def = parseLiteral(ts.next());
            } else if (ts.eatKw("COMMENT")) {
                cd.comment = tokenText(ts.next());
            } else if (ts.eatKw("AUTO_INCREMENT")) {
                cd.autoIncrement = true;
            } else if (ts.eatKw("UNIQUE")) {
                ts.eatKw("KEY");
                cd.unique = true;
            } else if (ts.eatKw("PRIMARY")) {
                ts.expectKw("KEY");
                cd.pk = true;
                cd.nullable = false;
            } else if (ts.eatKw("FIRST")) {
                // 位置子句：引擎按列序存放，忽略列位置要求
            } else if (ts.eatKw("AFTER")) {
                if (ts.hasNext()) {
                    ts.next();
                }
            } else if (!parseCommonColumnModifier(ts)) {
                break;
            }
        }
    }

    /** ALTER TABLE t ADD [COLUMN] [IF NOT EXISTS] <列> <定义> [FIRST|AFTER 列]。 */
    private void alterAddColumn(Table t, Ts ts) throws EngineException {
        if (ts.eatKw("IF") && ts.eatKw("NOT") && ts.eatKw("EXISTS")) {
            // 已消费
        }
        String cname = tsExpectIdent(ts, "列名");
        if (t.indexOf(cname) >= 0) {
            throw new EngineException(ErrorCode.SYNTAX_ERROR, "列已存在: " + cname);
        }
        ColumnDef cd = new ColumnDef(cname, ColumnType.VARCHAR, true, false, null);
        applyColumnTypeDef(cd, ts);
        t.cols.add(cd);
        Object fill = cd.nullable ? null : (cd.def != null ? cd.def : defaultValueFor(cd.type));
        for (int slot = 0; slot < t.rows.size(); slot++) {
            Object[] r = t.rows.get(slot);
            if (r == null) {
                continue;
            }
            Object[] nr = new Object[t.cols.size()];
            System.arraycopy(r, 0, nr, 0, r.length);
            nr[t.cols.size() - 1] = fill;
            t.rows.set(slot, nr);
        }
        t.indexDirty = true;
    }

    /** ALTER TABLE t DROP [COLUMN] [IF EXISTS] <列>；该列上的索引随之删除（MySQL 行为）。 */
    private void alterDropColumn(Table t, Ts ts) throws EngineException {
        boolean ifExists = ts.eatKw("IF") && ts.eatKw("EXISTS");
        String cname = tsExpectIdent(ts, "列名");
        int ci = t.indexOf(cname);
        if (ci < 0) {
            if (ifExists) {
                return;
            }
            throw new EngineException(ErrorCode.UNKNOWN_COLUMN, "列不存在: " + cname);
        }
        String dropped = t.cols.get(ci).name;
        if (t.cols.get(ci).pk) {
            throw new EngineException(ErrorCode.SYNTAX_ERROR, "不能删除主键列");
        }
        List<String> idxOnCol = new ArrayList<>();
        for (Map.Entry<String, String> e : t.indexNames.entrySet()) {
            if (e.getValue().equalsIgnoreCase(dropped)) {
                idxOnCol.add(e.getKey());
            }
        }
        for (String in : idxOnCol) {
            t.indexNames.remove(in);
        }
        t.cols.remove(ci);
        for (int slot = 0; slot < t.rows.size(); slot++) {
            Object[] r = t.rows.get(slot);
            if (r == null) {
                continue;
            }
            Object[] nr = new Object[t.cols.size()];
            for (int k = 0, kk = 0; k < r.length; k++) {
                if (k == ci) {
                    continue;
                }
                nr[kk++] = r[k];
            }
            t.rows.set(slot, nr);
        }
        t.indexDirty = true;
        resyncIndexes(t);                            // 行下标/列下标都变了，索引必须重建
    }

    /** ALTER TABLE t ADD [UNIQUE] [INDEX|KEY] <名> (列)；省略名字时自动生成 idx_<表>_<列>。 */
    private void alterAddIndex(Table t, Ts ts) throws EngineException {
        String iname = null;
        if (ts.peek().type == TokenType.IDENT && !ts.peek().text.equalsIgnoreCase("USING")) {
            iname = ts.next().text;
        }
        if (ts.eatKw("USING")) {
            if (ts.hasNext()) {
                ts.next();
            }
        }
        ts.expect(TokenType.LPAREN, "(");
        String col = ts.expect(TokenType.IDENT, "列名").text;
        if (ts.eat(TokenType.LPAREN)) {              // 前缀长度 col(10)
            if (ts.peek().type == TokenType.NUMBER) {
                ts.next();
            }
            ts.expect(TokenType.RPAREN, ")");
        }
        ts.expect(TokenType.RPAREN, ")");
        if (t.indexOf(col) < 0) {
            throw new EngineException(ErrorCode.UNKNOWN_COLUMN, "列不存在: " + col);
        }
        if (iname == null) {
            iname = "idx_" + t.name + "_" + col;
        }
        t.indexNames.put(iname, col);
        resyncIndexes(t);
    }

    /** ALTER TABLE t DROP [INDEX|KEY] <名>。 */
    private void alterDropIndex(Table t, Ts ts) throws EngineException {
        String iname = tsExpectIdent(ts, "索引名");
        if (t.indexNames.remove(iname) == null) {
            throw new EngineException(ErrorCode.UNKNOWN_COLUMN, "索引不存在: " + iname);
        }
        resyncIndexes(t);
    }

    private void alterAddPrimaryKey(Table t, Ts ts) throws EngineException {
        for (String c : parseColumnRefList(ts)) {
            int ci = t.indexOf(c);
            if (ci < 0) {
                throw new EngineException(ErrorCode.UNKNOWN_COLUMN, "列不存在: " + c);
            }
            t.cols.get(ci).pk = true;
            t.cols.get(ci).nullable = false;
        }
    }

    /** DROP FOREIGN KEY / DROP CONSTRAINT：本引擎不保存约束名，按"列名"或"fk_<列名>"匹配。 */
    private void alterDropForeignKey(Table t, String name) {
        String bare = name.startsWith("fk_") ? name.substring(3) : name;
        t.foreignKeys.removeIf(fk -> fk.col.equalsIgnoreCase(name) || fk.col.equalsIgnoreCase(bare));
    }

    /** 列结构变化后同步索引：indexCols 按 indexNames 重算（顺带清理已失效索引），并重建全部索引。 */
    private void resyncIndexes(Table t) {
        t.indexCols.clear();
        t.indexMaps.clear();
        List<String> dead = new ArrayList<>();
        for (Map.Entry<String, String> e : t.indexNames.entrySet()) {
            if (t.indexOf(e.getValue()) < 0) {
                dead.add(e.getKey());
                continue;
            }
            t.indexCols.add(e.getValue());
        }
        for (String d : dead) {
            t.indexNames.remove(d);
        }
        rebuildIndexes(t);
    }

    /** MODIFY / CHANGE 改变了列类型后，把该列已有值 coerce 回新类型（如 varchar→int）。 */
    private void coerceColumn(Table t, int ci, ColumnDef cd) {
        for (int slot = 0; slot < t.rows.size(); slot++) {
            Object[] r = t.rows.get(slot);
            if (r == null || ci >= r.length) {
                continue;
            }
            r[ci] = coerce(cd, r[ci]);
        }
    }

    /** 解析 [CONSTRAINT [名]] FOREIGN KEY (子列) REFERENCES 父表 [(父列)]：CREATE 与 ALTER 共用。 */
    private void parseForeignClause(Table t, Ts ts) throws EngineException {
        ts.expectKw("KEY");
        List<String> child = parseColumnRefList(ts);
        ts.expectKw("REFERENCES");
        String rt = ts.expect(TokenType.IDENT, "父表").text;
        List<String> parent = parseColumnRefList(ts);
        for (int i = 0; i < child.size(); i++) {
            String pc = parent.isEmpty() ? null : (i < parent.size() ? parent.get(i) : parent.get(0));
            if (pc != null) {
                t.foreignKeys.add(new FkDef(child.get(i), rt, pc));
            }
        }
    }

    private String tsExpectIdent(Ts ts, String what) throws EngineException {
        return ts.expect(TokenType.IDENT, what).text;
    }

    /** 取 token 文本（STRING / IDENT / NUMBER 均为 text；其它类型返回空串）。用于 COMMENT 后跟的字面量。 */
    private static String tokenText(Token tk) {
        return tk == null ? "" : tk.text;
    }

    /** 跳过一个配对的括号块（调用时当前 token 应为 '('）。 */
    private void skipParens(Ts ts) throws EngineException {
        if (ts.peek().type != TokenType.LPAREN) {
            return;
        }
        ts.next();
        int depth = 1;
        while (depth > 0 && ts.hasNext()) {
            Token x = ts.next();
            if (x.type == TokenType.LPAREN) {
                depth++;
            } else if (x.type == TokenType.RPAREN) {
                depth--;
            }
        }
    }

    /** 解析列定义里"非类型相关"的常见 MySQL 修饰符（字符集/排序规则/生成列/ON UPDATE 等）。
     *  命中并消费返回 true；未命中返回 false（调用方结束列定义解析）。
     *  COMMENT 需要写回列定义，故由调用方单独处理。 */
    private boolean parseCommonColumnModifier(Ts ts) throws EngineException {
        if (ts.eatKw("BINARY") || ts.eatKw("UNSIGNED") || ts.eatKw("ZEROFILL") || ts.eatKw("SIGNED")
                || ts.eatKw("ASCII") || ts.eatKw("UNICODE") || ts.eatKw("INVISIBLE")) {
            return true;
        }
        if (ts.eatKw("CHARACTER")) {
            ts.eatKw("SET");
            if (ts.hasNext()) {
                ts.next();
            }
            return true;
        }
        if (ts.eatKw("CHARSET")) {
            if (ts.hasNext()) {
                ts.next();
            }
            return true;
        }
        if (ts.eatKw("COLLATE")) {
            if (ts.hasNext()) {
                ts.next();
            }
            return true;
        }
        if (ts.eatKw("ON")) {
            // ON UPDATE CURRENT_TIMESTAMP / ON UPDATE CURRENT_TIMESTAMP(6)
            ts.eatKw("UPDATE");
            if (ts.hasNext()) {
                ts.next();
            }
            if (ts.peek().type == TokenType.LPAREN) {
                skipParens(ts);
            }
            return true;
        }
        if (ts.eatKw("GENERATED")) {
            // GENERATED ALWAYS AS (表达式) [VIRTUAL|STORED]；引擎不支持生成列，语法上放行以免误报 1064
            ts.eatKw("ALWAYS");
            ts.eatKw("AS");
            skipParens(ts);
            ts.eatKw("VIRTUAL");
            ts.eatKw("STORED");
            return true;
        }
        if (ts.eatKw("VIRTUAL") || ts.eatKw("STORED")) {
            return true;
        }
        if (ts.eatKw("REFERENCES")) {
            // 列内联外键：父表 [(列)] [ON DELETE/UPDATE ...]
            if (ts.hasNext()) {
                ts.next();
            }
            if (ts.peek().type == TokenType.LPAREN) {
                skipParens(ts);
            }
            while (ts.eatKw("ON")) {
                ts.eatKw("DELETE");
                ts.eatKw("UPDATE");
                if (ts.hasNext()) {
                    ts.next();
                }
                if (ts.eatKw("SET")) {
                    ts.eatKw("NULL");
                    ts.eatKw("DEFAULT");
                }
                ts.eatKw("NO");
                ts.eatKw("ACTION");
                ts.eatKw("CASCADE");
                ts.eatKw("RESTRICT");
            }
            return true;
        }
        return false;
    }

    /** 解析表级选项（CREATE TABLE ... ) <options> / ALTER TABLE ... <option>）。
     *  COMMENT 会写回 {@code t.comment}；TABLESPACE 写回 box[0]；其余（ENGINE/CHARSET/...）语法放行、语义忽略。 */
    private void parseTableOptions(Ts ts, Table t, String[] tsBox) throws EngineException {
        while (true) {
            if (!parseOneTableOption(ts, t, tsBox)) {
                return;
            }
        }
    }

    private boolean parseOneTableOption(Ts ts, Table t, String[] tsBox) throws EngineException {
        if (ts.eatKw("TABLESPACE")) {
            String nm = ts.expect(TokenType.IDENT, "表空间名").text;
            if (tspaces != null && !tspaces.exists(nm)) {
                throw new EngineException(ErrorCode.SERVER_ERROR, "表空间不存在: " + nm);
            }
            tsBox[0] = nm;
            return true;
        }
        if (ts.eatKw("COMMENT")) {
            ts.eat(TokenType.OP);          // 可选的 '='
            t.comment = tokenText(ts.next());
            return true;
        }
        if (ts.eatKw("DEFAULT")) {
            // DEFAULT CHARSET=xx / DEFAULT CHARACTER SET xx / DEFAULT COLLATE=xx
            ts.eatKw("CHARSET");
            ts.eatKw("CHARACTER");
            ts.eatKw("SET");
            ts.eatKw("COLLATE");
            ts.eat(TokenType.OP);          // 可选的 '='
            if (ts.hasNext()) {
                ts.next();
            }
            return true;
        }
        if (ts.eatKw("CHARACTER")) {
            ts.eatKw("SET");
            ts.eat(TokenType.OP);
            if (ts.hasNext()) {
                ts.next();
            }
            return true;
        }
        if (ts.eatKw("ENGINE") || ts.eatKw("ROW_FORMAT") || ts.eatKw("COLLATE") || ts.eatKw("CHARSET")
                || ts.eatKw("AUTO_INCREMENT") || ts.eatKw("AVG_ROW_LENGTH") || ts.eatKw("CHECKSUM")
                || ts.eatKw("DELAY_KEY_WRITE") || ts.eatKw("MAX_ROWS") || ts.eatKw("MIN_ROWS")
                || ts.eatKw("PACK_KEYS") || ts.eatKw("STATS_PERSISTENT") || ts.eatKw("KEY_BLOCK_SIZE")
                || ts.eatKw("COMPRESSION") || ts.eatKw("ENCRYPTION") || ts.eatKw("CONNECTION")
                || ts.eatKw("DATA") || ts.eatKw("INDEX") || ts.eatKw("INSERT_METHOD")
                || ts.eatKw("PASSWORD") || ts.eatKw("STORAGE") || ts.eatKw("STATS_AUTO_RECALC")) {
            ts.eat(TokenType.OP);          // 可选的 '='
            if (ts.eatKw("DEFAULT")) {
                if (ts.hasNext()) {
                    ts.next();
                }
            } else if (ts.hasNext()) {
                ts.next();
            }
            return true;
        }
        return false;
    }

    /** ADD COLUMN 时给 NOT NULL 无默认值列补的类型零值。 */
    private static Object defaultValueFor(ColumnType ct) {
        return switch (ct) {
            case INT -> 0;
            case BIGINT -> 0L;
            case DOUBLE -> 0.0;
            case DECIMAL -> "0";
            case BOOLEAN -> false;
            default -> "";
        };
    }

    private QueryResult doTruncate(SessionContext session, Ts ts) throws EngineException {
        ts.expectKw("TRUNCATE");
        ts.eatKw("TABLE");
        String rawName = ts.expect(TokenType.IDENT, "表名").text;
        int dot = rawName.indexOf('.');
        String dbName = dot < 0 ? requireDb(session).name : rawName.substring(0, dot);
        String tname = dot < 0 ? rawName : rawName.substring(dot + 1);
        Table t = requireTable(dbName, tname);
        requirePriv(session, dbName, t.name, Priv.DELETE);
        // TRUNCATE 在 undo 语义上等价于"删除所有行"：逐行记改前影像，另加一条记录还原自增计数器
        long autoIncBefore = t.autoIncValue;
        for (int i = 0; i < t.rows.size(); i++) {
            if (t.rows.get(i) != null) {
                recordUndo(session, dbName, t, UndoOp.DELETE, i, t.rows.get(i), false, -1);
                t.rows.set(i, null);
                t.deletedSlots.put(i, true);
                t.dirtySlots.add(i);
            }
        }
        recordUndo(session, dbName, t, UndoOp.TRUNCATE, -1, null, false, autoIncBefore);
        t.autoIncValue = 0;
        touch(session, dbName, t);
        return QueryResult.ofAffected(0);
    }

    private QueryResult doDesc(SessionContext session, Ts ts) throws EngineException {
        ts.eatKw("DESCRIBE");
        ts.eatKw("DESC");
        Table t = requireTableQualified(session, ts.expect(TokenType.IDENT, "表名").text);
        List<ColumnMeta> cols = List.of(
                new ColumnMeta("Field", ColumnType.VARCHAR, false, false),
                new ColumnMeta("Type", ColumnType.VARCHAR, false, false),
                new ColumnMeta("Null", ColumnType.VARCHAR, false, false),
                new ColumnMeta("Key", ColumnType.VARCHAR, false, false));
        List<List<Object>> rows = new ArrayList<>();
        for (ColumnDef c : t.cols) {
            rows.add(List.of(c.name, c.type.name(), c.nullable ? "YES" : "NO", c.pk ? "PRI" : ""));
        }
        return QueryResult.ofResultSet(cols, rows);
    }

    // ===================== SELECT 解析辅助 =====================

    enum SelectKind { STAR, COL, COUNT, SUM, AVG, MIN, MAX, SUBQ, LITERAL }

    static final class SelectItem {
        final SelectKind kind;
        final String ref;
        final String alias;
        /** 标量子查询（SELECT 列表中的 `(SELECT ...)`）；仅 kind==SUBQ 时非空。 */
        final SelectPlan subq;
        /** 裸字面量（SELECT 列表中的 `1` / `'x'` / TRUE / NULL）；仅 kind==LITERAL 时非空。 */
        final Object literal;

        SelectItem(SelectKind kind, String ref, String alias) {
            this(kind, ref, alias, null, null);
        }

        SelectItem(SelectKind kind, String ref, String alias, SelectPlan subq) {
            this(kind, ref, alias, subq, null);
        }

        SelectItem(SelectKind kind, String ref, String alias, Object literal) {
            this(kind, ref, alias, null, literal);
        }

        private SelectItem(SelectKind kind, String ref, String alias, SelectPlan subq, Object literal) {
            this.kind = kind;
            this.ref = ref;
            this.alias = alias;
            this.subq = subq;
            this.literal = literal;
        }
    }

    /** 一条 SELECT 的解析结果（不含执行）。UNION 各分支与子查询均复用。 */
    static final class SelectPlan {
        boolean distinct;
        List<SelectItem> items;
        FromClause from;
        Pred where;
        List<String> groupCols = new ArrayList<>();
        Pred having;
        // 分支内可选的 ORDER BY / LIMIT（解析阶段消费并保存，execPlan 阶段应用）
        List<String> orderRefs;
        List<Boolean> orderAsc;
        boolean hasLimit;
        int limitOffset;
        int limitCount;
        /** {@code SELECT ... FOR UPDATE}：读到的行要加排他锁（当前读，为随后的 UPDATE 占位）。 */
        boolean forUpdate;
        /** {@code SELECT ... LOCK IN SHARE MODE}：读到的行加共享锁（别人可读不可写）。 */
        boolean lockInShare;
    }

    // ===================== FROM 子句树（支持显式 JOIN ... ON / LEFT JOIN）=====================

    /** FROM 子句的抽象语法树节点。 */
    static abstract class FromClause {
        int colOffset;   // 本子树在组合行中的列起始偏移
        int colWidth;    // 本子树占用的列数
    }

    /** 表因子：一张具体的基表（或视图），带可选别名。 */
    static final class TableFactor extends FromClause {
        final String name;
        final String alias;

        TableFactor(String name, String alias) {
            this.name = name;
            this.alias = alias;
        }
    }

    /** 派生表因子：`FROM (SELECT ...) alias`，子查询作为一个虚拟表参与连接。 */
    static final class SubqueryFactor extends FromClause {
        final SelectPlan plan;
        final String alias;

        SubqueryFactor(SelectPlan plan, String alias) {
            this.plan = plan;
            this.alias = alias;
        }
    }

    /** 连接节点：左/右子树 + 连接类型 + ON 条件（逗号隐式连接即 inner 且 on 为空）。 */
    static final class JoinClause extends FromClause {
        FromClause left;
        FromClause right;
        final boolean leftJoin;   // true = LEFT [OUTER] JOIN；false = INNER / 逗号连接
        Pred on;                  // ON 谓词（可能为 null，表示逗号隐式连接）

        JoinClause(FromClause left, FromClause right, boolean leftJoin, Pred on) {
            this.left = left;
            this.right = right;
            this.leftJoin = leftJoin;
            this.on = on;
        }
    }

    // ===================== 谓词表达式树（WHERE / HAVING / ON）=====================

    /** 替代原来的线性列表：支持 AND/OR/NOT 组合与 LIKE/IN/BETWEEN/IS NULL。 */
    static final class Pred {
        enum Kind { AND, OR, NOT, CMP, LIKE, IN, BETWEEN, ISNULL, EXISTS }
        Kind kind;
        Operand left;
        String op;            // CMP 比较符：=,<>,<,<=,>,>=
        Operand right;        // CMP 右操作数
        String pattern;       // LIKE 模式串
        List<Object> values;  // IN 值列表
        SelectPlan inSubquery; // IN (SELECT ...) 子查询（非空时本谓词为 IN 子查询）
        Operand lo, hi;       // BETWEEN 上下界
        boolean negated;      // LIKE/NOT LIKE、IN/NOT IN、BETWEEN/NOT BETWEEN、ISNULL/IS NOT NULL、EXISTS/NOT EXISTS
        Pred left2, right2;   // AND / OR 子节点
        Pred child;           // NOT 子节点
        SelectPlan subquery;  // EXISTS (SELECT ...) 子查询（非空时 kind==EXISTS）

        static Pred and(Pred a, Pred b) {
            if (a == null) return b;
            if (b == null) return a;
            Pred p = new Pred(); p.kind = Kind.AND; p.left2 = a; p.right2 = b; return p;
        }

        static Pred or(Pred a, Pred b) {
            if (a == null) return b;
            if (b == null) return a;
            Pred p = new Pred(); p.kind = Kind.OR; p.left2 = a; p.right2 = b; return p;
        }

        static Pred cmp(Operand l, String o, Operand r) {
            Pred p = new Pred(); p.kind = Kind.CMP; p.left = l; p.op = o; p.right = r; return p;
        }

        static Pred like(Operand l, String pat, boolean neg) {
            Pred p = new Pred(); p.kind = Kind.LIKE; p.left = l; p.pattern = pat; p.negated = neg; return p;
        }

        static Pred inList(Operand l, List<Object> vals, boolean neg) {
            Pred p = new Pred(); p.kind = Kind.IN; p.left = l; p.values = vals; p.negated = neg; return p;
        }

        static Pred inSubquery(Operand l, SelectPlan plan, boolean neg) {
            Pred p = new Pred(); p.kind = Kind.IN; p.left = l; p.inSubquery = plan; p.negated = neg; return p;
        }

        static Pred between(Operand l, Operand lo, Operand hi, boolean neg) {
            Pred p = new Pred(); p.kind = Kind.BETWEEN; p.left = l; p.lo = lo; p.hi = hi; p.negated = neg; return p;
        }

        static Pred isNull(Operand l, boolean neg) {
            Pred p = new Pred(); p.kind = Kind.ISNULL; p.left = l; p.negated = neg; return p;
        }

        static Pred exists(SelectPlan plan, boolean neg) {
            Pred p = new Pred(); p.kind = Kind.EXISTS; p.subquery = plan; p.negated = neg; return p;
        }

        static Pred not(Pred child) {
            Pred p = new Pred(); p.kind = Kind.NOT; p.child = child; return p;
        }
    }

    static final class Operand {
        final String colRef;
        final Object literal;
        /** 标量子查询（操作数位置的 `(SELECT ...)`）；非空时 colRef/literal 均为 null。 */
        final SelectPlan subquery;

        Operand(String colRef, Object literal) {
            this(colRef, literal, null);
        }

        Operand(String colRef, Object literal, SelectPlan subquery) {
            this.colRef = colRef;
            this.literal = literal;
            this.subquery = subquery;
        }

        static Operand col(String ref) {
            return new Operand(ref, null);
        }

        static Operand lit(Object v) {
            return new Operand(null, v);
        }

        static Operand subq(SelectPlan plan) {
            return new Operand(null, null, plan);
        }
    }

    static final class Combined {
        final List<ColumnDef> outCols = new ArrayList<>();
        final List<int[]> src = new ArrayList<>();

        int resolve(String ref) throws EngineException {
            if (ref.contains(".")) {
                int dot = ref.lastIndexOf('.');
                String prefix = ref.substring(0, dot);
                String col = ref.substring(dot + 1);
                // 1) 精确匹配 alias.col 形式（buildCombined 命名规则）
                for (int k = 0; k < outCols.size(); k++) {
                    if (outCols.get(k).name.equals(ref)) {
                        return k;
                    }
                }
                // 2) 表限定匹配：前缀(别名/表名，可含库前缀) + 列名配对
                int found = -1;
                for (int k = 0; k < outCols.size(); k++) {
                    String n = outCols.get(k).name;
                    int nd = n.lastIndexOf('.');
                    if (nd < 0) {
                        // 裸列名退化：单表场景下按列名直接命中
                        if (n.equals(col)) {
                            if (found >= 0) {
                                throw new EngineException(ErrorCode.UNKNOWN_COLUMN, "列引用歧义: " + ref);
                            }
                            found = k;
                        }
                        continue;
                    }
                    String np = n.substring(0, nd), nc = n.substring(nd + 1);
                    if (nc.equals(col) && (np.equals(prefix) || np.endsWith("." + prefix))) {
                        if (found >= 0) {
                            throw new EngineException(ErrorCode.UNKNOWN_COLUMN, "列引用歧义: " + ref);
                        }
                        found = k;
                    }
                }
                if (found >= 0) {
                    return found;
                }
                // 3) 多段前缀兜底（如 DataGrip 旧元数据缓存生成的 `t.`t.point`` → ref "t.t.point"）：
                //    丢弃最外层段后按 `前缀.列名` 重试
                if (ref.indexOf('.') != ref.lastIndexOf('.')) {
                    int d2 = ref.lastIndexOf('.', ref.lastIndexOf('.') - 1);
                    return resolve(ref.substring(d2 + 1));
                }
                throw unknownCol(ref);
            }
            int found = -1;
            for (int k = 0; k < outCols.size(); k++) {
                String bare = bare(outCols.get(k).name);
                if (bare.equals(ref)) {
                    if (found >= 0) {
                        throw new EngineException(ErrorCode.UNKNOWN_COLUMN, "列引用歧义: " + ref);
                    }
                    found = k;
                }
            }
            if (found < 0) {
                throw unknownCol(ref);
            }
            return found;
        }

        private EngineException unknownCol(String ref) {
            StringBuilder b = new StringBuilder("未知列: ").append(ref).append(" 候选[");
            for (int k = 0; k < outCols.size(); k++) {
                b.append(outCols.get(k).name).append(",");
            }
            b.append("]");
            return new EngineException(ErrorCode.UNKNOWN_COLUMN, b.toString());
        }
    }

    private List<SelectItem> parseSelectList(Ts ts) throws EngineException {
        List<SelectItem> items = new ArrayList<>();
        while (true) {
            if (ts.peek().type == TokenType.STAR) {
                ts.next();
                items.add(new SelectItem(SelectKind.STAR, null, null));
            } else if (ts.peek().type == TokenType.IDENT
                    && ts.peekAt(1).type == TokenType.LPAREN) {
                // 聚合函数：COUNT / SUM / AVG / MIN / MAX
                String fn = ts.next().text.toUpperCase();
                ts.next(); // 消费 (
                ts.eatKw("DISTINCT"); // 聚合内 DISTINCT 暂忽略（与顶层 DISTINCT 语义一致，去重交给外层）
                String ref;
                if (ts.peek().type == TokenType.STAR) {
                    ts.next();
                    ref = "*";
                } else {
                    ref = readColRef(ts);
                }
                ts.expect(TokenType.RPAREN, ")");
                SelectKind k = switch (fn) {
                    case "COUNT" -> SelectKind.COUNT;
                    case "SUM" -> SelectKind.SUM;
                    case "AVG" -> SelectKind.AVG;
                    case "MIN" -> SelectKind.MIN;
                    case "MAX" -> SelectKind.MAX;
                    default -> throw syn("不支持的聚合函数: " + fn);
                };
                String alias = readAlias(ts);
                items.add(new SelectItem(k, ref, alias));
            } else if (ts.peek().type == TokenType.IDENT
                    && ts.peekAt(1).type == TokenType.DOT
                    && (ts.peekAt(2).type == TokenType.STAR
                            || (ts.peekAt(2).type == TokenType.IDENT
                                    && ts.peekAt(3).type == TokenType.DOT
                                    && ts.peekAt(4).type == TokenType.STAR))) {
                // 限定星号 `t.*` / `db.t.*`：ref 存限定符（表别名或表名），project 阶段按前缀展开
                String q = ts.next().text;
                ts.next();
                if (ts.peek().type == TokenType.STAR) {
                    ts.next();
                } else {
                    q = ts.next().text; // db.t.* → 限定符取表名
                    ts.next();
                    ts.next();
                }
                items.add(new SelectItem(SelectKind.STAR, q, null));
            } else if (ts.peek().type == TokenType.LPAREN
                    && ts.peekAt(1).type == TokenType.IDENT
                    && ts.peekAt(1).text.equalsIgnoreCase("SELECT")) {
                // 标量子查询：`(SELECT ...)` 作为 SELECT 列表项
                SelectPlan plan = parseSubqueryAfterParen(ts);
                String alias = readAlias(ts);
                items.add(new SelectItem(SelectKind.SUBQ, null, alias, plan));
            } else if (ts.peek().type == TokenType.NUMBER || ts.peek().type == TokenType.STRING
                    || (ts.peek().type == TokenType.IDENT && isLiteralKeyword(ts.peek().text))) {
                // 裸字面量：`SELECT 1` / `SELECT 'x'` / `SELECT TRUE` / `SELECT NULL` 作为常量列
                Object val = parseLiteral(ts.next());
                String alias = readAlias(ts);
                items.add(new SelectItem(SelectKind.LITERAL, null, alias, val));
            } else {
                String ref = readColRef(ts);
                String alias = readAlias(ts);
                items.add(new SelectItem(SelectKind.COL, ref, alias));
            }
            if (ts.eat(TokenType.COMMA)) {
                continue;
            }
            break;
        }
        return items;
    }

    /** 读取一个可能带点号的列引用，并把 `db.t.col` 三段式降为 `t.col`。 */
    private static String readColRef(Ts ts) throws EngineException {
        String ref = ts.next().text;
        while (ts.peek().type == TokenType.DOT) {
            ts.next();
            ref = ref + "." + ts.next().text;
        }
        int d1 = ref.indexOf('.');
        if (d1 >= 0 && ref.indexOf('.', d1 + 1) >= 0) {
            ref = ref.substring(d1 + 1);
        }
        return ref;
    }

    /** 读取 SELECT 项的别名（AS 显式或隐式裸 IDENT），无则返回 null。 */
    private String readAlias(Ts ts) throws EngineException {
        if (ts.eatKw("AS")) {
            return ts.expect(TokenType.IDENT, "别名").text;
        }
        if (ts.peek().type == TokenType.IDENT && !isClauseKeyword(ts.peek().text)) {
            // 隐式别名（无 AS）：`SELECT col alias FROM ...`（MySQL 标准写法，DataGrip 内省常用）
            return ts.next().text;
        }
        return null;
    }

    private FromClause parseFrom(Ts ts) throws EngineException {
        FromClause left = parseTableFactor(ts);
        while (true) {
            boolean leftJoin = false;
            boolean explicit = false;
            if (ts.eatKw("JOIN")) {
                explicit = true;
            } else if (ts.eatKw("INNER")) {
                ts.eatKw("JOIN");
                explicit = true;
            } else if (ts.eatKw("CROSS")) {
                ts.eatKw("JOIN");
                explicit = true; // CROSS JOIN 视为无 ON 的内连接
            } else if (ts.eatKw("LEFT")) {
                ts.eatKw("OUTER");
                ts.expectKw("JOIN");
                leftJoin = true;
                explicit = true;
            } else if (ts.eatKw("RIGHT")) {
                // 右连接转写为左连接（交换左右子树），简化实现但语义等价
                ts.eatKw("OUTER");
                ts.expectKw("JOIN");
                FromClause right = parseTableFactor(ts);
                Pred on = ts.eatKw("ON") ? parsePred(ts) : null;
                left = new JoinClause(right, left, true, on);
                continue;
            } else if (ts.eat(TokenType.COMMA)) {
                explicit = false; // 逗号隐式内连接（保持旧行为）
            } else {
                break;
            }
            FromClause right = parseTableFactor(ts);
            Pred on = (explicit && ts.eatKw("ON")) ? parsePred(ts) : null;
            left = new JoinClause(left, right, leftJoin, on);
        }
        return left;
    }

    private FromClause parseTableFactor(Ts ts) throws EngineException {
        // 派生表：`FROM (SELECT ...) [AS] alias`
        if (ts.peek().type == TokenType.LPAREN) {
            SelectPlan plan = parseSubqueryAfterParen(ts);
            String alias = "subq";
            if (ts.eatKw("AS")) {
                alias = ts.expect(TokenType.IDENT, "别名").text;
            } else if (ts.peek().type == TokenType.IDENT && !isClauseKeyword(ts.peek().text)) {
                alias = ts.next().text;
            }
            return new SubqueryFactor(plan, alias);
        }
        String name = ts.expect(TokenType.IDENT, "表名").text;
        if (ts.peek().type == TokenType.DOT) {
            ts.next(); // 消费点号，支持 `db.table` 限定写法
            name = name + "." + ts.expect(TokenType.IDENT, "表名").text;
        }
        // 默认别名：未显式起别名时，`db.table` 形式用“表名”而非“db.table”，
        // 这样 WHERE 里的 `table.column` 限定写法也能正确匹配（贴近真实 MySQL 行为）。
        String alias = name.contains(".") ? name.substring(name.indexOf('.') + 1) : name;
        if (ts.eatKw("AS")) {
            alias = ts.expect(TokenType.IDENT, "别名").text;
        } else if (ts.peek().type == TokenType.IDENT && !isClauseKeyword(ts.peek().text)) {
            alias = ts.next().text;
        }
        return new TableFactor(name, alias);
    }

    private static boolean isClauseKeyword(String w) {
        return switch (w.toUpperCase()) {
            case "WHERE", "ORDER", "LIMIT", "GROUP", "HAVING", "JOIN", "ON", "INNER",
                 "LEFT", "RIGHT", "FULL", "CROSS", "UNION", "FROM" -> true;
            default -> false;
        };
    }

    /** 解析 WHERE / HAVING / ON 的布尔表达式，返回 {@link Pred} 谓词树。 */
    private Pred parseWhere(Ts ts) throws EngineException {
        return parsePred(ts);
    }

    private Pred parsePred(Ts ts) throws EngineException {
        return parseOr(ts);
    }

    private Pred parseOr(Ts ts) throws EngineException {
        Pred left = parseAnd(ts);
        while (ts.eatKw("OR")) {
            left = Pred.or(left, parseAnd(ts));
        }
        return left;
    }

    private Pred parseAnd(Ts ts) throws EngineException {
        Pred left = parseNot(ts);
        while (ts.eatKw("AND")) {
            left = Pred.and(left, parseNot(ts));
        }
        return left;
    }

    private Pred parseNot(Ts ts) throws EngineException {
        if (ts.eatKw("NOT")) {
            return parsePredAtom(ts, true);
        }
        return parsePredAtom(ts, false);
    }

    private Pred parsePredAtom(Ts ts, boolean neg) throws EngineException {
        if (ts.peek().type == TokenType.LPAREN) {
            ts.next();
            Pred p = parseOr(ts);
            ts.expect(TokenType.RPAREN, ")");
            return neg ? Pred.not(p) : p;
        }
        // EXISTS (SELECT ...)
        if (ts.peek().type == TokenType.IDENT && ts.peek().text.equalsIgnoreCase("EXISTS")) {
            ts.next();
            SelectPlan plan = parseSubqueryAfterParen(ts);
            return Pred.exists(plan, neg);
        }
        Operand left;
        Token a0 = ts.peek();
        if (a0.type == TokenType.IDENT && ts.peekAt(1).type == TokenType.LPAREN && isAggFn(a0.text)) {
            // HAVING 中常见的“聚合函数引用”（如 COUNT(*) / SUM(x)），解析为按输出列名解析的 Operand
            String fn = ts.next().text.toUpperCase();
            ts.next(); // 消费 (
            String ref = ts.peek().type == TokenType.STAR ? ts.next().text : readColRef(ts);
            ts.expect(TokenType.RPAREN, ")");
            left = Operand.col(aggRefName(fn, ref));
        } else {
            left = parseOperand(ts);
        }
        TokenType tt = ts.peek().type;
        if (tt == TokenType.OP) {
            String op = ts.next().text;
            Operand right = parseOperand(ts);
            Pred cmp = Pred.cmp(left, op, right);
            return neg ? Pred.not(cmp) : cmp;
        }
        // 处理操作数后的 NOT（col NOT LIKE / NOT IN / NOT BETWEEN）。
        boolean postNeg = false;
        String kw = ts.peek().text.toUpperCase();
        if (kw.equals("NOT")) {
            String nxt = ts.peekAt(1) != null ? ts.peekAt(1).text.toUpperCase() : "";
            if (nxt.equals("LIKE") || nxt.equals("IN") || nxt.equals("BETWEEN")) {
                ts.next();          // 消费 NOT
                postNeg = true;
                kw = nxt;
            }
        }
        boolean effNeg = (neg != postNeg);
        if (kw.equals("LIKE")) {
            ts.next();
            String pat = ts.expect(TokenType.STRING, "LIKE 模式").text;
            return Pred.like(left, pat, effNeg);
        }
        if (kw.equals("IN")) {
            ts.next();
            ts.expect(TokenType.LPAREN, "(");
            if (ts.peek().type == TokenType.IDENT && ts.peek().text.equalsIgnoreCase("SELECT")) {
                // IN (SELECT ...) 子查询
                ts.expectKw("SELECT");
                SelectPlan plan = parseSelectPlan(ts);
                ts.expect(TokenType.RPAREN, ")");
                return Pred.inSubquery(left, plan, effNeg);
            }
            List<Object> vals = new ArrayList<>();
            while (true) {
                vals.add(parseOperand(ts).literal);
                if (ts.eat(TokenType.COMMA)) {
                    continue;
                }
                break;
            }
            ts.expect(TokenType.RPAREN, ")");
            return Pred.inList(left, vals, effNeg);
        }
        if (kw.equals("BETWEEN")) {
            ts.next();
            Operand lo = parseOperand(ts);
            ts.expectKw("AND");
            Operand hi = parseOperand(ts);
            return Pred.between(left, lo, hi, effNeg);
        }
        if (kw.equals("IS")) {
            ts.next();
            boolean neg2 = ts.eatKw("NOT");
            ts.expectKw("NULL");
            return Pred.isNull(left, neg != neg2);
        }
        throw syn("无法解析的谓词: " + ts.peek().text);
    }

    private Operand parseOperand(Ts ts) throws EngineException {
        Token p = ts.peek();
        if (p.type == TokenType.LPAREN) {
            // 标量子查询（操作数位置）：(SELECT ...)
            if (ts.peekAt(1).type == TokenType.IDENT && ts.peekAt(1).text.equalsIgnoreCase("SELECT")) {
                SelectPlan plan = parseSubqueryAfterParen(ts);
                return Operand.subq(plan);
            }
            // 其余括号表达式（如 (a+b)）本引擎暂不支持，交由上层分组逻辑或报错。
        }
        if (p.type == TokenType.STRING) {
            ts.next();
            return Operand.lit(p.text);
        }
        if (p.type == TokenType.NUMBER) {
            ts.next();
            return Operand.lit(parseNumber(p.text));
        }
        if (p.type == TokenType.IDENT) {
            String w = p.text.toUpperCase();
            if (w.equals("TRUE")) {
                ts.next();
                return Operand.lit(Boolean.TRUE);
            }
            if (w.equals("FALSE")) {
                ts.next();
                return Operand.lit(Boolean.FALSE);
            }
            if (w.equals("NULL")) {
                ts.next();
                return Operand.lit(null);
            }
            String ref = ts.next().text;
            while (ts.peek().type == TokenType.DOT) {
                ts.next();
                ref = ref + "." + ts.next().text;
            }
            int d1 = ref.indexOf('.');
            if (d1 >= 0 && ref.indexOf('.', d1 + 1) >= 0) {
                ref = ref.substring(d1 + 1);
            }
            return Operand.col(ref);
        }
        throw new EngineException(ErrorCode.SYNTAX_ERROR, "无法解析的操作数: " + p.text);
    }

    // ===================== 查询优化器：统计信息 / 代价估算 / 计划选择 =====================

    /** 列的统计信息：NDV（不同取值个数）与空值个数。 */
    static final class ColStats {
        final int ndv;
        final int nulls;
        ColStats(int ndv, int nulls) { this.ndv = ndv; this.nulls = nulls; }
    }

    /** 表级统计信息：有效行数 + 每列 NDV / 空值数。 */
    static final class TableStats {
        final int rows;
        final Map<String, ColStats> cols = new LinkedHashMap<>(); // 小写列名 -> 列统计
        TableStats(int rows) { this.rows = rows; }
    }

    /** 代价模型常量（抽象代价单位，越小越优；数值参照"处理一行 ≈ 0.01"的量纲）。 */
    private static final double COST_TUPLE = 0.01;      // 读取并判断一行的 CPU 代价
    private static final double COST_INDEX = 0.1;       // 一次哈希索引探针的固定开销（≈ 判断 10 行）
    private static final double COST_JOIN_TUPLE = 0.02; // 连接时比较一对元组的代价

    /** 表 -> 统计信息缓存。只在基表上缓存（派生表/视图是临时合成表），写语句后整体失效。 */
    private final Map<Table, TableStats> statsCache = new LinkedHashMap<>();

    /** 物理计划节点：扫描（SCAN）或嵌套循环连接（JOIN）。 */
    static final class OptPlan {
        enum Kind { SCAN, JOIN }
        Kind kind;
        // ---- SCAN ----
        FromClause factor;
        Table table;
        int colOffset, colWidth;
        String alias;
        final List<Pred> pushed = new ArrayList<>();   // 已下推到该扫描的单表谓词
        String access = "ALL";                          // ALL=全表扫描 / ref=哈希索引等值查找
        String indexCol;                                // 选中的索引列
        final List<String> possibleKeys = new ArrayList<>();
        double baseRows;                                // 统计基数（下推前）
        // ---- JOIN ----
        OptPlan left, right;
        final List<Pred> joinPreds = new ArrayList<>(); // 在该连接处生效的连接谓词
        // ---- 公共 ----
        double cost;      // 累计代价
        double estRows;   // 预估输出行数
        int mask;         // 覆盖的叶子因子位掩码
        int[] slots;      // 本节点在规范行中负责填充的列下标
    }

    /** 优化结果：物理计划 + 无法下推的残余谓词 + 叶子因子顺序。 */
    static final class OptimizedQuery {
        OptPlan plan;
        final List<Pred> residual = new ArrayList<>();
        List<FromClause> leaves = new ArrayList<>();
    }

    /** 取表统计信息（惰性计算；仅基表缓存）。 */
    private TableStats statsOf(Table t) {
        TableStats st = statsCache.get(t);
        if (st != null) {
            return st;
        }
        st = analyzeTable(t);
        if (isBaseTable(t)) {
            statsCache.put(t, st);
        }
        return st;
    }

    /** 写语句后统计信息整体失效（下次查询惰性重算）。 */
    private void invalidateAllStats() {
        statsCache.clear();
    }

    private boolean isBaseTable(Table t) {
        for (Database d : databases.values()) {
            if (d.tables.containsValue(t)) {
                return true;
            }
        }
        return false;
    }

    /** 扫描一遍统计行数与每列 NDV / 空值数；列上有哈希索引时 NDV 直接取桶数（O(1)）。 */
    private TableStats analyzeTable(Table t) {
        int live = 0;
        for (Object[] r : t.rows) {
            if (r != null) {
                live++;
            }
        }
        TableStats st = new TableStats(live);
        for (ColumnDef cd : t.cols) {
            String col = cd.name;
            int ci = t.indexOf(col);
            Map<Object, List<Integer>> im = t.indexMaps.get(col);
            int ndv;
            int nulls = 0;
            if (im != null && !im.isEmpty()) {
                ndv = im.size();
                for (Object[] r : t.rows) {
                    if (r != null && ci >= 0 && ci < r.length && r[ci] == null) {
                        nulls++;
                    }
                }
            } else {
                Set<Object> seen = new HashSet<>();
                for (Object[] r : t.rows) {
                    if (r == null) {
                        continue;
                    }
                    Object v = ci >= 0 && ci < r.length ? r[ci] : null;
                    if (v == null) {
                        nulls++;
                    } else {
                        seen.add(indexKey(v));
                    }
                }
                ndv = seen.size();
            }
            st.cols.put(col.toLowerCase(), new ColStats(Math.max(1, ndv), nulls));
        }
        return st;
    }

    /** 列引用 -> 所属基表（解析失败返回 null）。 */
    private Table tableOfRef(Combined c, String ref) {
        if (c == null || ref == null) {
            return null;
        }
        try {
            int idx = c.resolve(ref);
            int[] s = c.src.get(idx);
            if (s == null || s[0] < 0 || s[0] >= lastTables.size()) {
                return null;
            }
            return lastTables.get(s[0]);
        } catch (Exception e) {
            return null;
        }
    }

    private int ndvOfRef(Combined c, String ref) {
        Table t = tableOfRef(c, ref);
        if (t == null || ref == null) {
            return 10;   // 未知列：按 NDV=10 保守估计
        }
        TableStats st = statsOf(t);
        ColStats cs = st.cols.get(bare(ref).toLowerCase());
        return cs == null ? Math.max(1, st.rows) : cs.ndv;
    }

    private double nullFracOfRef(Combined c, String ref) {
        Table t = tableOfRef(c, ref);
        if (t == null || ref == null) {
            return 0.05;
        }
        TableStats st = statsOf(t);
        ColStats cs = st.cols.get(bare(ref).toLowerCase());
        if (cs == null || st.rows <= 0) {
            return 0.05;
        }
        return Math.min(1.0, (double) cs.nulls / st.rows);
    }

    /**
     * 谓词选择率估算：返回该谓词过滤后剩余行数的比例（0,1]）。
     * 等值用 1/NDV；范围/模糊匹配用经验常数；AND 相乘，OR 用 1-(1-a)(1-b)。
     */
    private double selectivity(Combined c, Pred p) {
        if (p == null) {
            return 1.0;
        }
        try {
            switch (p.kind) {
                case AND -> {
                    return selectivity(c, p.left2) * selectivity(c, p.right2);
                }
                case OR -> {
                    double a = selectivity(c, p.left2), b = selectivity(c, p.right2);
                    return Math.min(1.0, a + b - a * b);
                }
                case NOT -> {
                    return Math.max(0.01, 1.0 - selectivity(c, p.child));
                }
                case CMP -> {
                    boolean lCol = p.left != null && p.left.colRef != null;
                    boolean rCol = p.right != null && p.right.colRef != null;
                    if (lCol && rCol) {
                        // 连接条件：1 / max(两端 NDV)
                        int n1 = ndvOfRef(c, p.left.colRef), n2 = ndvOfRef(c, p.right.colRef);
                        return 1.0 / Math.max(1, Math.max(n1, n2));
                    }
                    int ndv = ndvOfRef(c, lCol ? p.left.colRef : null);
                    return switch (p.op) {
                        case "=" -> 1.0 / Math.max(1, ndv);
                        case "<>", "!=" -> Math.max(0.01, 1.0 - 1.0 / Math.max(1, ndv));
                        case "<", "<=", ">", ">=" -> 0.3;
                        default -> 0.5;
                    };
                }
                case LIKE -> {
                    boolean lead = p.pattern != null
                            && (p.pattern.startsWith("%") || p.pattern.startsWith("_"));
                    double s = lead ? 0.2 : 0.1;
                    return p.negated ? Math.max(0.01, 1.0 - s) : s;
                }
                case IN -> {
                    if (p.inSubquery != null) {
                        return 0.5;
                    }
                    int ndv = ndvOfRef(c, p.left != null ? p.left.colRef : null);
                    int n = p.values == null ? 1 : Math.max(1, p.values.size());
                    double s = Math.min(1.0, (double) n / Math.max(1, ndv));
                    return p.negated ? Math.max(0.01, 1.0 - s) : s;
                }
                case BETWEEN -> {
                    return p.negated ? 0.9 : 0.1;
                }
                case ISNULL -> {
                    double nf = nullFracOfRef(c, p.left != null ? p.left.colRef : null);
                    return p.negated ? Math.max(0.01, 1.0 - nf) : Math.max(0.01, nf);
                }
                case EXISTS -> {
                    return 0.5;
                }
                default -> {
                    return 0.5;
                }
            }
        } catch (Exception e) {
            return 0.5;
        }
    }

    /** 递归拆 WHERE 的 AND 链为合取项列表。 */
    private void splitAnd(Pred p, List<Pred> out) {
        if (p == null) {
            return;
        }
        if (p.kind == Pred.Kind.AND) {
            splitAnd(p.left2, out);
            splitAnd(p.right2, out);
        } else {
            out.add(p);
        }
    }

    /** 收集谓词里出现的所有列引用。 */
    private void collectColRefs(Pred p, Set<String> out) {
        if (p == null) {
            return;
        }
        if (p.left != null && p.left.colRef != null) {
            out.add(p.left.colRef);
        }
        if (p.right != null && p.right.colRef != null) {
            out.add(p.right.colRef);
        }
        if (p.lo != null && p.lo.colRef != null) {
            out.add(p.lo.colRef);
        }
        if (p.hi != null && p.hi.colRef != null) {
            out.add(p.hi.colRef);
        }
        collectColRefs(p.left2, out);
        collectColRefs(p.right2, out);
        collectColRefs(p.child, out);
    }

    /** 谓词引用了哪些叶子因子（用列区间判定），返回位掩码；0 表示无法归属。 */
    private int factorMaskOf(Combined c, Pred p, List<FromClause> leaves) {
        Set<String> refs = new LinkedHashSet<>();
        collectColRefs(p, refs);
        if (refs.isEmpty()) {
            return 0;
        }
        int mask = 0;
        for (int i = 0; i < leaves.size(); i++) {
            FromClause f = leaves.get(i);
            int off = f.colOffset, end = off + f.colWidth;
            for (String ref : refs) {
                try {
                    int idx = c.resolve(ref);
                    if (idx >= off && idx < end) {
                        mask |= (1 << i);
                        break;
                    }
                } catch (Exception ignore) {
                    // 无法解析的列引用忽略
                }
            }
        }
        return mask;
    }

    /** 收集可参与重排的叶子因子与 INNER JOIN 的 ON 谓词；遇到 LEFT JOIN 返回 false（禁止重排）。 */
    private boolean collectLeaves(FromClause f, List<FromClause> out, List<Pred> onPreds) {
        if (f instanceof TableFactor || f instanceof SubqueryFactor) {
            out.add(f);
            return true;
        }
        JoinClause jc = (JoinClause) f;
        if (jc.leftJoin) {
            return false;   // 外连接不可交换/结合，重排会改变语义
        }
        splitAnd(jc.on, onPreds);
        return collectLeaves(jc.left, out, onPreds) && collectLeaves(jc.right, out, onPreds);
    }

    /** 为一个叶子因子构造扫描节点：做访问路径选择（全表扫描 vs 哈希索引等值查找）。 */
    private OptPlan buildScan(Combined c, FromClause f, int idx, List<Pred> pushed) {
        OptPlan sp = new OptPlan();
        sp.kind = OptPlan.Kind.SCAN;
        sp.factor = f;
        sp.colOffset = f.colOffset;
        sp.colWidth = f.colWidth;
        Integer ti = factorIdx.get(f);
        sp.table = ti == null ? null : lastTables.get(ti);
        if (f instanceof TableFactor tf) {
            sp.alias = tf.alias != null ? tf.alias : tf.name;
        } else if (f instanceof SubqueryFactor sf) {
            sp.alias = sf.alias;
        } else {
            sp.alias = "t" + idx;
        }
        sp.pushed.addAll(pushed);
        sp.mask = 1 << idx;
        sp.slots = new int[f.colWidth];
        for (int k = 0; k < f.colWidth; k++) {
            sp.slots[k] = f.colOffset + k;
        }

        double base = sp.table == null ? 1 : statsOf(sp.table).rows;
        sp.baseRows = base;
        double sel = 1.0;
        for (Pred p : pushed) {
            sel *= selectivity(c, p);
        }
        sp.estRows = Math.max(1, base * sel);
        sp.cost = Math.max(1, base) * COST_TUPLE;   // 默认：全表扫描
        sp.access = "ALL";
        if (sp.table != null) {
            sp.possibleKeys.addAll(sp.table.indexCols);
            Pred eq = findEqOnIndex(sp.table, pushed);
            if (eq != null) {
                double matched = Math.max(1, base * selectivity(c, eq));
                double idxCost = COST_INDEX + matched * COST_TUPLE;
                if (idxCost < sp.cost) {
                    sp.cost = idxCost;
                    sp.access = "ref";
                    sp.indexCol = bare(eq.left.colRef);
                }
            }
        }
        return sp;
    }

    /** 在谓词列表里找"索引列上的等值条件"。 */
    private Pred findEqOnIndex(Table t, List<Pred> preds) {
        for (Pred p : preds) {
            if (p.kind == Pred.Kind.CMP && "=".equals(p.op)
                    && p.left != null && p.left.colRef != null
                    && p.right != null && p.right.literal != null
                    && t.indexCols.contains(bare(p.left.colRef))) {
                return p;
            }
        }
        return null;
    }

    /** 构造连接节点并估算代价：嵌套循环 = 外层代价 + 外层行数 × 内层代价 + 元组比较代价。 */
    private OptPlan makeJoin(OptPlan left, OptPlan right, List<Pred> preds, Combined c) {
        OptPlan j = new OptPlan();
        j.kind = OptPlan.Kind.JOIN;
        j.left = left;
        j.right = right;
        j.joinPreds.addAll(preds);
        j.mask = left.mask | right.mask;
        j.slots = new int[left.slots.length + right.slots.length];
        System.arraycopy(left.slots, 0, j.slots, 0, left.slots.length);
        System.arraycopy(right.slots, 0, j.slots, left.slots.length, right.slots.length);
        double sel = 1.0;
        for (Pred p : preds) {
            sel *= selectivity(c, p);
        }
        j.estRows = Math.max(1, left.estRows * right.estRows * sel);
        j.cost = left.cost + left.estRows * right.cost
                + left.estRows * right.estRows * COST_JOIN_TUPLE;
        return j;
    }

    /**
     * 基于代价的查询优化：谓词下推 + 访问路径选择 + 连接顺序选择（≤10 表用动态规划，更多按书写顺序）。
     * 返回 null 表示不接管（无 FROM / 含 LEFT JOIN / 解析异常），调用方回退原有执行路径。
     */
    private OptimizedQuery optimize(Combined c, FromClause from, Pred where) throws EngineException {
        if (from == null) {
            return null;
        }
        List<FromClause> leaves = new ArrayList<>();
        List<Pred> onPreds = new ArrayList<>();
        if (!collectLeaves(from, leaves, onPreds)) {
            return null;
        }
        if (leaves.isEmpty()) {
            return null;
        }

        List<Pred> conjuncts = new ArrayList<>();
        splitAnd(where, conjuncts);
        conjuncts.addAll(onPreds);   // INNER JOIN 的 ON 与 WHERE 等价，一起参与下推

        int n = leaves.size();
        Map<Integer, List<Pred>> byFactor = new HashMap<>();
        List<Pred> joinPreds = new ArrayList<>();
        List<Integer> jpMasks = new ArrayList<>();
        List<Pred> residual = new ArrayList<>();
        for (Pred p : conjuncts) {
            int m = factorMaskOf(c, p, leaves);
            int bits = Integer.bitCount(m);
            if (m == 0 || bits > 2) {
                residual.add(p);          // 跨 3 张表以上 / 含子查询等：留在过滤算子
            } else if (bits == 1) {
                byFactor.computeIfAbsent(m, k -> new ArrayList<>()).add(p);
            } else {
                joinPreds.add(p);
                jpMasks.add(m);
            }
        }

        OptPlan[] scans = new OptPlan[n];
        for (int i = 0; i < n; i++) {
            scans[i] = buildScan(c, leaves.get(i), i, byFactor.getOrDefault(1 << i, List.of()));
        }

        OptimizedQuery oq = new OptimizedQuery();
        oq.leaves = leaves;
        oq.residual.addAll(residual);

        if (n == 1) {
            oq.plan = scans[0];
            return oq;
        }
        if (n > 10) {
            // 表过多：不枚举顺序，按书写顺序连接（仍保留谓词下推与访问路径选择）
            OptPlan cur = scans[0];
            for (int i = 1; i < n; i++) {
                int newMask = cur.mask | (1 << i);
                List<Pred> applicable = new ArrayList<>();
                for (int k = 0; k < joinPreds.size(); k++) {
                    int m = jpMasks.get(k);
                    if ((m & (1 << i)) != 0 && (m & cur.mask) != 0 && (m & ~newMask) == 0) {
                        applicable.add(joinPreds.get(k));
                    }
                }
                cur = makeJoin(cur, scans[i], applicable, c);
            }
            oq.plan = cur;
            return oq;
        }

        // 动态规划：best[mask] = 该子集下的最优（左深）连接计划
        int size = 1 << n;
        OptPlan[] best = new OptPlan[size];
        for (int i = 0; i < n; i++) {
            best[1 << i] = scans[i];
        }
        for (int mask = 1; mask < size; mask++) {
            if (Integer.bitCount(mask) <= 1) {
                continue;
            }
            for (int i = 0; i < n; i++) {
                if ((mask & (1 << i)) == 0) {
                    continue;
                }
                int prev = mask ^ (1 << i);
                OptPlan left = best[prev];
                if (left == null) {
                    continue;
                }
                List<Pred> applicable = new ArrayList<>();
                for (int k = 0; k < joinPreds.size(); k++) {
                    int m = jpMasks.get(k);
                    if ((m & (1 << i)) != 0 && (m & prev) != 0 && (m & ~mask) == 0) {
                        applicable.add(joinPreds.get(k));
                    }
                }
                OptPlan cand = makeJoin(left, scans[i], applicable, c);
                if (best[mask] == null || cand.cost < best[mask].cost) {
                    best[mask] = cand;
                }
            }
        }
        oq.plan = best[size - 1];
        return oq;
    }

    /** 执行物理计划，产出规范布局（与 Combined 列序一致）的组合行。 */
    private List<Object[]> materialize(Combined c, OptPlan plan) throws EngineException {
        if (plan.kind == OptPlan.Kind.SCAN) {
            return scanRows(c, plan);
        }
        List<Object[]> L = materialize(c, plan.left);
        List<Object[]> R = materialize(c, plan.right);
        int width = c.outCols.size();
        List<Object[]> out = new ArrayList<>();
        for (Object[] l : L) {
            for (Object[] r : R) {
                Object[] joined = new Object[width];
                System.arraycopy(l, 0, joined, 0, width);
                for (int k : plan.right.slots) {
                    joined[k] = r[k];
                }
                boolean ok = true;
                for (Pred p : plan.joinPreds) {
                    if (!evaluatePred(c, joined, p)) {
                        ok = false;
                        break;
                    }
                }
                if (ok) {
                    out.add(joined);
                }
            }
        }
        return out;
    }

    /** 扫描节点取行：优先走哈希索引定位槽号，否则顺序扫描；随后应用下推谓词。 */
    private List<Object[]> scanRows(Combined c, OptPlan sp) throws EngineException {
        int width = c.outCols.size();
        List<Object[]> out = new ArrayList<>();
        Table t = sp.table;
        if (t == null) {
            out.add(new Object[width]);
            return out;
        }
        if (sp.indexCol != null) {
            Map<Object, List<Integer>> m = t.indexMaps.get(sp.indexCol);
            Pred eq = findEqOnIndex(t, sp.pushed);
            List<Integer> slots = null;
            if (m != null && eq != null) {
                slots = m.get(indexKey(eq.right.literal));
            }
            if (slots != null) {
                for (int si : slots) {
                    if (si < 0 || si >= t.rows.size()) {
                        continue;
                    }
                    Object[] r = visibleRow(t, si, currentReadView);   // 快照读：可能回溯到历史版本
                    if (r == null) {
                        continue;
                    }
                    Object[] row = new Object[width];
                    System.arraycopy(r, 0, row, sp.colOffset, sp.colWidth);
                    if (passAll(c, row, sp.pushed)) {
                        out.add(row);
                    }
                }
                return out;
            }
        }
        for (int i = 0; i < t.rows.size(); i++) {
            Object[] r = visibleRow(t, i, currentReadView);
            if (r == null) {
                continue;
            }
            Object[] row = new Object[width];
            System.arraycopy(r, 0, row, sp.colOffset, sp.colWidth);
            if (passAll(c, row, sp.pushed)) {
                out.add(row);
            }
        }
        return out;
    }

    private boolean passAll(Combined c, Object[] row, List<Pred> preds) throws EngineException {
        for (Pred p : preds) {
            if (!evaluatePred(c, row, p)) {
                return false;
            }
        }
        return true;
    }

    private Pred andAll(List<Pred> preds) {
        Pred r = null;
        for (Pred p : preds) {
            r = Pred.and(r, p);
        }
        return r;
    }

    /** EXPLAIN SELECT：只做优化不执行，输出 MySQL 风格的计划表（12 列）。 */
    private QueryResult doExplain(SessionContext session, Ts ts) throws EngineException {
        ts.expectKw("EXPLAIN");
        if (ts.hasNext() && "ANALYZE".equalsIgnoreCase(ts.peek().text)) {
            ts.next();   // EXPLAIN ANALYZE 暂按普通 EXPLAIN 处理
        }
        ts.expectKw("SELECT");
        SelectPlan p = parseSelectPlan(ts, true);
        for (TableFactor tf : collectTableFactors(p.from)) {
            int dot = tf.name.indexOf('.');
            String db = dot < 0 ? requireDb(session).name : tf.name.substring(0, dot);
            String tbl = dot < 0 ? tf.name : tf.name.substring(dot + 1);
            requirePriv(session, db, tbl, Priv.SELECT);
        }
        Combined combined = buildCombined(session, p.from);
        OptimizedQuery oq = null;
        try {
            oq = optimize(combined, p.from, p.where);
        } catch (Exception ignore) {
            oq = null;
        }
        List<ColumnMeta> cols = List.of(
                new ColumnMeta("id", ColumnType.BIGINT, false, false),
                new ColumnMeta("select_type", ColumnType.VARCHAR, false, false),
                new ColumnMeta("table", ColumnType.VARCHAR, true, false),
                new ColumnMeta("partitions", ColumnType.VARCHAR, true, false),
                new ColumnMeta("type", ColumnType.VARCHAR, true, false),
                new ColumnMeta("possible_keys", ColumnType.VARCHAR, true, false),
                new ColumnMeta("key", ColumnType.VARCHAR, true, false),
                new ColumnMeta("key_len", ColumnType.VARCHAR, true, false),
                new ColumnMeta("ref", ColumnType.VARCHAR, true, false),
                new ColumnMeta("rows", ColumnType.BIGINT, true, false),
                new ColumnMeta("filtered", ColumnType.DOUBLE, true, false),
                new ColumnMeta("Extra", ColumnType.VARCHAR, true, false));
        List<List<Object>> out = new ArrayList<>();
        if (oq == null || oq.plan == null) {
            List<Object> r = new ArrayList<>();
            r.add(1L); r.add("SIMPLE"); r.add(null); r.add(null); r.add("ALL");
            r.add(null); r.add(null); r.add(null); r.add(null); r.add(1L); r.add(100.0);
            r.add("no optimizer plan (legacy path)");
            out.add(r);
        } else {
            collectExplainRows(oq.plan, out, null);
            if (!oq.residual.isEmpty() && !out.isEmpty()) {
                List<Object> last = out.get(out.size() - 1);
                Object ex = last.get(11);
                String add = "Using where (residual)";
                last.set(11, ex == null ? add : ex + "; " + add);
            }
        }
        return QueryResult.ofResultSet(cols, out);
    }

    /** 按连接顺序（外层优先）把计划里的每个扫描节点展开成一行 EXPLAIN 输出。 */
    private void collectExplainRows(OptPlan plan, List<List<Object>> rows, String pendingExtra) {
        if (plan == null) {
            return;
        }
        if (plan.kind == OptPlan.Kind.SCAN) {
            List<Object> r = new ArrayList<>();
            r.add(1L);
            r.add("SIMPLE");
            r.add(plan.alias);
            r.add(null);
            r.add(plan.access);
            r.add(plan.possibleKeys.isEmpty() ? null : String.join(",", plan.possibleKeys));
            r.add(plan.indexCol);
            r.add(null);
            r.add(plan.indexCol == null ? null : "const");
            r.add((long) Math.max(1, Math.round(plan.estRows)));
            double filt = plan.baseRows > 0 ? (plan.estRows / plan.baseRows) * 100.0 : 100.0;
            r.add(Math.round(Math.min(100.0, Math.max(0.01, filt)) * 100.0) / 100.0);
            List<String> extra = new ArrayList<>();
            if (pendingExtra != null) {
                extra.add(pendingExtra);
            }
            if (plan.indexCol != null) {
                extra.add("Using index");
            }
            if (!plan.pushed.isEmpty()) {
                extra.add("Using where");
            }
            r.add(extra.isEmpty() ? null : String.join("; ", extra));
            rows.add(r);
        } else {
            collectExplainRows(plan.left, rows, pendingExtra);
            collectExplainRows(plan.right, rows, "Using join buffer (Block Nested Loop)");
        }
    }

    /** ANALYZE TABLE t[, t2 ...]：显式重算统计信息并回报行数与列数。 */
    private QueryResult doAnalyze(SessionContext session, Ts ts) throws EngineException {
        ts.expectKw("ANALYZE");
        ts.eatKw("TABLE");
        List<Table> targets = new ArrayList<>();
        List<String> names = new ArrayList<>();
        while (ts.hasNext()) {
            String name = ts.next().text.replace("`", "").replace("'", "");
            if (name.isEmpty()) {
                break;
            }
            targets.add(requireTableQualified(session, name));
            names.add(name);
            if (ts.eat(TokenType.COMMA)) {
                continue;
            }
            break;
        }
        if (targets.isEmpty()) {
            throw syn("ANALYZE TABLE 需要指定表名");
        }
        List<ColumnMeta> cols = List.of(
                new ColumnMeta("Table", ColumnType.VARCHAR, false, false),
                new ColumnMeta("Op", ColumnType.VARCHAR, false, false),
                new ColumnMeta("Msg_type", ColumnType.VARCHAR, false, false),
                new ColumnMeta("Msg_text", ColumnType.VARCHAR, false, false));
        List<List<Object>> out = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) {
            Table t = targets.get(i);
            statsCache.remove(t);
            TableStats st = statsOf(t);
            out.add(List.of(names.get(i), "analyze", "status",
                    "OK (rows=" + st.rows + ", cols=" + st.cols.size() + ")"));
        }
        return QueryResult.ofResultSet(cols, out);
    }

    // ===================== SELECT 执行辅助 =====================

    private Combined buildCombined(SessionContext session, FromClause from) throws EngineException {
        if (from == null) {
            // FROM 省略（如 `SELECT 1`）：无基表，返回空列组合，由 evaluateFrom 产出单行占位。
            lastTableRows = new ArrayList<>();
            lastTables = new ArrayList<>();
            factorIdx.clear();
            return new Combined();
        }
        Combined c = new Combined();
        lastTableRows = new ArrayList<>();
        lastTables = new ArrayList<>();
        factorIdx.clear();
        layoutFrom(session, from, c, 0);
        return c;
    }

    /** 递归铺开 FROM 树，填充 combined 的输出列、源映射，并记录每个表因子对应的基表下标。 */
    private int layoutFrom(SessionContext session, FromClause f, Combined c, int offset) throws EngineException {
        f.colOffset = offset;
        if (f instanceof TableFactor tf) {
            Table t = requireTableQualified(session, tf.name);
            int ti = lastTables.size();
            lastTableRows.add(t.rows);
            lastTables.add(t);
            factorIdx.put(tf, ti);
            for (ColumnDef cd : t.cols) {
                String qualName = tf.alias + "." + cd.name;
                c.outCols.add(new ColumnDef(qualName, cd.type, cd.nullable, cd.pk, cd.def));
                c.src.add(new int[]{ti, t.indexOf(cd.name)});
            }
            f.colWidth = t.cols.size();
            return offset + t.cols.size();
        }
        if (f instanceof SubqueryFactor sf) {
            // 派生表：先执行子查询得到结果集，包装成一张合成表接入 Combined。
            // 执行子查询会重置 lastTables/lastTableRows/factorIdx（buildCombined 内部），
            // 故执行前后备份/恢复外层上下文，仅把“合成表”挂载回来。
            List<List<Object[]>> savedRows = new ArrayList<>(lastTableRows);
            List<Table> savedTables = new ArrayList<>(lastTables);
            Map<FromClause, Integer> savedFactor = new HashMap<>(factorIdx);
            QueryResult qr = execPlan(session, sf.plan);
            Table vt = new Table(sf.alias);
            for (ColumnMeta cm : qr.columns()) {
                vt.cols.add(new ColumnDef(sf.alias + "." + cm.name(), cm.type(), cm.nullable(), cm.primaryKey(), null));
            }
            for (List<Object> r : qr.rows()) {
                vt.rows.add(r.toArray(new Object[0]));
            }
            // 恢复外层上下文并挂载合成表
            lastTableRows = savedRows;
            lastTables = savedTables;
            factorIdx = savedFactor;
            int ti = lastTables.size();
            lastTableRows.add(vt.rows);
            lastTables.add(vt);
            factorIdx.put(sf, ti);
            for (int i = 0; i < vt.cols.size(); i++) {
                c.outCols.add(vt.cols.get(i));
                c.src.add(new int[]{ti, i});
            }
            f.colWidth = vt.cols.size();
            return offset + vt.cols.size();
        }
        JoinClause jc = (JoinClause) f;
        int lo = layoutFrom(session, jc.left, c, offset);
        int ro = layoutFrom(session, jc.right, c, lo);
        jc.colWidth = ro - offset;
        return ro;
    }

    /** 收集 FROM 树中所有表因子（用于权限校验与遍历）。 */
    private List<TableFactor> collectTableFactors(FromClause from) {
        List<TableFactor> out = new ArrayList<>();
        if (from == null) {
            return out;
        }
        if (from instanceof TableFactor tf) {
            out.add(tf);
        } else if (from instanceof SubqueryFactor sf) {
            out.addAll(collectTableFactors(sf.plan.from));
        } else {
            JoinClause jc = (JoinClause) from;
            out.addAll(collectTableFactors(jc.left));
            out.addAll(collectTableFactors(jc.right));
        }
        return out;
    }

    /**
     * 按 FROM 树计算连接后的组合行。
     * 单表因子直接返回基表原始行（offset=0，便于 UPDATE/DELETE 就地操作）；
     * 连接节点做嵌套循环连接，LEFT JOIN 在左表无匹配时填充右半侧 null。
     */
    private List<Object[]> evaluateFrom(Combined combined, FromClause from) throws EngineException {
        if (from == null) {
            // FROM 省略：返回单行空占位（供 SELECT 列表中的裸字面量/聚合投影）。
            List<Object[]> single = new ArrayList<>();
            single.add(new Object[0]);
            return single;
        }
        if (from instanceof TableFactor || from instanceof SubqueryFactor) {
            int ti = factorIdx.get(from);
            Table t = lastTables.get(ti);
            List<Object[]> out = new ArrayList<>();
            if (currentReadView == null) {
                for (Object[] r : t.rows) {
                    if (r != null) {
                        out.add(r);
                    }
                }
            } else {
                // 快照读：逐槽判断可见性，看不见就沿 undo 版本链回溯；回溯结果可能为 null（该行在我的快照里不存在）
                for (int i = 0; i < t.rows.size(); i++) {
                    Object[] r = visibleRow(t, i, currentReadView);
                    if (r != null) {
                        out.add(r);
                    }
                }
            }
            return out;
        }
        JoinClause jc = (JoinClause) from;
        List<Object[]> L = evaluateFrom(combined, jc.left);
        List<Object[]> R = evaluateFrom(combined, jc.right);
        int lw = jc.left.colWidth, rw = jc.right.colWidth;
        List<Object[]> result = new ArrayList<>();
        for (Object[] l : L) {
            boolean matched = false;
            for (Object[] r : R) {
                Object[] joined = new Object[lw + rw];
                System.arraycopy(l, 0, joined, 0, lw);
                System.arraycopy(r, 0, joined, lw, rw);
                if (jc.on == null || evaluatePred(combined, joined, jc.on)) {
                    result.add(joined);
                    matched = true;
                }
            }
            if (jc.leftJoin && !matched) {
                Object[] joined = new Object[lw + rw];
                System.arraycopy(l, 0, joined, 0, lw);
                result.add(joined); // 右半侧全部为 null
            }
        }
        return result;
    }

    /** 索引键归一：数值统一为 Double，避免 INTEGER(1) 与 BIGINT(1) 因 Integer/Long 类型不同而匹配失败。 */
    private static Object indexKey(Object v) {
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        return v;
    }

    private Map<FromClause, Integer> factorIdx = new HashMap<>();
    private List<List<Object[]>> lastTableRows = new ArrayList<>();
    private List<Table> lastTables = new ArrayList<>();

    /** 单表 + WHERE：优先用哈希索引缩小候选（命中索引列上的等值条件），否则全扫描。 */
    private List<Object[]> filterTableRows(Table t, Combined c, Pred p) throws EngineException {
        ReadView v = currentReadView;   // 快照读时非 null；UPDATE/DELETE 走当前读时为 null
        if (p == null) {
            List<Object[]> out = new ArrayList<>();
            for (int i = 0; i < t.rows.size(); i++) {
                Object[] r = visibleRow(t, i, v);
                if (r != null) {
                    out.add(r);
                }
            }
            return out;
        }
        Pred eq = findIndexableEq(p, t);
        if (eq != null) {
            Map<Object, List<Integer>> m = t.indexMaps.get(bare(eq.left.colRef));
            List<Integer> idxs = m != null ? m.get(indexKey(eq.right.literal)) : null;
            if (idxs == null) {
                return new ArrayList<>();
            }
            List<Object[]> out = new ArrayList<>();
            for (int i : idxs) {
                if (i < 0 || i >= t.rows.size()) {
                    continue;    // 索引可能短暂滞后于行数组（并发下），越界直接跳过
                }
                Object[] r = visibleRow(t, i, v);
                if (r == null) {
                    continue;
                }
                if (evaluatePred(c, r, p)) {
                    out.add(r);
                }
            }
            return out;
        }
        List<Object[]> out = new ArrayList<>();
        for (int i = 0; i < t.rows.size(); i++) {
            Object[] r = visibleRow(t, i, v);
            if (r == null) {
                continue;
            }
            if (evaluatePred(c, r, p)) {
                out.add(r);
            }
        }
        return out;
    }

    /** 在 AND 链里找“索引列上的等值条件”，用于单表查询的索引加速。 */
    private Pred findIndexableEq(Pred p, Table t) {
        if (p == null) {
            return null;
        }
        switch (p.kind) {
            case CMP:
                if (p.op.equals("=") && p.left.colRef != null && p.right.literal != null
                        && t.indexCols.contains(bare(p.left.colRef))) {
                    return p;
                }
                return null;
            case AND:
                Pred a = findIndexableEq(p.left2, t);
                return a != null ? a : findIndexableEq(p.right2, t);
            default:
                return null;
        }
    }

    /** 对给定的组合行集合按谓词过滤（多表连接 / JOIN 后使用）。 */
    private List<Object[]> filterRows(Combined c, List<Object[]> rows, Pred p) throws EngineException {
        if (p == null) {
            return rows;
        }
        List<Object[]> out = new ArrayList<>();
        for (Object[] r : rows) {
            if (evaluatePred(c, r, p)) {
                out.add(r);
            }
        }
        return out;
    }

    /** 计算谓词树（WHERE / HAVING / ON / CHECK 共用）。 */
    private boolean evaluatePred(Combined c, Object[] row, Pred p) throws EngineException {
        switch (p.kind) {
            case AND -> {
                return evaluatePred(c, row, p.left2) && evaluatePred(c, row, p.right2);
            }
            case OR -> {
                return evaluatePred(c, row, p.left2) || evaluatePred(c, row, p.right2);
            }
            case NOT -> {
                return !evaluatePred(c, row, p.child);
            }
            case CMP -> {
                Object lv = operandValue(c, row, p.left);
                Object rv = operandValue(c, row, p.right);
                if (lv == null || rv == null) {
                    return p.op.equals("<>");
                }
                return applyOp(p.op, lv, rv);
            }
            case LIKE -> {
                Object lv = operandValue(c, row, p.left);
                boolean m = likeMatch(lv, p.pattern);
                return p.negated ? !m : m;
            }
            case IN -> {
                Object lv = operandValue(c, row, p.left);
                boolean found;
                if (p.inSubquery != null) {
                    found = false;
                    for (Object v : inSubqueryValues(p.inSubquery)) {
                        if (eq(lv, v)) {
                            found = true;
                            break;
                        }
                    }
                } else {
                    found = false;
                    for (Object v : p.values) {
                        if (eq(lv, v)) {
                            found = true;
                            break;
                        }
                    }
                }
                return p.negated ? !found : found;
            }
            case EXISTS -> {
                if (p.subquery == null) {
                    return false;
                }
                QueryResult qr = execPlan(currentSession, p.subquery);
                boolean nonEmpty = !qr.rows().isEmpty();
                return p.negated ? !nonEmpty : nonEmpty;
            }
            case BETWEEN -> {
                Object lv = operandValue(c, row, p.left);
                // SQL 语义：操作数为 NULL 时 BETWEEN / NOT BETWEEN 在 WHERE 中均视为不命中（false）。
                if (lv == null) {
                    return false;
                }
                Object lo = operandValue(c, row, p.lo);
                Object hi = operandValue(c, row, p.hi);
                boolean b = cmp(lv, lo) >= 0 && cmp(lv, hi) <= 0;
                return p.negated ? !b : b;
            }
            case ISNULL -> {
                Object lv = operandValue(c, row, p.left);
                boolean isNull = (lv == null);
                return p.negated ? !isNull : isNull;
            }
            default -> {
                return false;
            }
        }
    }

    /** SQL LIKE 模式匹配（% 任意串，_ 任意单字符；其余字符按字面，正则元字符转义）。 */
    private static boolean likeMatch(Object val, String pattern) {
        if (val == null) {
            return false;
        }
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < pattern.length(); i++) {
            char ch = pattern.charAt(i);
            if (ch == '%') {
                regex.append(".*");
            } else if (ch == '_') {
                regex.append('.');
            } else if ("\\.[]{}()*+-?^$|".indexOf(ch) >= 0) {
                regex.append('\\').append(ch);
            } else {
                regex.append(ch);
            }
        }
        regex.append("$");
        try {
            return java.util.regex.Pattern.matches(regex.toString(), String.valueOf(val));
        } catch (Exception e) {
            return false;
        }
    }

    /** 值相等比较（数值按 double 比较，其余按 equals；两侧均 null 视为相等）。 */
    private static boolean eq(Object a, Object b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        if (a instanceof Number && b instanceof Number) {
            return ((Number) a).doubleValue() == ((Number) b).doubleValue();
        }
        return a.equals(b);
    }

    private Object operandValue(Combined combined, Object[] row, Operand op) throws EngineException {
        if (op.colRef != null) {
            return row[combined.resolve(op.colRef)];
        }
        if (op.subquery != null) {
            return scalarValue(op.subquery);
        }
        return op.literal;
    }

    /** 执行标量子查询，返回第一行第一列（空集/空行返回 null）。 */
    private Object scalarValue(SelectPlan plan) throws EngineException {
        QueryResult qr = execPlan(currentSession, plan);
        if (qr.rows().isEmpty()) {
            return null;
        }
        List<Object> r = qr.rows().get(0);
        return r.isEmpty() ? null : r.get(0);
    }

    /** 执行 IN 子查询，收集其第一列的值集合用于成员判定。 */
    private java.util.Set<Object> inSubqueryValues(SelectPlan plan) throws EngineException {
        QueryResult qr = execPlan(currentSession, plan);
        java.util.Set<Object> set = new java.util.HashSet<>();
        for (List<Object> r : qr.rows()) {
            if (!r.isEmpty()) {
                set.add(r.get(0));
            }
        }
        return set;
    }

    private boolean applyOp(String op, Object a, Object b) {
        if (a == null || b == null) {
            return op.equals("<>");
        }
        int c = cmp(a, b);
        return switch (op) {
            case "=" -> c == 0;
            case "<>" -> c != 0;
            case "<" -> c < 0;
            case "<=" -> c <= 0;
            case ">" -> c > 0;
            case ">=" -> c >= 0;
            default -> false;
        };
    }

    private static int cmp(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) {
            return Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue());
        }
        if (a instanceof Boolean && b instanceof Boolean) {
            return Boolean.compare((Boolean) a, (Boolean) b);
        }
        if (a instanceof String && b instanceof String) {
            return ((String) a).compareTo((String) b);
        }
        return a.toString().compareTo(b.toString());
    }

    /** 按显式列引用（列名或位置序号）对组合行排序。 */
    private List<Object[]> orderByRefs(Combined combined, List<Object[]> rows, List<String> refs, List<Boolean> asc)
            throws EngineException {
        List<Integer> keys = new ArrayList<>();
        for (String ref : refs) {
            if (ref.matches("\\d+")) {
                int idx = Integer.parseInt(ref) - 1;
                if (idx < 0 || idx >= combined.outCols.size()) {
                    throw syn("ORDER BY 位置越界: " + ref);
                }
                keys.add(idx);
            } else {
                keys.add(combined.resolve(ref));
            }
        }
        List<Object[]> copy = new ArrayList<>(rows);
        copy.sort((r1, r2) -> {
            for (int i = 0; i < keys.size(); i++) {
                int c = cmp(r1[keys.get(i)], r2[keys.get(i)]);
                if (c != 0) {
                    return asc.get(i) ? c : -c;
                }
            }
            return 0;
        });
        return copy;
    }

    /** 对 UNION 合并后的结果行按列标签（或位置序号）排序。 */
    private static List<List<Object>> orderRowsByLabels(List<ColumnMeta> cols, List<List<Object>> rows,
                                                       List<String> refs, List<Boolean> asc) throws EngineException {
        List<Integer> keys = new ArrayList<>();
        for (String ref : refs) {
            if (ref.matches("\\d+")) {
                int idx = Integer.parseInt(ref) - 1;
                if (idx < 0 || idx >= cols.size()) {
                    throw syn("ORDER BY 位置越界: " + ref);
                }
                keys.add(idx);
            } else {
                String bare = bareColName(ref);
                int found = -1;
                for (int i = 0; i < cols.size(); i++) {
                    if (cols.get(i).name().equalsIgnoreCase(ref) || cols.get(i).name().equalsIgnoreCase(bare)) {
                        found = i;
                        break;
                    }
                }
                if (found < 0) {
                    throw syn("ORDER BY 未知列: " + bare);
                }
                keys.add(found);
            }
        }
        List<List<Object>> copy = new ArrayList<>(rows);
        copy.sort((r1, r2) -> {
            for (int i = 0; i < keys.size(); i++) {
                int c = cmp(r1.get(keys.get(i)), r2.get(keys.get(i)));
                if (c != 0) {
                    return asc.get(i) ? c : -c;
                }
            }
            return 0;
        });
        return copy;
    }

    /** 非聚合场景：把一行组合行按 SELECT 列表投影成输出行（含 STAR 展开）。 */
    private List<Object> projectRow(Combined combined, Object[] row, List<SelectItem> items)
            throws EngineException {
        List<Object> out = new ArrayList<>();
        for (SelectItem it : items) {
            if (it.kind == SelectKind.STAR) {
                if (it.ref == null) {
                    for (Object v : row) {
                        out.add(v);
                    }
                } else {
                    for (ColumnDef cd : combined.outCols) {
                        if (qualifierMatches(cd.name, it.ref)) {
                            out.add(row[combined.outCols.indexOf(cd)]);
                        }
                    }
                }
            } else if (it.kind == SelectKind.COL) {
                out.add(row[combined.resolve(it.ref)]);
            } else if (it.kind == SelectKind.SUBQ) {
                // 标量子查询项：每行独立执行一次子查询取首格（非关联子查询语义）
                out.add(currentSession != null && it.subq != null ? scalarValue(it.subq) : null);
            } else if (it.kind == SelectKind.LITERAL) {
                out.add(it.literal);
            } else {
                out.add(null); // 聚合项不应出现在非聚合路径；兜底
            }
        }
        return out;
    }

    /** 去重（DISTINCT）：按整行值比较。 */
    private static List<List<Object>> dedupe(List<List<Object>> rows) {
        List<List<Object>> out = new ArrayList<>();
        for (List<Object> r : rows) {
            boolean dup = false;
            for (List<Object> e : out) {
                if (e.size() == r.size()) {
                    boolean same = true;
                    for (int i = 0; i < r.size(); i++) {
                        if (!eq(r.get(i), e.get(i))) {
                            same = false;
                            break;
                        }
                    }
                    if (same) {
                        dup = true;
                        break;
                    }
                }
            }
            if (!dup) {
                out.add(r);
            }
        }
        return out;
    }

    /** 分组 + 聚合：返回每个分组的输出行。 */
    private List<List<Object>> aggregate(Combined combined, List<Object[]> rows,
                                         List<SelectItem> items, List<String> groupCols) throws EngineException {
        List<List<Object[]>> groups = new ArrayList<>();
        if (groupCols.isEmpty()) {
            groups.add(rows); // 整表作为一个分组
        } else {
            List<Integer> gIdx = new ArrayList<>();
            for (String gc : groupCols) {
                gIdx.add(combined.resolve(gc));
            }
            Map<List<Object>, List<Object[]>> map = new LinkedHashMap<>();
            for (Object[] r : rows) {
                List<Object> key = new ArrayList<>();
                for (int i : gIdx) {
                    key.add(r[i]);
                }
                map.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
            }
            groups.addAll(map.values());
        }
        List<List<Object>> out = new ArrayList<>();
        for (List<Object[]> g : groups) {
            List<Object> outRow = new ArrayList<>();
            for (SelectItem it : items) {
                switch (it.kind) {
                    case STAR -> {
                        if (it.ref == null) {
                            for (Object v : g.get(0)) {
                                outRow.add(v);
                            }
                        } else {
                            for (ColumnDef cd : combined.outCols) {
                                if (qualifierMatches(cd.name, it.ref)) {
                                    outRow.add(g.get(0)[combined.outCols.indexOf(cd)]);
                                }
                            }
                        }
                    }
                    case COL -> outRow.add(g.get(0)[combined.resolve(it.ref)]);
                    case COUNT -> {
                        long n;
                        if (it.ref.equals("*")) {
                            n = g.size();
                        } else {
                            int ci = combined.resolve(it.ref);
                            long c = 0;
                            for (Object[] r : g) {
                                if (r[ci] != null) {
                                    c++;
                                }
                            }
                            n = c;
                        }
                        outRow.add(n);
                    }
                    case SUM -> {
                        int ci = combined.resolve(it.ref);
                        double s = 0;
                        long n = 0;
                        for (Object[] r : g) {
                            Object v = r[ci];
                            if (v != null) {
                                s += ((Number) toNum(v)).doubleValue();
                                n++;
                            }
                        }
                        outRow.add(n == 0 ? 0L : s);
                    }
                    case AVG -> {
                        int ci = combined.resolve(it.ref);
                        double s = 0;
                        long n = 0;
                        for (Object[] r : g) {
                            Object v = r[ci];
                            if (v != null) {
                                s += ((Number) toNum(v)).doubleValue();
                                n++;
                            }
                        }
                        outRow.add(n == 0 ? 0.0 : s / n);
                    }
                    case MIN -> {
                        int ci = combined.resolve(it.ref);
                        Object best = null;
                        for (Object[] r : g) {
                            Object v = r[ci];
                            if (v != null && (best == null || cmp(v, best) < 0)) {
                                best = v;
                            }
                        }
                        outRow.add(best);
                    }
                    case MAX -> {
                        int ci = combined.resolve(it.ref);
                        Object best = null;
                        for (Object[] r : g) {
                            Object v = r[ci];
                            if (v != null && (best == null || cmp(v, best) > 0)) {
                                best = v;
                            }
                        }
                        outRow.add(best);
                    }
                    case SUBQ -> outRow.add(
                            currentSession != null && it.subq != null ? scalarValue(it.subq) : null);
                    case LITERAL -> outRow.add(it.literal);
                }
            }
            out.add(outRow);
        }
        return out;
    }

    /** 用 SELECT 输出列构造临时 Combined，使 HAVING 能引用聚合结果或 SELECT 别名/分组列。 */
    private List<List<Object>> filterAggregate(Combined combined, List<SelectItem> items,
                                               List<List<Object>> outRows, Pred having) throws EngineException {
        Combined oc = new Combined();
        List<String> labels = outputLabels(items);
        for (int i = 0; i < labels.size(); i++) {
            oc.outCols.add(new ColumnDef(labels.get(i), ColumnType.VARCHAR, true, false, null));
            oc.src.add(new int[]{i, i});
        }
        List<List<Object>> out = new ArrayList<>();
        for (List<Object> r : outRows) {
            Object[] arr = r.toArray();
            try {
                if (evaluatePred(oc, arr, having)) {
                    out.add(r);
                }
            } catch (EngineException ignore) {
                // HAVING 引用了无法在输出列中解析的列（如未选中的基表列）：按不匹配处理
            }
        }
        return out;
    }

    private List<String> outputLabels(List<SelectItem> items) {
        List<String> labels = new ArrayList<>();
        for (SelectItem it : items) {
            if (it.kind == SelectKind.STAR) {
                labels.add(it.ref == null ? "*" : it.ref + ".*");
            } else if (it.kind == SelectKind.COL) {
                labels.add(it.alias != null ? it.alias : it.ref);
            } else if (it.kind == SelectKind.LITERAL) {
                labels.add(it.alias != null ? it.alias : literalLabel(it.literal));
            } else {
                labels.add(aggLabel(it));
            }
        }
        return labels;
    }

    private static String aggLabel(SelectItem it) {
        if (it.alias != null) {
            return it.alias;
        }
        return switch (it.kind) {
            case COUNT -> "count(" + it.ref + ")";
            case SUM -> "sum(" + it.ref + ")";
            case AVG -> "avg(" + it.ref + ")";
            case MIN -> "min(" + it.ref + ")";
            case MAX -> "max(" + it.ref + ")";
            case LITERAL -> literalLabel(it.literal);
            case SUBQ -> it.alias != null ? it.alias : "EXPR";
            default -> it.ref;
        };
    }

    /** 字面量 SELECT 项的列标签：优先别名，否则取字面量文本（如 `1` / `x` / `NULL`）。 */
    private static String literalLabel(Object v) {
        if (v == null) {
            return "NULL";
        }
        if (v instanceof Boolean b) {
            return b ? "TRUE" : "FALSE";
        }
        return String.valueOf(v);
    }

    /** 字面量 SELECT 项的列类型，由实际值推导。 */
    private static ColumnType columnTypeOfLiteral(Object v) {
        if (v == null) {
            return ColumnType.VARCHAR;
        }
        if (v instanceof Boolean) {
            return ColumnType.BOOLEAN;
        }
        if (v instanceof Double) {
            return ColumnType.DOUBLE;
        }
        if (v instanceof Number) {
            return ColumnType.BIGINT;
        }
        return ColumnType.VARCHAR;
    }

    /** HAVING 中聚合引用的规范化列名（与 aggLabel 对齐）。 */
    private static String aggRefName(String fn, String ref) {
        return fn.toLowerCase() + "(" + ref + ")";
    }

    private static boolean isAggFn(String name) {
        return switch (name.toUpperCase()) {
            case "COUNT", "SUM", "AVG", "MIN", "MAX" -> true;
            default -> false;
        };
    }

    /** SELECT 列表中以裸字面量开头时：TRUE / FALSE / NULL 关键字也视为字面量（其余 IDENT 仍是列引用）。 */
    private static boolean isLiteralKeyword(String w) {
        return switch (w.toUpperCase()) {
            case "TRUE", "FALSE", "NULL" -> true;
            default -> false;
        };
    }

    /** 构造最终查询结果（含 STAR 展开、聚合列类型）。 */
    private QueryResult buildResult(Combined combined, List<SelectItem> items, List<List<Object>> outRows)
            throws EngineException {
        List<ColumnMeta> cols = new ArrayList<>();
        for (SelectItem it : items) {
            if (it.kind == SelectKind.STAR) {
                // 结果列标签用【裸列名】（真 MySQL 行为）：内部限定名 t.col / db.t.col → col。
                // 若带别名前缀，DataGrip 表编辑器会生成 UPDATE ... SET t.`t.point` 这类坏语句。
                if (it.ref == null) {
                    for (ColumnDef cd : combined.outCols) {
                        cols.add(new ColumnMeta(bareColName(cd.name), cd.type, cd.nullable, cd.pk));
                    }
                } else {
                    for (ColumnDef cd : combined.outCols) {
                        if (qualifierMatches(cd.name, it.ref)) {
                            cols.add(new ColumnMeta(bareColName(cd.name), cd.type, cd.nullable, cd.pk));
                        }
                    }
                }
            } else if (it.kind == SelectKind.COL) {
                int idx = combined.resolve(it.ref);
                ColumnDef cd = combined.outCols.get(idx);
                String label = it.alias != null ? it.alias : bareColName(it.ref);
                cols.add(new ColumnMeta(label, cd.type, cd.nullable, cd.pk));
            } else if (it.kind == SelectKind.LITERAL) {
                String label = it.alias != null ? it.alias : literalLabel(it.literal);
                cols.add(new ColumnMeta(label, columnTypeOfLiteral(it.literal), true, false));
            } else if (it.kind == SelectKind.SUBQ) {
                // 标量子查询作为 SELECT 列表项：列名优先别名，否则取子查询首列名；
                // 列类型由子查询首列元信息推导（避免 name=null 触发列定义编码 NPE）。
                String label = it.alias != null ? it.alias : "EXPR";
                ColumnType ct = ColumnType.VARCHAR;
                if (currentSession != null && it.subq != null) {
                    try {
                        QueryResult sq = execPlan(currentSession, it.subq);
                        if (!sq.columns().isEmpty()) {
                            label = it.alias != null ? it.alias : sq.columns().get(0).name();
                            ct = sq.columns().get(0).type();
                        }
                    } catch (EngineException ignore) {
                        // 子查询执行失败时退化为 VARCHAR/EXPR，沿用外层兜底
                    }
                }
                cols.add(new ColumnMeta(label, ct, true, false));
            } else {
                ColumnType ct = it.kind == SelectKind.COUNT ? ColumnType.BIGINT : ColumnType.DOUBLE;
                cols.add(new ColumnMeta(aggLabel(it), ct, true, false));
            }
        }
        return QueryResult.ofResultSet(cols, outRows);
    }

    /** 结果列标签用裸列名（真 MySQL 行为）：内部限定名 t.col / db.t.col → col。 */
    private static String bareColName(String ref) {
        int d = ref.lastIndexOf('.');
        return d < 0 ? ref : ref.substring(d + 1);
    }

    /** 限定星号匹配：列名形如 `alias.col` 或 `db.alias.col`，q 为表别名/表名。 */
    private static boolean qualifierMatches(String colName, String q) {
        int d = colName.indexOf('.');
        if (d < 0) {
            return false;
        }
        String prefix = colName.substring(0, d);
        return prefix.equals(q) || prefix.endsWith("." + q);
    }

    // ===================== 行构建 / 工具 =====================

    private Object[] buildRow(Table t, List<String> colNames, List<Token> vals) throws EngineException {
        Object[] row = new Object[t.cols.size()];
        if (colNames == null) {
            if (vals.size() != t.cols.size()) {
                throw new EngineException(ErrorCode.SYNTAX_ERROR,
                        "INSERT 值数量不匹配: 期望 " + t.cols.size() + " 实际 " + vals.size());
            }
            for (int i = 0; i < t.cols.size(); i++) {
                row[i] = convert(t.cols.get(i), vals.get(i));
            }
        } else {
            if (vals.size() != colNames.size()) {
                throw new EngineException(ErrorCode.SYNTAX_ERROR, "INSERT 列/值数量不一致");
            }
            for (int i = 0; i < colNames.size(); i++) {
                int ci = t.indexOf(colNames.get(i));
                if (ci < 0) {
                    throw new EngineException(ErrorCode.UNKNOWN_COLUMN, "未知列: " + colNames.get(i));
                }
                row[ci] = convert(t.cols.get(ci), vals.get(i));
            }
        }
        // AUTO_INCREMENT：自增列未显式赋值时自动分配下一个值；显式赋值时抬高计数器。
        // 必须放在“非空检查”之前，否则主键自增列（NOT NULL）会因 null 被提前拒绝。
        int ai = autoIncColumnIndex(t);
        if (ai >= 0) {
            if (row[ai] == null) {
                t.autoIncValue++;
                row[ai] = coerce(t.cols.get(ai), t.autoIncValue);
            } else {
                long v = ((Number) toNum(row[ai])).longValue();
                if (v > t.autoIncValue) {
                    t.autoIncValue = v;
                }
            }
        }
        for (int i = 0; i < t.cols.size(); i++) {
            ColumnDef cd = t.cols.get(i);
            if (row[i] == null) {
                if (cd.def != null) {
                    row[i] = cd.def;
                } else if (!cd.nullable) {
                    throw new EngineException(ErrorCode.TYPE_MISMATCH, "列 " + cd.name + " 不允许 NULL");
                }
            }
        }
        for (int i = 0; i < t.cols.size(); i++) {
            if (t.cols.get(i).pk) {
                for (Object[] ex : t.rows) {
                    if (ex != null && Objects.equals(ex[i], row[i])) {
                        throw new EngineException(ErrorCode.DUPLICATE_KEY, "主键冲突: " + row[i]);
                    }
                }
            }
        }
        // 约束：唯一（列级 / 表级）、CHECK、外键
        checkRowConstraints(t, row, null);
        return row;
    }

    /** 返回表中声明 AUTO_INCREMENT 的列下标（-1 表示无）。 */
    private static int autoIncColumnIndex(Table t) {
        for (int i = 0; i < t.cols.size(); i++) {
            if (t.cols.get(i).autoIncrement) {
                return i;
            }
        }
        return -1;
    }

    /** 校验一行是否满足唯一 / CHECK / 外键约束；ignore 为非 null 时跳过它（UPDATE 原地改自己）。 */
    private void checkRowConstraints(Table t, Object[] row, Object[] ignore) throws EngineException {
        // 列级唯一
        for (int i = 0; i < t.cols.size(); i++) {
            if (t.cols.get(i).unique) {
                for (Object[] ex : t.rows) {
                    if (ex == null || ex == ignore) {
                        continue;
                    }
                    if (eq(ex[i], row[i])) {
                        throw new EngineException(ErrorCode.DUPLICATE_KEY, "唯一约束冲突: " + t.cols.get(i).name);
                    }
                }
            }
        }
        // 表级唯一（单列或多列组合）
        for (List<String> uq : t.uniqueKeys) {
            List<Integer> idxs = new ArrayList<>();
            for (String c : uq) {
                idxs.add(t.indexOf(c));
            }
            for (Object[] ex : t.rows) {
                if (ex == null || ex == ignore) {
                    continue;
                }
                boolean same = true;
                for (int i : idxs) {
                    if (!eq(ex[i], row[i])) {
                        same = false;
                        break;
                    }
                }
                if (same) {
                    throw new EngineException(ErrorCode.DUPLICATE_KEY, "唯一约束冲突: " + uq);
                }
            }
        }
        // 表级 CHECK
        if (!t.tableChecks.isEmpty()) {
            Combined c = singleTableCombined(t);
            for (Pred chk : t.tableChecks) {
                if (!evaluatePred(c, row, chk)) {
                    throw new EngineException(ErrorCode.TYPE_MISMATCH, "CHECK 约束失败");
                }
            }
        }
        // 外键
        checkForeignKeys(t, row);
    }

    private void checkForeignKeys(Table t, Object[] row) throws EngineException {
        if (t.foreignKeys.isEmpty()) {
            return;
        }
        Database db = dbOf(t);
        if (db == null) {
            return;
        }
        for (FkDef fk : t.foreignKeys) {
            int ci = t.indexOf(fk.col);
            Object v = row[ci];
            if (v == null) {
                continue;
            }
            Table parent = db.tables.get(fk.refTable);
            if (parent == null) {
                throw new EngineException(ErrorCode.SERVER_ERROR, "外键父表不存在: " + fk.refTable);
            }
            int pi = parent.indexOf(fk.refCol);
            boolean found = false;
            for (Object[] pr : parent.rows) {
                if (pr != null && eq(pr[pi], v)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new EngineException(ErrorCode.FK_VIOLATION,
                        "外键约束失败: " + fk.col + " -> " + fk.refTable + "." + fk.refCol);
            }
        }
    }

    /** 查找包含给定表的库（用于外键父表定位；表名跨库重复时取首个，足够课程场景）。 */
    private Database dbOf(Table t) {
        for (Database d : databases.values()) {
            if (d.tables.get(t.name) == t) {
                return d;
            }
        }
        return null;
    }

    private Object convert(ColumnDef cd, Token vt) throws EngineException {
        if (vt.type == TokenType.STRING) {
            return vt.text;
        }
        if (vt.type == TokenType.NUMBER) {
            return switch (cd.type) {
                case INT -> Integer.parseInt(vt.text);
                case BIGINT -> Long.parseLong(vt.text);
                case DOUBLE -> Double.parseDouble(vt.text);
                case DECIMAL -> vt.text;
                case BOOLEAN -> !vt.text.equals("0");
                default -> vt.text;
            };
        }
        if (vt.type == TokenType.IDENT) {
            return switch (vt.text.toUpperCase()) {
                case "TRUE" -> Boolean.TRUE;
                case "FALSE" -> Boolean.FALSE;
                case "NULL" -> null;
                default -> throw new EngineException(ErrorCode.SYNTAX_ERROR, "无法解析的字面量: " + vt.text);
            };
        }
        throw new EngineException(ErrorCode.SYNTAX_ERROR, "无法解析的 INSERT 值");
    }

    private Object parseLiteral(Token vt) throws EngineException {
        if (vt.type == TokenType.STRING) {
            return vt.text;
        }
        if (vt.type == TokenType.NUMBER) {
            return parseNumber(vt.text);
        }
        if (vt.type == TokenType.IDENT) {
            return switch (vt.text.toUpperCase()) {
                case "TRUE" -> Boolean.TRUE;
                case "FALSE" -> Boolean.FALSE;
                case "NULL" -> null;
                default -> vt.text;
            };
        }
        throw new EngineException(ErrorCode.SYNTAX_ERROR, "DEFAULT 值无法解析");
    }

    private static Number parseNumber(String s) {
        if (s.contains(".")) {
            return Double.parseDouble(s);
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return Long.parseLong(s);
        }
    }

    private static String bare(String qualified) {
        int dot = qualified.lastIndexOf('.');
        return dot < 0 ? qualified : qualified.substring(dot + 1);
    }

    private Database requireDb(SessionContext session) throws EngineException {
        String db = session.getCurrentDatabase();
        if (db == null) {
            throw new EngineException(ErrorCode.UNKNOWN_DATABASE, "未选择数据库，请先 USE db");
        }
        Database d = databases.get(db);
        if (d == null) {
            throw new EngineException(ErrorCode.UNKNOWN_DATABASE, "库不存在: " + db);
        }
        return d;
    }

    private Table requireTable(SessionContext session, String name) throws EngineException {
        Database db = requireDb(session);
        Table t = db.tables.get(name);
        if (t != null) {
            return t;
        }
        String body = db.views.get(name);
        if (body != null) {
            return materializeView(session, name, body);
        }
        throw new EngineException(ErrorCode.UNKNOWN_TABLE, "表不存在: " + name);
    }

    /** 支持 `db.table` 限定写法：含点号则按前缀取库，否则按当前库解析；
     *  表不存在时尝试按视图物化（供 SELECT / DESC / SHOW 引用）。 */
    private Table requireTableQualified(SessionContext session, String name) throws EngineException {
        int dot = name.indexOf('.');
        if (dot < 0) {
            return requireTable(session, name);
        }
        String dbName = name.substring(0, dot);
        String tname = name.substring(dot + 1);
        Database db = databases.get(dbName);
        if (db == null) {
            throw new EngineException(ErrorCode.UNKNOWN_DATABASE, "库不存在: " + dbName);
        }
        Table t = db.tables.get(tname);
        if (t != null) {
            return t;
        }
        String body = db.views.get(tname);
        if (body != null) {
            return materializeView(session, tname, body);
        }
        throw new EngineException(ErrorCode.UNKNOWN_TABLE, "表不存在: " + tname);
    }

    private Table requireTable(String dbName, String name) throws EngineException {
        Database d = databases.get(dbName);
        if (d == null) {
            throw new EngineException(ErrorCode.UNKNOWN_DATABASE, "库不存在: " + dbName);
        }
        return requireTable(d, name);
    }

    private Table requireTable(Database db, String name) throws EngineException {
        Table t = db.tables.get(name);
        if (t == null) {
            throw new EngineException(ErrorCode.UNKNOWN_TABLE, "表不存在: " + name);
        }
        return t;
    }

    private static EngineException syn(String msg) {
        return new EngineException(ErrorCode.SYNTAX_ERROR, msg);
    }

    // ===================== 持久化 =====================

    private void loadAll() throws EngineException {
        if (!Files.exists(dataRoot)) {
            return;
        }
        try (var dbDirs = Files.list(dataRoot)) {
            // 过滤掉非库目录：dataRoot 下还会放 tablespaces/ 目录（表空间）与内部文件，
            // 它们不是数据库，若当成库加载会出现在 SHOW DATABASES / information_schema.schemata 里。
            for (Path dbDir : (Iterable<Path>) dbDirs.filter(p -> Files.isDirectory(p)
                    && !p.getFileName().toString().equals("tablespaces")
                    && !p.getFileName().toString().startsWith("_"))::iterator) {
                Database db = new Database(dbDir.getFileName().toString());
                try (var files = Files.list(dbDir)) {
                    for (Path file : (Iterable<Path>) files.filter(f -> {
                        String n = f.getFileName().toString();
                        // 跳过 _views.json 等内部元数据文件，它们不是表文件，强行解析会因缺 columns 而 NPE
                        return n.endsWith(".json") && !n.startsWith("_");
                    })::iterator) {
                        loadTable(file, db);
                    }
                }
                databases.put(db.name, db);
                try {
                    loadViews(db);
                } catch (EngineException ve) {
                    throw ve;
                }
            }
        } catch (IOException e) {
            throw new EngineException(ErrorCode.SERVER_ERROR, "加载数据失败: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void loadTable(Path file, Database db) throws EngineException {
        try {
            Map<String, Object> obj = JsonUtils.mapper().readValue(file.toFile(), Map.class);
            // 防御：缺 name 字段的不是表文件（如 _views.json），直接跳过，避免整库加载失败
            if (obj.get("name") == null) {
                return;
            }
            String tname = (String) obj.get("name");
            Table t = new Table(tname);
            if (obj.get("comment") != null) {
                t.comment = String.valueOf(obj.get("comment"));
            }
            List<Map<String, Object>> cols = (List<Map<String, Object>>) obj.get("columns");
            for (Map<String, Object> c : cols) {
                String cn = (String) c.get("name");
                ColumnType ct = ColumnType.fromName((String) c.get("type"));
                boolean nullable = Boolean.TRUE.equals(c.get("nullable"));
                boolean pk = Boolean.TRUE.equals(c.get("pk"));
                Object def = c.get("def");
                boolean autoInc = Boolean.TRUE.equals(c.get("autoIncrement"));
                boolean uniq = Boolean.TRUE.equals(c.get("unique"));
                t.cols.add(new ColumnDef(cn, ct, nullable, pk, def, autoInc, uniq,
                        c.get("comment") == null ? "" : String.valueOf(c.get("comment"))));
            }
            List<List<Object>> rows = (List<List<Object>>) obj.get("rows");
            if (rows != null) {
                for (List<Object> r : rows) {
                    t.rows.add(r.toArray());
                }
            }
            Object idxObj = obj.get("indexes");
            if (idxObj instanceof List) {
                for (Object io : (List<?>) idxObj) {
                    @SuppressWarnings("unchecked")
                    List<String> pair = (List<String>) io;
                    t.indexNames.put(pair.get(0), pair.get(1));
                    t.indexCols.add(pair.get(1));
                }
            }
            Object aiObj = obj.get("autoIncValue");
            if (aiObj instanceof Number) {
                t.autoIncValue = ((Number) aiObj).longValue();
            }
            Object ukObj = obj.get("uniqueKeys");
            if (ukObj instanceof List) {
                for (Object uo : (List<?>) ukObj) {
                    @SuppressWarnings("unchecked")
                    List<String> u = (List<String>) uo;
                    t.uniqueKeys.add(u);
                }
            }
            Object fkObj = obj.get("foreignKeys");
            if (fkObj instanceof List) {
                for (Object fo : (List<?>) fkObj) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> fm = (Map<String, Object>) fo;
                    t.foreignKeys.add(new FkDef((String) fm.get("col"),
                            (String) fm.get("refTable"), (String) fm.get("refCol")));
                }
            }
            Object chkObj = obj.get("checks");
            if (chkObj instanceof List) {
                for (Object co : (List<?>) chkObj) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> cm = (Map<String, Object>) co;
                    t.tableChecks.add(mapToPred(cm));
                }
            }
            Object tsObj = obj.get("tablespace");
            t.tablespace = tsObj == null ? TablespaceManager.DEFAULT_TS : (String) tsObj;
            db.tables.put(tname, t);
            // 登记到表空间（若元数据里没有就按表自带的 tablespace 补登记）
            if (tspaces != null) {
                tspaces.assign(db.name + "." + tname, t.tablespace);
            }
            // 三段式记录文件是主存储：它保留了槽位置与删除标记，
            // 上面从 JSON 读到的行只是兜底（旧数据目录首次升级时会走到这条路径）。
            loadSlotFile(db.name, t);
        } catch (Exception e) {
            throw new EngineException(ErrorCode.SERVER_ERROR, "加载表失败 " + file.getFileName() + ": " + e.getMessage());
        }
    }

    /**
     * 从三段式记录文件恢复行：按槽号逐个还原，空位还原成 {@code null}，
     * 被标记删除的槽记入 {@code deletedSlots}。这样"删除只做标记"在重启后依然成立。
     */
    private void loadSlotFile(String dbName, Table t) {
        Path f = tspaces == null ? null : tspaces.fileOfIfExists(dbName, t.name);
        if (f == null) {
            t.slotFileReady = false;
            return;
        }
        try {
            RecordFileStore.Header h = RecordFileStore.readHeader(f);
            if (h == null) {
                t.slotFileReady = false;      // 旧格式的整表 JSON 数据文件：留给首次落盘时重建
                return;
            }
            char[] flags = RecordFileStore.readBitmap(f, h);
            List<Object[]> loaded = new ArrayList<>();
            t.deletedSlots.clear();
            for (int i = 0; i < h.slotCount; i++) {
                char fl = i < flags.length ? flags[i] : RecordFileStore.SLOT_EMPTY;
                if (fl == RecordFileStore.SLOT_USED) {
                    String txt = RecordFileStore.readSlotText(f, h, i);
                    loaded.add(txt.isEmpty() ? null : toRow(t, RecordFileStore.decodeRow(txt)));
                } else {
                    loaded.add(null);
                    if (fl == RecordFileStore.SLOT_DELETED) {
                        t.deletedSlots.put(i, true);
                    }
                }
            }
            t.rows.clear();
            t.rows.addAll(loaded);
            t.fileHeader = h;
            t.slotSize = h.slotSize;
            t.slotCount = h.slotCount;
            t.slotFileReady = true;
        } catch (Exception e) {
            t.slotFileReady = false;          // 读失败就退回 JSON 的行，下次落盘时重建文件
        }
    }

    private void persistDatabase(String dbName) throws EngineException {
        try {
            Files.createDirectories(dataRoot.resolve(dbName));
        } catch (IOException e) {
            throw new EngineException(ErrorCode.SERVER_ERROR, "建库落盘失败: " + e.getMessage());
        }
    }

    private void persistTable(String dbName, Table t) throws EngineException {
        try {
            Path dir = dataRoot.resolve(dbName);
            Files.createDirectories(dir);
            Map<String, Object> obj = tableJson(dbName, t);
            Files.write(dir.resolve(t.name + ".json"), JsonUtils.mapper().writeValueAsBytes(obj));
        } catch (IOException e) {
            throw new EngineException(ErrorCode.SERVER_ERROR, "落盘失败: " + e.getMessage());
        }
        // 三段式记录文件（格式信息区 / 记录空间分配表 / 记录存储空间）才是本表的主存储，
        // 上面那份 JSON 只作为可读副本与兼容旧工具用。
        syncSlotFile(dbName, t);
    }

    /** 表结构 + 有效行的 JSON（空位不写）。 */
    private Map<String, Object> tableJson(String dbName, Table t) {
        Map<String, Object> obj = new LinkedHashMap<>();
        obj.put("name", t.name);
        obj.put("tablespace", tsOf(dbName, t.name));
        if (t.comment != null && !t.comment.isEmpty()) {
            obj.put("comment", t.comment);
        }
        List<Map<String, Object>> cols = new ArrayList<>();
        for (ColumnDef c : t.cols) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", c.name);
            m.put("type", c.type.name());
            m.put("nullable", c.nullable);
            m.put("pk", c.pk);
            m.put("def", c.def);
            m.put("autoIncrement", c.autoIncrement);
            m.put("unique", c.unique);
            if (c.comment != null && !c.comment.isEmpty()) {
                m.put("comment", c.comment);
            }
            cols.add(m);
        }
        obj.put("columns", cols);
        List<List<Object>> rows = new ArrayList<>();
        for (Object[] r : t.rows) {
            if (r != null) {
                rows.add(Arrays.asList(r));
            }
        }
        obj.put("rows", rows);
        if (!t.indexNames.isEmpty()) {
            List<List<String>> idx = new ArrayList<>();
            for (Map.Entry<String, String> e : t.indexNames.entrySet()) {
                idx.add(List.of(e.getKey(), e.getValue()));
            }
            obj.put("indexes", idx);
        }
        if (t.autoIncValue != 0) {
            obj.put("autoIncValue", t.autoIncValue);
        }
        if (!t.uniqueKeys.isEmpty()) {
            obj.put("uniqueKeys", t.uniqueKeys);
        }
        if (!t.foreignKeys.isEmpty()) {
            List<Map<String, Object>> fks = new ArrayList<>();
            for (FkDef fk : t.foreignKeys) {
                Map<String, Object> fm = new LinkedHashMap<>();
                fm.put("col", fk.col);
                fm.put("refTable", fk.refTable);
                fm.put("refCol", fk.refCol);
                fks.add(fm);
            }
            obj.put("foreignKeys", fks);
        }
        if (!t.tableChecks.isEmpty()) {
            List<Object> checks = new ArrayList<>();
            for (Pred p : t.tableChecks) {
                checks.add(predToMap(p));
            }
            obj.put("checks", checks);
        }
        return obj;
    }

    /** 把谓词树序列化为可 JSON 化的 Map（用于 CHECK 约束持久化）。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> predToMap(Pred p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", p.kind.name());
        switch (p.kind) {
            case AND, OR -> {
                m.put("l", predToMap(p.left2));
                m.put("r", predToMap(p.right2));
            }
            case NOT -> m.put("c", predToMap(p.child));
            case CMP -> {
                m.put("op", p.op);
                m.put("l", operandToMap(p.left));
                m.put("r", operandToMap(p.right));
            }
            case LIKE -> {
                m.put("pat", p.pattern);
                m.put("neg", p.negated);
                m.put("l", operandToMap(p.left));
            }
            case IN -> {
                m.put("vals", p.values);
                m.put("neg", p.negated);
                m.put("l", operandToMap(p.left));
            }
            case BETWEEN -> {
                m.put("lo", operandToMap(p.lo));
                m.put("hi", operandToMap(p.hi));
                m.put("neg", p.negated);
                m.put("l", operandToMap(p.left));
            }
            case ISNULL -> {
                m.put("neg", p.negated);
                m.put("l", operandToMap(p.left));
            }
        }
        return m;
    }

    private static Map<String, Object> operandToMap(Operand o) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (o.colRef != null) {
            m.put("col", o.colRef);
        } else {
            m.put("lit", o.literal);
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Pred mapToPred(Map<String, Object> m) {
        Pred.Kind kind = Pred.Kind.valueOf((String) m.get("kind"));
        Pred p = new Pred();
        p.kind = kind;
        switch (kind) {
            case AND, OR -> {
                p.left2 = mapToPred((Map<String, Object>) m.get("l"));
                p.right2 = mapToPred((Map<String, Object>) m.get("r"));
            }
            case NOT -> p.child = mapToPred((Map<String, Object>) m.get("c"));
            case CMP -> {
                p.op = (String) m.get("op");
                p.left = mapToOperand((Map<String, Object>) m.get("l"));
                p.right = mapToOperand((Map<String, Object>) m.get("r"));
            }
            case LIKE -> {
                p.pattern = (String) m.get("pat");
                p.negated = Boolean.TRUE.equals(m.get("neg"));
                p.left = mapToOperand((Map<String, Object>) m.get("l"));
            }
            case IN -> {
                p.values = (List<Object>) m.get("vals");
                p.negated = Boolean.TRUE.equals(m.get("neg"));
                p.left = mapToOperand((Map<String, Object>) m.get("l"));
            }
            case BETWEEN -> {
                p.lo = mapToOperand((Map<String, Object>) m.get("lo"));
                p.hi = mapToOperand((Map<String, Object>) m.get("hi"));
                p.negated = Boolean.TRUE.equals(m.get("neg"));
                p.left = mapToOperand((Map<String, Object>) m.get("l"));
            }
            case ISNULL -> {
                p.negated = Boolean.TRUE.equals(m.get("neg"));
                p.left = mapToOperand((Map<String, Object>) m.get("l"));
            }
        }
        return p;
    }

    @SuppressWarnings("unchecked")
    private static Operand mapToOperand(Map<String, Object> m) {
        if (m.containsKey("col")) {
            return Operand.col((String) m.get("col"));
        }
        return Operand.lit(m.get("lit"));
    }

    /**
     * 把内存中的行同步到三段式记录文件。
     * <p>日常 DML 只把动过的槽记在 {@code t.dirtySlots} 里，这里对它们做<b>随机写</b>：
     * 插入写"槽内容 + 分配表字节"，更新只写"槽内容"（原地修改），删除只写"分配表字节"（数据不动）。
     * 只有建表、槽数扩展、槽大小扩容、OPTIMIZE 才需要整文件重写。</p>
     */
    private void syncSlotFile(String dbName, Table t) throws EngineException {
        if (tspaces == null) {
            return;
        }
        int slotSize = Math.max(computeSlotSize(t), t.slotSize);
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                Path f = tspaces.fileOf(dbName, t.name);   // 不存在则按"整数个区"预分配创建
                RecordFileStore.Header h = t.fileHeader;
                int needSlots = Math.max(RecordFileStore.DEFAULT_SLOTS, t.rows.size());
                // 目标槽数：以内存里声明的为准（OPTIMIZE 会把它调小来真正回收空位）
                int base = t.slotCount > 0 ? t.slotCount : (h == null ? 0 : h.slotCount);
                if (needSlots > base) {
                    // 一次多扩一些（至少 64 个、不少于现有槽数的 1/4），避免每插一行就重写一次整文件
                    needSlots = Math.max(needSlots, base + Math.max(64, base / 4));
                }
                boolean fullRewrite = !t.slotFileReady || h == null || !Files.exists(f)
                        || h.slotSize != slotSize || h.slotCount < needSlots;
                if (fullRewrite) {
                    h = rewriteSlotFile(f, t, slotSize, Math.max(base, needSlots));
                } else {
                    for (int slot : t.dirtySlots) {
                        if (slot < 0 || slot >= h.slotCount || slot >= t.rows.size()) {
                            continue;
                        }
                        Object[] row = t.rows.get(slot);
                        if (row != null) {
                            // 插入 / 更新：只覆写这一个槽的内容（更新即"原地修改"）
                            RecordFileStore.writeSlot(f, h, slot, RecordFileStore.encodeRow(row));
                        }
                        // 删除时数据区一个字节都不动，只把分配表这一格改成 '2'
                        RecordFileStore.writeFlag(f, h, slot, slotFlag(t, slot));
                    }
                    updateFileStats(t, h);
                    RecordFileStore.writeHeader(f, h);
                }
                t.fileHeader = h;
                t.slotSize = h.slotSize;
                t.slotCount = h.slotCount;
                t.slotFileReady = true;
                t.dirtySlots.clear();
                tspaces.reportUsed(dbName, t.name, h.fileSize);
                return;
            } catch (IOException e) {
                // 最常见的失败原因是"行内容超出槽大小"：把槽翻倍后整文件重写一次再试
                if (slotSize >= RecordFileStore.MAX_SLOT_SIZE) {
                    throw new EngineException(ErrorCode.SERVER_ERROR, "写入记录文件失败: " + e.getMessage());
                }
                slotSize = Math.min(RecordFileStore.MAX_SLOT_SIZE, slotSize * 2);
                t.slotFileReady = false;
            }
        }
        throw new EngineException(ErrorCode.SERVER_ERROR, "写入记录文件失败：记录过长");
    }

    /** 全量重写三段式文件（建表 / 扩展 / 扩容 / OPTIMIZE 时调用）。 */
    private RecordFileStore.Header rewriteSlotFile(Path f, Table t, int slotSize, int slots)
            throws IOException {
        RecordFileStore.Header h = new RecordFileStore.Header();
        h.slotSize = slotSize;
        h.slotCount = Math.max(slots, t.rows.size());
        h.recompute();
        char[] flags = new char[h.slotCount];
        byte[][] payloads = new byte[h.slotCount][];
        for (int i = 0; i < h.slotCount; i++) {
            Object[] row = i < t.rows.size() ? t.rows.get(i) : null;
            flags[i] = slotFlag(t, i);
            if (row != null) {
                payloads[i] = RecordFileStore.encodeRow(row);
            }
        }
        RecordFileStore.rewrite(f, h, flags, payloads);
        updateFileStats(t, h);
        RecordFileStore.writeHeader(f, h);
        return h;
    }

    /** 槽在分配表中的状态：'1' 有数据 / '2' 删除标记留下的空位 / '0' 从未使用。 */
    private static char slotFlag(Table t, int slot) {
        Object[] row = slot < t.rows.size() ? t.rows.get(slot) : null;
        if (row != null) {
            return RecordFileStore.SLOT_USED;
        }
        return t.deletedSlots.containsKey(slot) ? RecordFileStore.SLOT_DELETED : RecordFileStore.SLOT_EMPTY;
    }

    /** 重算格式信息区里的统计字段：有效记录数、第一个/最后一个记录位置、可复用空位数。 */
    private static void updateFileStats(Table t, RecordFileStore.Header h) {
        h.recordCount = 0;
        h.firstRecord = -1;
        h.lastRecord = -1;
        h.freeCount = 0;
        int n = Math.min(h.slotCount, t.rows.size());
        for (int i = 0; i < n; i++) {
            if (t.rows.get(i) != null) {
                h.recordCount++;
                if (h.firstRecord < 0) {
                    h.firstRecord = i;
                }
                h.lastRecord = i;
            } else {
                h.freeCount++;
            }
        }
        h.freeCount += Math.max(0, h.slotCount - n);
    }

    /** 按列定义估算一个记录位置的大小（定长槽，写不满补空格）。 */
    private static int computeSlotSize(Table t) {
        int size = 16;
        for (ColumnDef c : t.cols) {
            size += switch (c.type) {
                case INT, BIGINT -> 16;
                case DOUBLE, DECIMAL -> 24;
                case DATE, DATETIME -> 32;
                case BOOLEAN -> 8;
                default -> 128;   // VARCHAR / CHAR / TEXT 等变长类型
            };
            size += 4;            // 分隔符与引号
        }
        return Math.max(RecordFileStore.MIN_SLOT_SIZE, Math.min(RecordFileStore.MAX_SLOT_SIZE, size));
    }

    private void deleteDatabase(String dbName) throws EngineException {
        try {
            Path dir = dataRoot.resolve(dbName);
            if (Files.exists(dir)) {
                deleteRecursive(dir);
            }
        } catch (IOException e) {
            throw new EngineException(ErrorCode.SERVER_ERROR, "删库落盘失败: " + e.getMessage());
        }
    }

    private void deleteTable(String dbName, String tname) throws EngineException {
        try {
            Files.deleteIfExists(dataRoot.resolve(dbName).resolve(tname + ".json"));
            if (tspaces != null) {
                tspaces.release(dbName + "." + tname);   // 释放表空间中的数据文件与配额
            }
        } catch (IOException e) {
            throw new EngineException(ErrorCode.SERVER_ERROR, "删表落盘失败: " + e.getMessage());
        }
    }

    private void persistAll() throws EngineException {
        for (Database db : databases.values()) {
            persistDatabase(db.name);
            for (Table t : db.tables.values()) {
                persistTable(db.name, t);
            }
        }
    }

    private void deleteRecursive(Path dir) throws IOException {
        try (var stream = Files.walk(dir)) {
            List<Path> sorted = new ArrayList<>();
            stream.forEach(sorted::add);
            sorted.sort((a, b) -> b.compareTo(a)); // 先删文件再删目录
            for (Path p : sorted) {
                Files.deleteIfExists(p);
            }
        }
    }

    // ===================== 演示数据 =====================

    private void seedDemo() {
        Database school = new Database("school");
        Table student = new Table("student");
        student.cols.add(new ColumnDef("id", ColumnType.INT, false, true, null));
        student.cols.add(new ColumnDef("name", ColumnType.VARCHAR, false, false, null));
        student.cols.add(new ColumnDef("grade", ColumnType.INT, true, false, null));
        student.rows.add(new Object[]{1, "张三", 90});
        student.rows.add(new Object[]{2, "李四", 85});
        student.rows.add(new Object[]{3, "王五", 92});
        student.rows.add(new Object[]{4, "赵六", 78});

        Table course = new Table("course");
        course.cols.add(new ColumnDef("id", ColumnType.INT, false, true, null));
        course.cols.add(new ColumnDef("name", ColumnType.VARCHAR, false, false, null));
        course.rows.add(new Object[]{101, "数学"});
        course.rows.add(new Object[]{102, "物理"});

        Table score = new Table("score");
        score.cols.add(new ColumnDef("sid", ColumnType.INT, false, false, null));
        score.cols.add(new ColumnDef("cid", ColumnType.INT, false, false, null));
        score.cols.add(new ColumnDef("point", ColumnType.INT, false, false, null));
        score.rows.add(new Object[]{1, 101, 95});
        score.rows.add(new Object[]{1, 102, 88});
        score.rows.add(new Object[]{2, 101, 76});
        score.rows.add(new Object[]{3, 102, 99});

        school.tables.put("student", student);
        school.tables.put("course", course);
        school.tables.put("score", score);
        databases.put("school", school);
    }

    // ===================== 访问控制（用户与权限）=====================

    private void requirePriv(SessionContext session, String db, String tbl, Priv priv) throws EngineException {
        String u = session.getUser();
        if (u == null) {
            throw new EngineException(ErrorCode.PRIVILEGE_DENIED, "未认证，请先连接登录");
        }
        if ("root".equals(u)) {
            return; // root 拥有全部权限
        }
        User user = userRegistry.get(u);
        if (user == null) {
            throw new EngineException(ErrorCode.PRIVILEGE_DENIED, "未知用户: " + u);
        }
        for (Grant g : user.grants) {
            if ((g.db.equals("*") || g.db.equals(db))
                    && (g.tbl.equals("*") || g.tbl.equals(tbl))
                    && g.privs.contains(priv)) {
                return;
            }
        }
        throw new EngineException(ErrorCode.PRIVILEGE_DENIED,
                "用户 " + u + " 缺少 " + priv + " 权限于 " + db + "." + tbl);
    }

    private QueryResult doGrant(SessionContext session, Ts ts) throws EngineException {
        requirePriv(session, "*", "*", Priv.GRANT);
        ts.expectKw("GRANT");
        EnumSet<Priv> privs = EnumSet.noneOf(Priv.class);
        while (true) {
            String p = ts.expect(TokenType.IDENT, "权限").text.toUpperCase();
            if (p.equals("ALL") || p.equals("ALL_PRIVILEGES")) {
                privs = ALL_PRIVS.clone();
            } else {
                privs.add(Priv.valueOf(p));
            }
            if (ts.eatKw("PRIVILEGES")) {
                break;
            }
            if (ts.eat(TokenType.COMMA)) {
                continue;
            }
            break;
        }
        ts.expectKw("ON");
        String[] target = parseTarget(session, ts);
        ts.expectKw("TO");
        String user = parseUser(ts);
        if (ts.eatKw("IDENTIFIED")) {
            ts.expectKw("BY");
            ts.expect(TokenType.STRING, "密码"); // GRANT ... IDENTIFIED BY 'pwd'：密码忽略（用户须已存在或可自动创建）
        }
        User u = userRegistry.get(user);
        if (u == null) {
            u = new User("");
            userRegistry.put(user, u);
            users.put(user, "");
        }
        u.grants.add(new Grant(target[0], target[1], privs));
        persistAuth();
        Map<String, Object> gp = new LinkedHashMap<>();
        gp.put("user", user);
        gp.put("privs", privs.toString());
        walLog(session, WalManager.OP_GRANT, target[0], target[1], "grant " + privs + " to " + user, 0, gp);
        return QueryResult.ofAffected(0);
    }

    private QueryResult doRevoke(SessionContext session, Ts ts) throws EngineException {
        requirePriv(session, "*", "*", Priv.GRANT);
        ts.expectKw("REVOKE");
        EnumSet<Priv> privs = EnumSet.noneOf(Priv.class);
        while (true) {
            String p = ts.expect(TokenType.IDENT, "权限").text.toUpperCase();
            privs.add(Priv.valueOf(p));
            if (ts.eat(TokenType.COMMA)) {
                continue;
            }
            break;
        }
        ts.expectKw("ON");
        String[] target = parseTarget(session, ts);
        ts.expectKw("FROM");
        String user = parseUser(ts);
        User u = userRegistry.get(user);
        if (u == null) {
            throw new EngineException(ErrorCode.PRIVILEGE_DENIED, "用户不存在: " + user);
        }
        for (Grant g : u.grants) {
            if ((g.db.equals("*") || g.db.equals(target[0]))
                    && (g.tbl.equals("*") || g.tbl.equals(target[1]))) {
                g.privs.removeAll(privs);
            }
        }
        u.grants.removeIf(g -> g.privs.isEmpty());
        persistAuth();
        Map<String, Object> rp = new LinkedHashMap<>();
        rp.put("user", user);
        rp.put("privs", privs.toString());
        walLog(session, WalManager.OP_REVOKE, target[0], target[1], "revoke " + privs + " from " + user, 0, rp);
        return QueryResult.ofAffected(0);
    }

    /** 解析 `db.tbl` / `*.*` / `db.*` / `*`，返回 [db, tbl]（`*` 归一为 "*"）。
     *  注意 `feat.*` 被词法器拆成 IDENT/DOT/STAR 三个 token，需逐个消费。 */
    private String[] parseTarget(SessionContext session, Ts ts) throws EngineException {
        String first;
        if (ts.peek().type == TokenType.STAR) {
            first = ts.next().text;
        } else {
            first = ts.expect(TokenType.IDENT, "授权对象").text;
        }
        StringBuilder sb = new StringBuilder(first);
        if (ts.peek().type == TokenType.DOT) {
            ts.next();
            sb.append('.');
            if (ts.peek().type == TokenType.STAR) {
                sb.append(ts.next().text);
            } else {
                sb.append(ts.expect(TokenType.IDENT, "对象").text);
            }
        }
        String raw = sb.toString();
        int dot = raw.indexOf('.');
        if (dot < 0) {
            return new String[]{requireDb(session).name, raw};
        }
        return new String[]{raw.substring(0, dot), raw.substring(dot + 1)};
    }

    /** 解析 `'user'[@'host']`，返回用户名（主机部分忽略）。 */
    private String parseUser(Ts ts) throws EngineException {
        String user = ts.expect(TokenType.STRING, "用户名").text;
        if (ts.peek().type == TokenType.IDENT && ts.peek().text.equals("@")) {
            ts.next();
            ts.expect(TokenType.STRING, "主机");
        }
        return user;
    }

    private QueryResult doCreateUser(SessionContext session, Ts ts) throws EngineException {
        requirePriv(session, "*", "*", Priv.CREATE);
        // 注意：doCreate 中已 eatKw("USER")，此处无需再 expect
        String user = ts.expect(TokenType.STRING, "用户名").text;
        if (ts.peek().type == TokenType.IDENT && ts.peek().text.equals("@")) {
            ts.next();
            ts.expect(TokenType.STRING, "主机");
        }
        String pwd = "";
        if (ts.eatKw("IDENTIFIED")) {
            ts.expectKw("BY");
            pwd = ts.expect(TokenType.STRING, "密码").text;
        }
        if (userRegistry.containsKey(user)) {
            throw new EngineException(ErrorCode.SYNTAX_ERROR, "用户已存在: " + user);
        }
        userRegistry.put(user, new User(pwd));
        users.put(user, pwd);
        persistAuth();
        walLog(session, WalManager.OP_CREATE_USER, null, null, "create user " + user, 0, null);
        return QueryResult.ofAffected(0);
    }

    private void loadAuth() throws EngineException {
        Path p = dataRoot.resolve("users.json");
        if (!Files.exists(p)) {
            return;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> obj = JsonUtils.mapper().readValue(p.toFile(), Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> us = (Map<String, Object>) obj.get("users");
            if (us == null) {
                return;
            }
            for (Map.Entry<String, Object> e : us.entrySet()) {
                String uname = e.getKey();
                if ("root".equals(uname)) {
                    continue; // root 权限在 init 中已固定为全权限，不被持久化文件覆盖
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> ud = (Map<String, Object>) e.getValue();
                String pwd = String.valueOf(ud.get("password"));
                User user = new User(pwd);
                users.put(uname, pwd);
                Object gl = ud.get("grants");
                if (gl instanceof List) {
                    for (Object go : (List<?>) gl) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> gm = (Map<String, Object>) go;
                        String gdb = String.valueOf(gm.get("db"));
                        String gtbl = String.valueOf(gm.get("tbl"));
                        @SuppressWarnings("unchecked")
                        List<String> ps = (List<String>) gm.get("privs");
                        EnumSet<Priv> ps2 = EnumSet.noneOf(Priv.class);
                        for (String s : ps) {
                            ps2.add(Priv.valueOf(s));
                        }
                        user.grants.add(new Grant(gdb, gtbl, ps2));
                    }
                }
                userRegistry.put(uname, user);
            }
        } catch (Exception e) {
            throw new EngineException(ErrorCode.SERVER_ERROR, "加载用户失败: " + e.getMessage());
        }
    }

    private void persistAuth() throws EngineException {
        try {
            Map<String, Object> obj = new LinkedHashMap<>();
            Map<String, Object> us = new LinkedHashMap<>();
            for (Map.Entry<String, User> e : userRegistry.entrySet()) {
                Map<String, Object> ud = new LinkedHashMap<>();
                ud.put("password", e.getValue().password);
                List<Object> gl = new ArrayList<>();
                for (Grant g : e.getValue().grants) {
                    Map<String, Object> gm = new LinkedHashMap<>();
                    gm.put("db", g.db);
                    gm.put("tbl", g.tbl);
                    List<String> ps = new ArrayList<>();
                    for (Priv p : g.privs) {
                        ps.add(p.name());
                    }
                    gm.put("privs", ps);
                    gl.add(gm);
                }
                ud.put("grants", gl);
                us.put(e.getKey(), ud);
            }
            obj.put("users", us);
            Files.write(dataRoot.resolve("users.json"), JsonUtils.mapper().writeValueAsBytes(obj));
        } catch (IOException e) {
            throw new EngineException(ErrorCode.SERVER_ERROR, "保存用户失败: " + e.getMessage());
        }
    }

    // ===================== 视图 =====================

    @Override
    public boolean isView(String db, String table) throws EngineException {
        Database d = databases.get(db);
        return d != null && d.views.containsKey(table);
    }

    @Override
    public List<List<Object>> listTablesWithType(String db) throws EngineException {
        Database d = databases.get(db);
        if (d == null) {
            throw new EngineException(ErrorCode.UNKNOWN_DATABASE, "库不存在: " + db);
        }
        List<List<Object>> out = new ArrayList<>();
        for (String t : d.tables.keySet()) {
            out.add(List.of(t, "BASE TABLE"));
        }
        for (String v : d.views.keySet()) {
            out.add(List.of(v, "VIEW"));
        }
        return out;
    }

    @Override
    public List<List<String>> listViews(String db) throws EngineException {
        Database d = databases.get(db);
        if (d == null) {
            throw new EngineException(ErrorCode.UNKNOWN_DATABASE, "库不存在: " + db);
        }
        List<List<String>> out = new ArrayList<>();
        for (Map.Entry<String, String> e : d.views.entrySet()) {
            out.add(List.of(e.getKey(), e.getValue()));
        }
        return out;
    }

    @Override
    public List<List<String>> listIndexes(String db, String table) throws EngineException {
        if (isView(db, table)) {
            return new ArrayList<>();
        }
        Table t = requireTable(db, table);
        List<List<String>> out = new ArrayList<>();
        for (Map.Entry<String, String> e : t.indexNames.entrySet()) {
            out.add(List.of(e.getKey(), e.getValue()));
        }
        return out;
    }

    /** 把视图定义（一条 SELECT 文本）物化为临时内存表，供 SELECT / DESC / SHOW 引用。 */
    private Table materializeView(SessionContext session, String viewName, String body) throws EngineException {
        // 物化时内部会再进入 execute()→buildCombined()，后者会重置 lastTables/lastTableRows；
        // 先保存外层查询上下文，物化结束后恢复，避免 SELECT FROM view 时把基表与视图自连接导致行翻倍。
        List<Table> savedTables = new ArrayList<>(lastTables);
        List<List<Object[]>> savedRows = new ArrayList<>(lastTableRows);
        try {
            QueryResult qr = execute(session, body);
            if (!qr.isResultSet()) {
                throw new EngineException(ErrorCode.SYNTAX_ERROR, "视图定义必须是 SELECT: " + viewName);
            }
            Table vt = new Table(viewName);
            for (ColumnMeta cm : qr.columns()) {
                vt.cols.add(new ColumnDef(cm.name(), cm.type(), cm.nullable(), false, null));
            }
            for (List<Object> row : qr.rows()) {
                vt.rows.add(row.toArray());
            }
            return vt;
        } finally {
            lastTables = savedTables;
            lastTableRows = savedRows;
        }
    }

    private void createView(SessionContext session, Ts ts, Database db, String vname) throws EngineException {
        ts.expectKw("AS");
        StringBuilder sb = new StringBuilder();
        while (ts.hasNext() && ts.peek().type != TokenType.SEMI) {
            sb.append(ts.next().text).append(' ');
        }
        String body = sb.toString().trim();
        if (body.isEmpty()) {
            throw new EngineException(ErrorCode.SYNTAX_ERROR, "视图定义不能为空");
        }
        db.views.put(vname, body);
        persistViews(db);
        Map<String, Object> vp = new LinkedHashMap<>();
        vp.put("select", body);
        walLog(session, WalManager.OP_CREATE_VIEW, db.name, vname, "create view", 0, vp);
    }

    private void dropView(SessionContext session, Ts ts, Database db, String vname) throws EngineException {
        if (db.views.remove(vname) == null) {
            throw new EngineException(ErrorCode.UNKNOWN_TABLE, "视图不存在: " + vname);
        }
        deleteViews(db.name, vname);
        walLog(session, WalManager.OP_DROP_VIEW, db.name, vname, "drop view", 0, null);
    }

    private void persistViews(Database db) throws EngineException {
        try {
            Path dir = dataRoot.resolve(db.name);
            Files.createDirectories(dir);
            Map<String, Object> obj = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : db.views.entrySet()) {
                obj.put(e.getKey(), e.getValue());
            }
            Files.write(dir.resolve("_views.json"), JsonUtils.mapper().writeValueAsBytes(obj));
        } catch (IOException e) {
            throw new EngineException(ErrorCode.SERVER_ERROR, "保存视图失败: " + e.getMessage());
        }
    }

    private void deleteViews(String dbName, String vname) throws EngineException {
        Path f = dataRoot.resolve(dbName).resolve("_views.json");
        if (!Files.exists(f)) {
            return;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> obj = JsonUtils.mapper().readValue(f.toFile(), Map.class);
            if (obj.remove(vname) != null) {
                Files.write(f, JsonUtils.mapper().writeValueAsBytes(obj));
            }
        } catch (IOException e) {
            throw new EngineException(ErrorCode.SERVER_ERROR, "删除视图失败: " + e.getMessage());
        }
    }

    private void loadViews(Database db) throws EngineException {
        Path f = dataRoot.resolve(db.name).resolve("_views.json");
        if (!Files.exists(f)) {
            return;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> obj = JsonUtils.mapper().readValue(f.toFile(), Map.class);
            for (Map.Entry<String, Object> e : obj.entrySet()) {
                db.views.put(e.getKey(), String.valueOf(e.getValue()));
            }
        } catch (Exception e) {
            throw new EngineException(ErrorCode.SERVER_ERROR, "加载视图失败: " + e.getMessage());
        }
    }

    // ===================== 索引 =====================

    private void createIndex(SessionContext session, Ts ts, Database db, String iname) throws EngineException {
        ts.expectKw("ON");
        String rawTbl = ts.expect(TokenType.IDENT, "表名").text;
        Table t = requireTable(db, rawTbl);
        requirePriv(session, db.name, t.name, Priv.CREATE);
        ts.expect(TokenType.LPAREN, "(");
        String col = ts.expect(TokenType.IDENT, "列名").text;
        ts.expect(TokenType.RPAREN, ")");
        if (t.indexNames.containsKey(iname)) {
            throw new EngineException(ErrorCode.SYNTAX_ERROR, "索引已存在: " + iname);
        }
        t.indexNames.put(iname, col);
        t.indexCols.add(col);
        rebuildIndex(t, col);
        persistTable(db.name, t);
        Map<String, Object> ip = new LinkedHashMap<>();
        ip.put("index", iname);
        ip.put("column", col);
        walLog(session, WalManager.OP_CREATE_INDEX, db.name, t.name, "create index " + iname, 0, ip);
    }

    private void dropIndex(SessionContext session, Ts ts, Database db) throws EngineException {
        String iname = ts.expect(TokenType.IDENT, "索引名").text;
        ts.expectKw("ON");
        String rawTbl = ts.expect(TokenType.IDENT, "表名").text;
        Table t = requireTable(db, rawTbl);
        requirePriv(session, db.name, t.name, Priv.DROP);
        String col = t.indexNames.remove(iname);
        if (col == null) {
            throw new EngineException(ErrorCode.UNKNOWN_COLUMN, "索引不存在: " + iname);
        }
        t.indexCols.remove(col);
        t.indexMaps.remove(col);
        persistTable(db.name, t);
        walLog(session, WalManager.OP_DROP_INDEX, db.name, t.name, "drop index " + iname, 0, null);
    }

    private void rebuildIndex(Table t, String col) {
        int ci = t.indexOf(col);
        Map<Object, List<Integer>> map = new LinkedHashMap<>();
        for (int i = 0; i < t.rows.size(); i++) {
            Object[] r = t.rows.get(i);
            if (r == null) {
                continue;                // 空位不进索引
            }
            map.computeIfAbsent(indexKey(r[ci]), k -> new ArrayList<>()).add(i);
        }
        t.indexMaps.put(col, map);
    }

    private void rebuildIndexes(Table t) {
        for (String col : t.indexCols) {
            rebuildIndex(t, col);
        }
    }

    private void rebuildAllIndexes() {
        for (Database db : databases.values()) {
            for (Table t : db.tables.values()) {
                rebuildIndexes(t);
            }
        }
    }

    // ===================== 并发控制：连接注册 / 事务号 / 快照 / 可见性 / 加锁 =====================

    /** 连接建立：沿用全局默认隔离级别，并登记到进程表。 */
    @Override
    public void onConnect(SessionContext s) {
        s.setIsolation(globalIsolation);
        s.setLockWaitTimeoutMs(globalLockWaitTimeoutMs);
        registerProcess(s);
    }

    /** 连接断开：注销进程、回滚未提交事务、释放全部锁（否则异常断开会把锁永久占住）。 */
    @Override
    public void onDisconnect(SessionContext s) {
        unregisterProcess(s);
    }

    /** 连接建立时登记（SHOW PROCESSLIST 用）。 */
    public void registerProcess(SessionContext s) {
        Proc p = new Proc(s.getSessionId(), s.getUser() == null ? "" : s.getUser());
        p.isolation = s.getIsolation().sqlName();
        processes.put(s.getSessionId(), p);
    }

    /** 连接断开时注销，并释放它可能还持有的锁与事务。 */
    public void unregisterProcess(SessionContext s) {
        processes.remove(s.getSessionId());
        if (s != null) {
            forceEndTransaction(s);
        }
    }

    /** 更新一条连接的活动状态（SHOW PROCESSLIST 展示）。 */
    private void markProc(SessionContext s, String command, String info) {
        if (s == null) {
            return;
        }
        Proc p = processes.get(s.getSessionId());
        if (p == null) {
            return;
        }
        p.command = command;
        p.info = info == null ? "" : info;
        p.database = s.getCurrentDatabase() == null ? "" : s.getCurrentDatabase();
        p.trxId = s.getTrxId();
        p.isolation = s.getIsolation().sqlName();
        p.lastActiveAt = System.currentTimeMillis();
    }

    /**
     * 确保当前会话有一个事务号：不在事务中则分配一个新的（并登记为活跃事务）。
     * 事务号是 MVCC 的基石 —— 行上记下"最后改我的是谁"，读的人拿自己的快照去比。
     */
    private long ensureTrxId(SessionContext s) {
        if (s == null) {
            return 0L;
        }
        if (s.getTrxId() != 0L) {
            return s.getTrxId();
        }
        long id = trxIdGen.incrementAndGet();
        s.setTrxId(id);
        Txn txn = txns.computeIfAbsent(s.getSessionId(), k -> new Txn());
        txn.trxId = id;
        activeTxns.put(id, txn);
        return id;
    }

    /** 新建一个读视图：记下此刻还活跃着的其他事务与已分配的最大事务号。 */
    private ReadView newReadView(SessionContext s) {
        long me = ensureTrxId(s);
        Set<Long> active = new HashSet<>();
        for (Map.Entry<Long, Txn> e : activeTxns.entrySet()) {
            if (e.getKey() != me) {
                active.add(e.getKey());
            }
        }
        return new ReadView(me, active, trxIdGen.get());
    }

    /**
     * 按隔离级别拿到本次读应使用的视图：
     * <ul>
     *   <li>READ UNCOMMITTED → null，表示"直接读最新版本"（脏读就是这么来的）</li>
     *   <li>READ COMMITTED → 每条语句新建一个</li>
     *   <li>REPEATABLE READ / SERIALIZABLE → 事务内首次读建一次，之后复用（所以可重复读）</li>
     * </ul>
     */
    private ReadView readViewFor(SessionContext s) {
        if (s == null || !s.isInTransaction()) {
            return null;   // 不在事务中：autocommit 语句看到的就是最新已提交数据
        }
        switch (s.getIsolation()) {
            case READ_UNCOMMITTED:
                return null;
            case READ_COMMITTED:
                return newReadView(s);
            default:
                Txn txn = txns.get(s.getSessionId());
                if (txn == null || txn.view == null) {
                    ReadView v = newReadView(s);
                    if (txn != null) {
                        txn.view = v;
                    }
                    return v;
                }
                return txn.view;
        }
    }

    /**
     * 取某一行在给定视图下的<b>可见版本</b>。
     * <ul>
     *   <li>{@code view == null} → 当前读，直接返回最新版本（UPDATE/DELETE/SERIALIZABLE 走这条）</li>
     *   <li>当前版本的改动事务对我可见 → 返回当前版本</li>
     *   <li>否则沿 undo 版本链往回找最早一个我可见的版本；找不到（例如这行是别人刚插入的）→ 返回 null，
     *       表示"在我的快照里这行不存在"</li>
     * </ul>
     */
    private Object[] visibleRow(Table t, int slot, ReadView view) {
        Object[] cur = t.rows.get(slot);
        if (view == null) {
            return cur;
        }
        long trx = t.rowTrxId.getOrDefault(slot, 0L);
        if (view.sees(trx)) {
            return cur;
        }
        return findVersion(t, slot, view);
    }

    /**
     * 沿版本链往回找：从最新的历史版本开始倒序扫描，取第一个我能看见的版本。
     * 找不到（例如这行是别人刚 INSERT 的，它的"上一版"是不存在）→ 返回 null，
     * 表示"在我的快照里这行还没有"。
     */
    private Object[] findVersion(Table t, int slot, ReadView view) {
        List<Object[]> vs = t.versions.get(slot);
        if (vs == null || vs.isEmpty()) {
            return null;
        }
        for (int i = vs.size() - 1; i >= 0; i--) {
            Object[] v = vs.get(i);
            long vTrx = v[0] == null ? 0L : (Long) v[0];
            if (view.sees(vTrx)) {
                return (Object[]) v[1];   // INSERT 的 before 是 null —— 这行在我的快照里还不存在
            }
        }
        return null;
    }

    /** 事务结束（提交或回滚）时的收尾：释放锁、移出活跃事务表、提交时把 undo 转入版本链。 */
    private void endTransaction(SessionContext s, boolean commit) {
        if (s == null) {
            return;
        }
        Txn txn = txns.remove(s.getSessionId());
        long id = s.getTrxId();
        if (id != 0L) {
            activeTxns.remove(id);
            if (commit && txn != null && !txn.undo.isEmpty()) {
                // 提交后改动已生效，undo 记录转为"历史版本"供别人的快照读回溯
                versionStore.put(id, new ArrayList<>(txn.undo));
                // 版本链不能无限增长：只保留最近 200 个事务的影像
                if (versionStore.size() > 200) {
                    List<Long> ids = new ArrayList<>(versionStore.keySet());
                    ids.sort(null);
                    for (int i = 0; i < ids.size() - 200; i++) {
                        versionStore.remove(ids.get(i));
                    }
                }
            }
        }
        releaseTxnLocks(s, txn);
        s.setTrxId(0L);
        s.setInTransaction(false);
        s.setAutocommit(true);
        s.setDeadlockVictim(false);
    }

    /** 连接断开时强制收尾（未提交的事务按回滚处理，锁全部释放）。 */
    private void forceEndTransaction(SessionContext s) {
        endTransaction(s, false);
        lockMgr.releaseAll(s.getSessionId());
        s.getExplicitTableLocks().clear();
    }

    /**
     * 释放本事务持有的行锁与表锁，但<b>保留 {@code LOCK TABLES} 加的显式表锁</b> ——
     * 那种锁要一直留到 UNLOCK TABLES 或连接断开，不能因为一条语句结束就放掉。
     */
    private void releaseTxnLocks(SessionContext s, Txn txn) {
        Set<String> keep = s.getExplicitTableLocks().keySet();
        if (txn == null) {
            if (keep.isEmpty()) {
                lockMgr.releaseAll(s.getSessionId());
            } else {
                lockMgr.releaseAllExcept(s.getSessionId(), keep);
            }
            return;
        }
        for (String r : new ArrayList<>(txn.lockedTables)) {
            if (!keep.contains(r)) {
                lockMgr.release(r, s.getSessionId());
            }
        }
        txn.lockedTables.retainAll(keep);
        for (String r : new ArrayList<>(txn.lockedRows)) {
            lockMgr.release(r, s.getSessionId());
        }
        txn.lockedRows.clear();
    }

    /**
     * 请求一个表级锁（冲突则等待，超时报 1205，会成环报 1213）。
     *
     * @param mode IX = "我要改这张表的某些行"；X = "我要独占这张表（如 INSERT 改行结构）"；
     *             IS/S = 读锁（SERIALIZABLE 读、LOCK IN SHARE MODE）
     */
    private void lockTable(SessionContext s, String db, Table t, LockManager.Mode mode)
            throws EngineException {
        if (s == null) {
            return;
        }
        ensureTrxId(s);
        String res = db + "." + t.name;
        Txn txn = txns.computeIfAbsent(s.getSessionId(), k -> new Txn());
        if (txn.lockedTables.contains(res)) {
            return;   // 本事务已持有，无需重复申请
        }
        try {
            lockMgr.acquire(res, s.getSessionId(), mode, s.getLockWaitTimeoutMs());
            txn.lockedTables.add(res);
        } catch (EngineException e) {
            if (e.code() == ErrorCode.DEADLOCK) {
                s.setDeadlockVictim(true);
            }
            throw e;
        }
    }

    /** 请求一个行级锁（{@code db.table#slot}）。 */
    private void lockRow(SessionContext s, String db, Table t, int slot, LockManager.Mode mode)
            throws EngineException {
        if (s == null) {
            return;
        }
        ensureTrxId(s);
        String res = db + "." + t.name + "#" + slot;
        Txn txn = txns.computeIfAbsent(s.getSessionId(), k -> new Txn());
        if (txn.lockedRows.contains(res)) {
            return;
        }
        try {
            lockMgr.acquire(res, s.getSessionId(), mode, s.getLockWaitTimeoutMs());
            txn.lockedRows.add(res);
        } catch (EngineException e) {
            if (e.code() == ErrorCode.DEADLOCK) {
                s.setDeadlockVictim(true);
            }
            throw e;
        }
    }

    // ===================== 并发观测：SHOW PROCESSLIST / SHOW ENGINE INNODB STATUS =====================

    /** {@code SHOW PROCESSLIST}：每条连接一行，显示它在干什么、处于哪个事务、什么隔离级别。 */
    private QueryResult showProcessList() {
        List<ColumnMeta> cols = List.of(
                new ColumnMeta("Id", ColumnType.VARCHAR, false, false),
                new ColumnMeta("User", ColumnType.VARCHAR, false, false),
                new ColumnMeta("Host", ColumnType.VARCHAR, false, false),
                new ColumnMeta("db", ColumnType.VARCHAR, true, false),
                new ColumnMeta("Command", ColumnType.VARCHAR, false, false),
                new ColumnMeta("Time", ColumnType.BIGINT, false, false),
                new ColumnMeta("State", ColumnType.VARCHAR, true, false),
                new ColumnMeta("Info", ColumnType.VARCHAR, true, false));
        List<List<Object>> rows = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Proc p : processes.values()) {
            List<Object> r = new ArrayList<>();
            r.add(p.sessionId);
            r.add(p.user);
            r.add("127.0.0.1");
            r.add(p.database.isEmpty() ? null : p.database);
            r.add(p.command);
            r.add((now - p.lastActiveAt) / 1000);
            String state = p.trxId != 0L
                    ? "in trx " + p.trxId + " / " + p.isolation
                    : p.isolation;
            r.add(state);
            String info = p.info;
            if (info.length() > 100) {
                info = info.substring(0, 100);
            }
            r.add(info.isEmpty() ? null : info);
            rows.add(r);
        }
        return QueryResult.ofResultSet(cols, rows);
    }

    /**
     * {@code SHOW ENGINE INNODB STATUS}：把锁管理器与事务表的内部状态打印出来。
     * 按 MySQL 的列布局 {@code Type / Name / Status} 返回，Status 是一大段文本。
     */
    private QueryResult showInnodbStatus(SessionContext session) {
        StringBuilder b = new StringBuilder();
        b.append("=====================================\n");
        b.append("KUN-DB INNODB MONITOR OUTPUT\n");
        b.append("=====================================\n");

        b.append("\n--------------\nTRANSACTIONS\n--------------\n");
        b.append("Trx id counter ").append(trxIdGen.get()).append('\n');
        b.append("活跃事务数: ").append(activeTxns.size()).append('\n');
        for (Map.Entry<Long, Txn> e : activeTxns.entrySet()) {
            Txn t = e.getValue();
            b.append("---TRX ").append(e.getKey())
                    .append(", undo 记录 ").append(t.undo.size())
                    .append(" 条, 脏表 ").append(t.dirty.size())
                    .append(" 张, 行锁 ").append(t.lockedRows.size())
                    .append(" 个, 表锁 ").append(t.lockedTables.size()).append(" 个\n");
        }
        b.append("版本链中保留的事务影像数: ").append(versionStore.size()).append('\n');

        b.append("\n--------------\nLOCKS\n--------------\n");
        List<List<Object>> locks = lockMgr.snapshotLocks();
        if (locks.isEmpty()) {
            b.append("当前无任何锁\n");
        } else {
            for (List<Object> l : locks) {
                b.append("资源 ").append(l.get(0))
                        .append(" | 模式 ").append(l.get(1))
                        .append(" | 持有者 ").append(l.get(2))
                        .append(" | 等待者 ").append(l.get(3)).append(" 个\n");
            }
        }
        List<List<Object>> waits = lockMgr.snapshotWaits();
        b.append("\n等待中的请求: ").append(waits.size()).append(" 个\n");
        for (List<Object> w : waits) {
            b.append("  会话 ").append(w.get(0)).append(" 等 ").append(w.get(1))
                    .append(" (").append(w.get(2)).append("), 被 ").append(w.get(3)).append(" 占着\n");
        }

        b.append("\n--------------\nDEADLOCKS\n--------------\n");
        List<String> dl = lockMgr.deadlockLog();
        b.append("死锁次数: ").append(dl.size()).append('\n');
        for (String s : dl) {
            b.append("  ").append(s).append('\n');
        }

        b.append("\n--------------\nSESSION\n--------------\n");
        b.append("当前会话隔离级别: ")
                .append(session == null ? "-" : session.getIsolation().sqlName()).append('\n');
        b.append("全局默认隔离级别: ").append(globalIsolation.sqlName()).append('\n');
        b.append("锁等待超时: ").append(session == null ? globalLockWaitTimeoutMs
                : session.getLockWaitTimeoutMs()).append(" ms\n");

        List<ColumnMeta> cols = List.of(
                new ColumnMeta("Type", ColumnType.VARCHAR, false, false),
                new ColumnMeta("Name", ColumnType.VARCHAR, false, false),
                new ColumnMeta("Status", ColumnType.VARCHAR, false, false));
        return QueryResult.ofResultSet(cols, List.of(List.of("InnoDB", "", b.toString())));
    }

    // ===================== 并发控制语句：SET / LOCK TABLES / UNLOCK TABLES =====================

    /**
     * {@code SET} 语句中与并发控制相关的部分：
     * <ul>
     *   <li>{@code SET [SESSION|GLOBAL] TRANSACTION ISOLATION LEVEL <级别>}</li>
     *   <li>{@code SET [SESSION|GLOBAL] innodb_lock_wait_timeout = <秒>}</li>
     *   <li>{@code SET autocommit = 0|1}</li>
     * </ul>
     * 其余（{@code SET NAMES} / 各种客户端变量）一律忽略并回 OK —— 不影响功能，只是不报错。
     */
    private QueryResult doSet(SessionContext session, Ts ts) throws EngineException {
        ts.expectKw("SET");
        StringBuilder sb = new StringBuilder();
        while (ts.hasNext()) {
            sb.append(ts.next().text).append(' ');
        }
        String rest = sb.toString().trim();
        String up = rest.toUpperCase();
        boolean isGlobal = up.contains("GLOBAL");

        // 隔离级别
        if (up.contains("ISOLATION") && up.contains("LEVEL")) {
            String level = rest.substring(up.indexOf("LEVEL") + 5).trim();
            SessionContext.Isolation iso = SessionContext.parseIsolation(level.replace('=', ' ').trim());
            if (iso == null) {
                throw syn("不支持的隔离级别: " + level
                        + "（可选 READ UNCOMMITTED / READ COMMITTED / REPEATABLE READ / SERIALIZABLE）");
            }
            if (isGlobal) {
                globalIsolation = iso;                 // 之后新建的会话沿用
            }
            session.setIsolation(iso);                 // 无论如何当前会话立即生效
            return QueryResult.ofAffected(0);
        }

        // 锁等待超时（MySQL 单位是秒）
        Matcher tm = Pattern.compile("(?i).*innodb_lock_wait_timeout\\s*=\\s*(\\d+).*").matcher(rest);
        if (tm.matches()) {
            long sec = Long.parseLong(tm.group(1));
            long ms = sec * 1000L;
            if (isGlobal) {
                globalLockWaitTimeoutMs = ms;
            }
            session.setLockWaitTimeoutMs(ms);
            return QueryResult.ofAffected(0);
        }

        // autocommit
        Matcher am = Pattern.compile("(?i).*autocommit\\s*=\\s*(\\d+|ON|OFF|TRUE|FALSE).*").matcher(rest);
        if (am.matches()) {
            String v = am.group(1).toUpperCase();
            boolean on = !v.equals("0") && !v.equals("OFF") && !v.equals("FALSE");
            session.setAutocommit(on);
            return QueryResult.ofAffected(0);
        }
        // 其余一律忽略（SET NAMES / SET @@x 等客户端探测语句）
        return QueryResult.ofAffected(0);
    }

    /** {@code LOCK TABLES t READ|WRITE [, t2 ...]}：显式表锁，持到 UNLOCK TABLES 或连接断开。 */
    private QueryResult doLockTables(SessionContext session, Ts ts) throws EngineException {
        ts.expectKw("LOCK");
        ts.eatKw("TABLES");
        ts.eatKw("TABLE");
        Database db = requireDb(session);
        int n = 0;
        while (true) {
            String name = ts.expect(TokenType.IDENT, "表名").text;
            String mode = "WRITE";
            if (ts.peek() != null) {
                String nx = ts.peek().text.toUpperCase();
                if (nx.equals("READ") || nx.equals("WRITE")) {
                    ts.next();
                    mode = nx;
                }
            }
            Table t = db.tables.get(name);
            if (t == null) {
                throw new EngineException(ErrorCode.UNKNOWN_TABLE, "表不存在: " + name);
            }
            requirePriv(session, db.name, t.name, mode.equals("READ") ? Priv.SELECT : Priv.UPDATE);
            lockTable(session, db.name, t,
                    mode.equals("READ") ? LockManager.Mode.S : LockManager.Mode.X);
            session.getExplicitTableLocks().put(db.name + "." + t.name, mode);
            n++;
            if (ts.eat(TokenType.COMMA)) {
                continue;
            }
            break;
        }
        return QueryResult.ofAffected(n);
    }

    /** {@code UNLOCK TABLES}：释放本会话的全部显式表锁。 */
    private QueryResult doUnlockTables(SessionContext session, Ts ts) throws EngineException {
        ts.expectKw("UNLOCK");
        ts.eatKw("TABLES");
        ts.eatKw("TABLE");
        int n = session.getExplicitTableLocks().size();
        for (String res : new ArrayList<>(session.getExplicitTableLocks().keySet())) {
            lockMgr.release(res, session.getSessionId());
        }
        session.getExplicitTableLocks().clear();
        return QueryResult.ofAffected(n);
    }

    // ===================== 事务 / 日志 / 表空间 =====================

    private QueryResult doTransaction(SessionContext session, Ts ts) throws EngineException {
        if (ts.eatKw("BEGIN")) {
            // 标准 BEGIN
        } else if (ts.eatKw("START")) {
            ts.expectKw("TRANSACTION");
        } else {
            throw new EngineException(ErrorCode.SYNTAX_ERROR, "期望 BEGIN 或 START TRANSACTION");
        }
        endTransaction(session, false);   // 若上一个事务异常残留，先收尾（释放锁、清旧事务号）
        session.setInTransaction(true);
        session.setAutocommit(false);
        txns.remove(session.getSessionId()); // 重新开始一个干净的事务
        long trxId = ensureTrxId(session);   // 分配事务号：MVCC 与锁都以它为身份
        walLog(session, WalManager.OP_BEGIN, session.getCurrentDatabase(), null,
                "begin trx=" + trxId, 0, null);
        return QueryResult.ofAffected(0);
    }

    private QueryResult doCommit(SessionContext session) throws EngineException {
        // 注意这里只"取"不"删"：删的动作交给 endTransaction，否则它拿不到 undo 记录、
        // 就无法把本次改动转入 MVCC 版本链（别人的快照读会因此看不到历史版本）。
        Txn txn = txns.get(session.getSessionId());
        // 提交的两步：① 先写 COMMIT 日志（这一步写完，事务就"算提交成功"了，即使随后崩溃也能 redo 回来）
        //            ② 再把脏表落盘。顺序不能反 —— 这正是 write-ahead logging 的核心。
        walLog(session, WalManager.OP_COMMIT, session.getCurrentDatabase(), null,
                txn == null ? "commit (empty)" : "commit " + txn.dirty.size() + " table(s)", 0, null);
        // 崩溃演示开关：-Dkundb.crash=commit 时在此处硬退出，制造"已提交但未落盘"的现场
        if ("commit".equalsIgnoreCase(crashAt)) {
            System.out.println("[KUN-DB] 模拟崩溃：COMMIT 已写日志，尚未落盘，进程退出");
            System.exit(9);
        }
        if (txn != null) {
            for (Map.Entry<Table, String> e : txn.dirty.entrySet()) {
                persistTable(e.getValue(), e.getKey());
            }
        }
        // 事务收尾：移出活跃事务表、把 undo 转入 MVCC 版本链、释放本事务持有的全部锁
        endTransaction(session, true);
        return QueryResult.ofAffected(0);
    }

    /**
     * ROLLBACK：逆序把本事务 undo 日志里的每条"改前影像"写回去，撤销全部改动。
     * <p>注意这里<b>不再复制整张表</b> —— 只还原 undo 日志记录过的那几行，其余行一个字节都不动。</p>
     */
    private QueryResult doRollback(SessionContext session, Ts ts) throws EngineException {
        ts.expectKw("ROLLBACK");
        // ROLLBACK TO SAVEPOINT 〈名〉：只撤销保存点之后的改动（整表快照方案根本做不到部分回滚）
        if (ts.eatKw("TO")) {
            ts.eatKw("SAVEPOINT");
            String name = ts.expect(TokenType.IDENT, "保存点名").text;
            return rollbackToSavepoint(session, name);
        }
        Txn txn = txns.get(session.getSessionId());   // 只取不删，删除交给 endTransaction
        // 先记 ROLLBACK，恢复时才不会把该事务的修改重放
        walLog(session, WalManager.OP_ROLLBACK, session.getCurrentDatabase(), null, "rollback", 0, null);
        int undone = txn == null ? 0 : applyUndo(txn.undo, 0);
        if (txn != null) {
            persistUndoneTables(txn);
        }
        // 回滚收尾：撤销的改动不入版本链（等于没发生过），锁全部释放
        endTransaction(session, false);
        return QueryResult.ofAffected(undone);
    }

    /** SAVEPOINT 〈名〉：在当前 undo 日志位置打一个标记，之后可只回滚到这里。 */
    private QueryResult doSavepoint(SessionContext session, Ts ts) throws EngineException {
        ts.expectKw("SAVEPOINT");
        String name = ts.expect(TokenType.IDENT, "保存点名").text;
        if (!session.isInTransaction()) {
            throw new EngineException(ErrorCode.SYNTAX_ERROR, "SAVEPOINT 只能在事务内使用（请先 BEGIN）");
        }
        Txn txn = txns.computeIfAbsent(session.getSessionId(), k -> new Txn());
        txn.savepoints.put(name, txn.undo.size());   // 记住"当时的 undo 日志长度"
        walLog(session, WalManager.OP_SAVEPOINT, session.getCurrentDatabase(), null,
                "savepoint " + name, 0, null);
        return QueryResult.ofAffected(0);
    }

    /** RELEASE SAVEPOINT 〈名〉：丢弃一个保存点（事务本身继续）。 */
    private QueryResult doReleaseSavepoint(SessionContext session, Ts ts) throws EngineException {
        ts.expectKw("RELEASE");
        ts.expectKw("SAVEPOINT");
        String name = ts.expect(TokenType.IDENT, "保存点名").text;
        Txn txn = txns.get(session.getSessionId());
        if (txn == null || txn.savepoints.remove(name) == null) {
            throw new EngineException(ErrorCode.SYNTAX_ERROR, "保存点不存在: " + name);
        }
        return QueryResult.ofAffected(0);
    }

    /** 回滚到保存点：只撤销该保存点<b>之后</b>追加的 undo 记录，之前的改动保留。 */
    private QueryResult rollbackToSavepoint(SessionContext session, String name) throws EngineException {
        Txn txn = txns.get(session.getSessionId());
        if (txn == null || !txn.savepoints.containsKey(name)) {
            throw new EngineException(ErrorCode.SYNTAX_ERROR, "保存点不存在: " + name);
        }
        int from = txn.savepoints.get(name);
        int undone = applyUndo(txn.undo, from);
        if (from < txn.undo.size()) {
            txn.undo.subList(from, txn.undo.size()).clear();   // 已撤销的记录不再需要
        }
        txn.savepoints.entrySet().removeIf(e -> e.getValue() >= from);  // 更晚建立的保存点随之失效
        // 注意用 ROLLBACK_TO_SAVEPOINT 而不是 ROLLBACK：事务还在进行中，只是撤到了保存点
        walLog(session, WalManager.OP_ROLLBACK_TO_SAVEPOINT, session.getCurrentDatabase(), null,
                "rollback to savepoint " + name, 0, null);
        persistUndoneTables(txn);
        return QueryResult.ofAffected(undone);
    }

    /** DML 改写后调用：事务内只做脏表标记（落盘推迟到 COMMIT）；
     *  非事务（autocommit）则立即落盘并写 WAL。
     *  注意：undo 日志必须在 DML 真正改写行<b>之前</b>通过 recordUndo() 记录改前影像
     *  （见 doUpdate/doDelete/doInsert/doTruncate），否则记下的就是改后的值，ROLLBACK 无法还原。 */
    private void touch(SessionContext session, String db, Table t) throws EngineException {
        // 索引动态维护：任何 DML（INSERT/UPDATE/DELETE）改完行之后立即重建该表的索引。
        // 索引里存的是"行在 t.rows 中的下标"，删行会整体前移、增行会追加 —— 不重建就会读到过期下标，
        // 表现为"刚插进去的行用索引条件查不到"（课程要求 §3 明确要求索引随记录变更同步维护）。
        if (!t.indexCols.isEmpty()) {
            rebuildIndexes(t);
        }
        if (session != null && session.isInTransaction()) {
            Txn txn = txns.computeIfAbsent(session.getSessionId(), k -> new Txn());
            txn.dirty.put(t, db);   // 事务内：推迟到 COMMIT 再落盘
        } else {
            // autocommit：立即落盘。注意 WAL 记录已在 DML 处先行写入（write-ahead 语义），
            // 若落盘前崩溃，重启后靠日志 redo 补回。
            persistTable(db, t);
        }
    }

    /**
     * 在<b>真正改写某一行之前</b>调用：往本事务的 undo 日志里追加一条"改前影像"。
     * 这是 undo 日志与整表快照的根本差别 —— 只记被改动的这一行，而不是整张表。
     *
     * @param before 该行改动前的内容（INSERT 传 null，因为插入前该槽是空位）
     */
    private void recordUndo(SessionContext session, String dbName, Table t, UndoOp op, int slot,
                            Object[] before, boolean wasDeletedSlot, long autoIncBefore) {
        if (session == null) {
            return;
        }
        // ---- MVCC：无论是否在显式事务中，都要登记版本号与版本链 ----
        // autocommit 的 DML 也是一个"独立事务"（立即生效），别人做快照读时必须能判断
        // "这行是它改的、我的快照看不看得见"。以前只在事务内登记，导致 autocommit 的改动
        // 对所有快照都可见 —— RR 下就会出现幻读、不可重复读。
        long trx = ensureTrxId(session);
        long prevTrx = t.rowTrxId.getOrDefault(slot, 0L);
        if (trx != 0L) {
            List<Object[]> vs = t.versions.computeIfAbsent(slot, k -> new ArrayList<>());
            vs.add(new Object[]{prevTrx, before == null ? null : before.clone()});
            if (vs.size() > 64) {
                vs.remove(0);   // 版本链不能无限增长，只保留最近 64 个版本
            }
            t.rowTrxId.put(slot, trx);
        }
        if (!session.isInTransaction()) {
            return;    // autocommit 语句没有"可回滚的事务"，无需记 undo
        }
        Txn txn = txns.computeIfAbsent(session.getSessionId(), k -> new Txn());
        txn.dirty.put(t, dbName);
        txn.trxId = trx;
        txn.undo.add(new UndoRecord(t, slot, op, before, wasDeletedSlot, autoIncBefore));
        // undo 也要 write-ahead 落日志：崩溃恢复时靠它把"崩时正在进行"的事务真正回滚掉，
        // 而不是像以前那样只能"丢弃"（事务期间若执行过 CHECKPOINT，未提交数据其实已落盘）。
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("op", op.name());
        p.put("slot", slot);
        p.put("before", before == null ? null : Arrays.asList(before));
        p.put("wasDeletedSlot", wasDeletedSlot);
        p.put("autoInc", autoIncBefore);
        walLog(session, WalManager.OP_UNDO, dbName, t.name,
                "undo " + op.name().toLowerCase() + " slot=" + slot, 1, p);
    }

    /**
     * 逆序应用 undo 日志：把 {@code [fromIndex, size)} 这段记录代表的改动逐个撤销。
     * <b>必须从后往前</b> —— 后发生的改动先撤销，才能保证每次还原的都是"上一次的状态"
     * （同一行被改两次时，顺序反了就会还原成错误的中间值）。
     *
     * @return 实际还原的行数
     */
    private int applyUndo(List<UndoRecord> undo, int fromIndex) {
        int undone = 0;
        Set<Table> touched = new LinkedHashSet<>();
        for (int i = undo.size() - 1; i >= fromIndex; i--) {
            UndoRecord u = undo.get(i);
            Table t = u.table;
            touched.add(t);
            if (u.op == UndoOp.TRUNCATE) {
                t.autoIncValue = u.autoIncBefore;      // 行本身由逐条 DELETE 记录还原
                undone++;
                continue;
            }
            if (u.slot < 0) {
                continue;
            }
            while (t.rows.size() <= u.slot) {
                t.rows.add(null);                      // 恢复场景下槽位可能尚未分配
            }
            switch (u.op) {
                case INSERT -> {
                    // 插入的逆操作：把这个槽重新清空
                    t.rows.set(u.slot, null);
                    if (u.wasDeletedSlot) {
                        t.deletedSlots.put(u.slot, true);   // 原本是"删除标记空位"，标记要补回去
                    } else {
                        t.deletedSlots.remove(u.slot);
                    }
                    t.dirtySlots.add(u.slot);
                    undone++;
                }
                case UPDATE, DELETE -> {
                    // 更新/删除的逆操作：把改前影像原样写回同一个槽（原地还原）
                    if (u.before != null) {
                        t.rows.set(u.slot, u.before.clone());
                        t.deletedSlots.remove(u.slot);
                        t.dirtySlots.add(u.slot);
                        undone++;
                    }
                }
            }
        }
        for (Table t : touched) {
            rebuildIndexes(t);   // 行内容变了，索引必须跟着重建
        }
        return undone;
    }

    /** 回滚后把被改过的表重新落盘：事务期间若执行过 CHECKPOINT，未提交的数据可能已经写到磁盘上了，
     *  只改内存会让磁盘与内存长期不一致。 */
    private void persistUndoneTables(Txn txn) throws EngineException {
        for (Map.Entry<Table, String> e : txn.dirty.entrySet()) {
            Table t = e.getKey();
            Database d = databases.get(e.getValue());
            if (d != null && d.tables.get(t.name) == t) {   // 表还在（没被 DROP）才落盘
                persistTable(e.getValue(), t);
            }
        }
    }

    /**
     * 分配一个记录位置：<b>先找空位</b>——从头扫分配表，遇到第一个空槽就插进去（这正是要求里
     * "插入时先寻找文件中的记录空位，插入到第一个空位中"）；一个空位都没有时才追加到末尾。
     * 追加到末尾时如果槽也不够了，落盘阶段会按区扩展文件（见 {@link #syncSlotFile}）。
     */
    private static int allocSlot(Table t) {
        return allocSlot(t, new boolean[1]);
    }

    /**
     * 分配槽位的内部实现。{@code wasDeleted[0]} 返回该槽在本次分配前是否为"删除标记空位"
     * —— 插入回滚时需要据此决定要不要把删除标记补回去，才能还原成和插入前<b>一模一样</b>的状态。
     */
    private static int allocSlot(Table t, boolean[] wasDeleted) {
        for (int i = 0; i < t.rows.size(); i++) {
            if (t.rows.get(i) == null) {
                wasDeleted[0] = t.deletedSlots.containsKey(i);
                t.deletedSlots.remove(i);   // 该空位重新启用，不再是"删除标记"
                return i;
            }
        }
        wasDeleted[0] = false;              // 追加的新槽：从未用过，不是删除留下的
        t.rows.add(null);
        return t.rows.size() - 1;
    }

    // ===================== 日志系统（WAL）=====================

    /** 写一条日志记录。user/txn 从会话中自动带入。 */
    private WalManager.WalRecord walLog(SessionContext session, String op, String db, String table,
                                        String detail, int rows, Map<String, Object> payload) {
        if (wal == null) {
            return null;
        }
        String user = session == null ? null : session.getUser();
        String txn = (session != null && session.isInTransaction()) ? session.getSessionId() : null;
        return wal.append(op, db, table, detail, rows, user, txn, payload);
    }

    /**
     * 崩溃恢复（redo）：重放最后一次 CHECKPOINT 之后<b>已提交</b>的日志。
     * <p>关键规则：只有出现 COMMIT 记录的事务才会被重放；出现 ROLLBACK、或既没提交也没回滚的事务
     * （崩溃时正在进行中）一律丢弃 —— 这就是"崩溃时未提交事务自动回滚"。</p>
     * <p>所有重放动作都是<b>幂等</b>的（插入已存在的主键就跳过、删除不存在的行就跳过），
     * 所以即使数据其实已经落过盘，重放一遍也不会出错。</p>
     *
     * @return 实际重放的记录条数
     */
    private long recoverFromWal() throws EngineException {
        if (wal == null) {
            return 0;
        }
        List<WalManager.WalRecord> recs = wal.readSinceCheckpoint();
        if (recs.isEmpty()) {
            return 0;
        }
        Set<String> committed = new java.util.HashSet<>();
        Set<String> aborted = new java.util.HashSet<>();
        for (WalManager.WalRecord r : recs) {
            if (WalManager.OP_COMMIT.equals(r.op) && r.txn != null) {
                committed.add(r.txn);
            } else if (WalManager.OP_ROLLBACK.equals(r.op) && r.txn != null) {
                aborted.add(r.txn);
            }
        }
        long applied = 0;
        for (WalManager.WalRecord r : recs) {
            if (r.txn != null && !committed.contains(r.txn)) {
                continue;                     // 未提交（或已回滚）的事务不重放
            }
            if (aborted.contains(r.txn)) {
                continue;
            }
            try {
                if (applyWalRecord(r)) {
                    applied++;
                }
            } catch (Exception ignore) {
                // 单条重放失败不影响其余记录
            }
        }
        if (applied > 0) {
            System.out.println("[KUN-DB] 崩溃恢复：从 CHECKPOINT(" + wal.checkpointLsn()
                    + ") 之后重放 " + applied + " 条日志");
        }
        // ---- undo：把"崩溃时未提交"的事务用 undo 日志真正回滚掉 ----
        // 光"不重放"是不够的：事务执行期间若执行过 CHECKPOINT，未提交的改动其实已经落到磁盘上了，
        // 这时必须靠 undo 日志里的改前影像把它们撤销回去（ARIES 的 "undo 阶段"）。
        Set<String> needUndo = new java.util.LinkedHashSet<>();
        for (WalManager.WalRecord r : recs) {
            if (r.txn != null && !committed.contains(r.txn)) {
                needUndo.add(r.txn);
            }
        }
        long undone = 0;
        for (String txnId : needUndo) {
            List<WalManager.WalRecord> undos = new ArrayList<>();
            for (WalManager.WalRecord r : recs) {
                if (WalManager.OP_UNDO.equals(r.op) && txnId.equals(r.txn)) {
                    undos.add(r);
                }
            }
            for (int i = undos.size() - 1; i >= 0; i--) {    // 逆序：后发生的改动先撤销
                try {
                    if (applyUndoRecord(undos.get(i))) {
                        undone++;
                    }
                } catch (Exception ignore) {
                    // 单条撤销失败不影响其余记录
                }
            }
        }
        if (undone > 0) {
            System.out.println("[KUN-DB] 崩溃恢复：用 undo 日志回滚 " + needUndo.size()
                    + " 个未提交事务，撤销 " + undone + " 行改动");
        }
        return applied;
    }

    /** 把一条 undo 日志（OP_UNDO）应用到内存：按槽号把改前影像写回。返回 true 表示确实产生了改变。 */
    private boolean applyUndoRecord(WalManager.WalRecord r) {
        if (r.payload == null || r.db == null || r.table == null) {
            return false;
        }
        Database db = databases.get(r.db);
        if (db == null) {
            return false;
        }
        Table t = db.tables.get(r.table);
        if (t == null) {
            return false;
        }
        String op = String.valueOf(r.payload.get("op"));
        long autoInc = r.payload.get("autoInc") == null ? 0L
                : ((Number) r.payload.get("autoInc")).longValue();
        if ("TRUNCATE".equals(op)) {
            t.autoIncValue = autoInc;                    // 行由同事务的 DELETE 型 undo 逐条还原
            return true;
        }
        int slot = r.payload.get("slot") == null ? -1 : ((Number) r.payload.get("slot")).intValue();
        if (slot < 0) {
            return false;
        }
        while (t.rows.size() <= slot) {
            t.rows.add(null);
        }
        if ("INSERT".equals(op)) {
            // 插入的逆操作：槽位重新清空，删除标记按原样补回
            if (t.rows.get(slot) == null) {
                return false;                            // 已经是空位，幂等跳过
            }
            t.rows.set(slot, null);
            if (Boolean.TRUE.equals(r.payload.get("wasDeletedSlot"))) {
                t.deletedSlots.put(slot, true);
            } else {
                t.deletedSlots.remove(slot);
            }
            t.dirtySlots.add(slot);
            return true;
        }
        Object beforeRaw = r.payload.get("before");
        if (!(beforeRaw instanceof List<?> bl)) {
            return false;
        }
        t.rows.set(slot, toRow(t, bl));                  // UPDATE / DELETE：写回改前影像
        t.deletedSlots.remove(slot);
        t.dirtySlots.add(slot);
        return true;
    }

    /** 把一条日志重放到内存（幂等）。返回 true 表示确实产生了改变。 */
    private boolean applyWalRecord(WalManager.WalRecord r) throws EngineException {
        if (r.op == null) {
            return false;
        }
        switch (r.op) {
            case WalManager.OP_INSERT, WalManager.OP_DELETE, WalManager.OP_UPDATE -> {
                Database db = r.db == null ? null : databases.get(r.db);
                if (db == null) {
                    return false;
                }
                Table t = db.tables.get(r.table);
                if (t == null) {
                    return false;
                }
                List<?> row = r.payload == null ? null : (List<?>) r.payload.get(
                        WalManager.OP_UPDATE.equals(r.op) ? "new" : "row");
                if (row == null) {
                    return false;
                }
                Object[] vals = toRow(t, row);
                int idx = indexOfRowByKey(t, vals);
                if (WalManager.OP_INSERT.equals(r.op)) {
                    if (idx >= 0) {
                        return false;         // 已存在，幂等跳过
                    }
                    int slot = allocSlot(t);  // 优先复用被删除标记留下的空位
                    t.rows.set(slot, vals);
                    t.dirtySlots.add(slot);
                    return true;
                }
                if (WalManager.OP_DELETE.equals(r.op)) {
                    if (idx < 0) {
                        return false;
                    }
                    t.rows.set(idx, null);    // 只做删除标记，不移动后面的行
                    t.deletedSlots.put(idx, true);
                    t.dirtySlots.add(idx);
                    return true;
                }
                // UPDATE：按主键（无主键则按整行）定位后整体替换为新值
                List<?> old = (List<?>) (r.payload == null ? null : r.payload.get("old"));
                int oi = old == null ? idx : indexOfRowByKey(t, toRow(t, old));
                if (oi < 0) {
                    return false;
                }
                t.rows.set(oi, vals);
                t.dirtySlots.add(oi);
                return true;
            }
            case WalManager.OP_CREATE_DB -> {
                if (r.db != null && !databases.containsKey(r.db)) {
                    databases.put(r.db, new Database(r.db));
                    persistDatabase(r.db);
                    return true;
                }
                return false;
            }
            case WalManager.OP_DROP_DB -> {
                if (r.db != null && databases.remove(r.db) != null) {
                    deleteDatabase(r.db);
                    return true;
                }
                return false;
            }
            case WalManager.OP_CREATE_TABLE -> {
                Database db = r.db == null ? null : databases.get(r.db);
                if (db == null || r.table == null) {
                    return false;
                }
                if (!db.tables.containsKey(r.table)) {
                    Table nt = new Table(r.table);
                    if (r.payload != null && r.payload.get("columns") instanceof List<?> cl) {
                        for (Object o : cl) {
                            if (!(o instanceof Map<?, ?> cm)) {
                                continue;
                            }
                            @SuppressWarnings("unchecked")
                            Map<String, Object> ccm = (Map<String, Object>) cm;
                            nt.cols.add(new ColumnDef((String) ccm.get("name"),
                                    ColumnType.fromName(String.valueOf(ccm.get("type"))),
                                    !Boolean.FALSE.equals(ccm.get("nullable")),
                                    Boolean.TRUE.equals(ccm.get("pk")),
                                    ccm.get("def"), false, false,
                                    ccm.get("comment") == null ? "" : String.valueOf(ccm.get("comment"))));
                        }
                    }
                    if (r.payload != null && r.payload.get("comment") != null) {
                        nt.comment = String.valueOf(r.payload.get("comment"));
                    }
                    db.tables.put(r.table, nt);
                    return true;
                }
                return false;
            }
            case WalManager.OP_DROP_TABLE -> {
                Database db = r.db == null ? null : databases.get(r.db);
                if (db != null && db.tables.remove(r.table) != null) {
                    return true;
                }
                return false;
            }
            case WalManager.OP_CREATE_VIEW -> {
                Database db = r.db == null ? null : databases.get(r.db);
                String body = r.payload == null ? null : (String) r.payload.get("select");
                if (db != null && r.table != null && body != null && !db.views.containsKey(r.table)) {
                    db.views.put(r.table, body);
                    return true;
                }
                return false;
            }
            case WalManager.OP_DROP_VIEW -> {
                Database db = r.db == null ? null : databases.get(r.db);
                if (db != null && db.views.remove(r.table) != null) {
                    return true;
                }
                return false;
            }
            default -> {
                return false;   // BEGIN/COMMIT/ROLLBACK/CHECKPOINT/权限类无需重放
            }
        }
    }

    /** 按主键定位行；无主键时按整行比较（保证 redo 幂等）。找不到返回 -1。 */
    private static int indexOfRowByKey(Table t, Object[] vals) {
        int pk = -1;
        for (int i = 0; i < t.cols.size(); i++) {
            if (t.cols.get(i).pk) {
                pk = i;
                break;
            }
        }
        for (int i = 0; i < t.rows.size(); i++) {
            Object[] row = t.rows.get(i);
            if (row == null) {
                continue;                 // 空位不是记录，不参与定位
            }
            if (pk >= 0) {
                if (pk < row.length && pk < vals.length && Objects.equals(norm(row[pk]), norm(vals[pk]))) {
                    return i;
                }
            } else if (Arrays.deepEquals(row, vals)) {
                return i;
            }
        }
        return -1;
    }

    /** 数值归一化：JSON 读回的整型可能是 Integer/Long/Double，统一成 Long 才能与内存中的值相等比较。 */
    private static Object norm(Object v) {
        if (v instanceof Number n) {
            if (v instanceof Double || v instanceof Float) {
                return n.doubleValue();
            }
            return n.longValue();
        }
        return v;
    }

    /** 把 JSON 读回的 List 转成 Object[]，并按列声明类型做一次强制转换。 */
    private Object[] toRow(Table t, List<?> list) {
        Object[] out = new Object[t.cols.size()];
        for (int i = 0; i < t.cols.size(); i++) {
            Object v = i < list.size() ? list.get(i) : null;
            out[i] = i < t.cols.size() ? coerce(t.cols.get(i), v) : v;
        }
        return out;
    }

    // ===================== 表空间 =====================

    /** 表所在的表空间（默认 kundspace）。 */
    private String tsOf(String db, String table) {
        return tspaces == null ? TablespaceManager.DEFAULT_TS : tspaces.tablespaceOf(db + "." + table);
    }

    /** 表结构的日志 payload（redo 时用于重建表结构）。 */
    private static Map<String, Object> tablePayload(Table t) {
        Map<String, Object> p = new LinkedHashMap<>();
        List<Map<String, Object>> cols = new ArrayList<>();
        for (ColumnDef c : t.cols) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", c.name);
            m.put("type", c.type.name());
            m.put("nullable", c.nullable);
            m.put("pk", c.pk);
            m.put("def", c.def);
            if (c.comment != null && !c.comment.isEmpty()) {
                m.put("comment", c.comment);
            }
            cols.add(m);
        }
        p.put("columns", cols);
        if (t.comment != null && !t.comment.isEmpty()) {
            p.put("comment", t.comment);
        }
        return p;
    }

    // ===================== UPDATE / DELETE =====================

    /** SET 子句的一项：ci=目标列下标；baseCol>=0 表示取同行该列值，否则用 literal；
     *  aop!=null 表示算术赋值（如 col = col + 1），rhsCol/rhsLit 为右操作数。 */
    static final class SetExpr {
        final int ci;
        final int baseCol;
        final Object literal;
        final String aop;
        final int rhsCol;
        final Object rhsLit;

        SetExpr(int ci, int baseCol, Object literal, String aop, int rhsCol, Object rhsLit) {
            this.ci = ci;
            this.baseCol = baseCol;
            this.literal = literal;
            this.aop = aop;
            this.rhsCol = rhsCol;
            this.rhsLit = rhsLit;
        }
    }

    private QueryResult doUpdate(SessionContext session, Ts ts) throws EngineException {
        ts.expectKw("UPDATE");
        String[] dt = parseQualifiedTable(session, ts);
        String dbName = dt[0];
        Table t = requireTable(dbName, dt[1]);
        requirePriv(session, dbName, t.name, Priv.UPDATE);
        String tableAlias = eatTableAlias(ts, "SET"); // 支持 `UPDATE db.tbl alias SET ...`（DataGrip 表编辑器生成形态）
        ts.expectKw("SET");
        List<SetExpr> sets = new ArrayList<>();
        while (true) {
            String cname = ts.expect(TokenType.IDENT, "列名").text;
            while (ts.peek().type == TokenType.DOT) {
                // SET 子句支持 `alias.col` 限定写法；stripBackticks 把 `` t.`t.point` `` 变成
                // `t. t.point`（两段 DOT），循环取末段为列名
                ts.next();
                cname = ts.expect(TokenType.IDENT, "列名").text;
            }
            int ci = t.indexOf(cname);
            if (ci < 0 && cname.contains(".")) {
                // 兼容 DataGrip 旧元数据缓存生成的 `t.`t.point`` 形态（别名.带前缀列名）
                ci = t.indexOf(bare(cname));
            }
            if (ci < 0) {
                throw new EngineException(ErrorCode.UNKNOWN_COLUMN, "未知列: " + cname);
            }
            ts.expect(TokenType.OP, "=");
            Operand base = parseOperand(ts);
            int baseCol = base.colRef != null ? t.indexOf(bare(base.colRef)) : -1;
            if (base.colRef != null && baseCol < 0) {
                throw new EngineException(ErrorCode.UNKNOWN_COLUMN, "未知列: " + base.colRef);
            }
            String aop = null;
            int rhsCol = -1;
            Object rhsLit = null;
            if (ts.peek().type == TokenType.OP
                    && (ts.peek().text.equals("+") || ts.peek().text.equals("-")
                    || ts.peek().text.equals("*") || ts.peek().text.equals("/"))) {
                aop = ts.next().text;
                Operand rhs = parseOperand(ts);
                rhsCol = rhs.colRef != null ? t.indexOf(bare(rhs.colRef)) : -1;
                rhsLit = rhs.colRef != null ? null : rhs.literal;
            }
            sets.add(new SetExpr(ci, baseCol, base.literal, aop, rhsCol, rhsLit));
            if (ts.eat(TokenType.COMMA)) {
                continue;
            }
            break;
        }
        Pred where = ts.eatKw("WHERE") ? parseWhere(ts) : null;
        Combined c = singleTableCombined(t, tableAlias);
        List<Object[]> matched = filterTableRows(t, c, where);
        // 并发控制：UPDATE 是"当前读 + 写"，先拿表级意向排他锁 IX（表示"我要改这张表的行"），
        // 再对被改的每一行拿行级 X 锁 —— 同一行只允许一个事务改，不同行可并行。
        lockTable(session, dbName, t, LockManager.Mode.IX);
        for (Object[] r : matched) {
            Object[] before = r.clone();   // 改前值（redo 时用它定位行，undo 时用它还原）
            int slot = t.rows.indexOf(r);  // 槽号：更新必须落在原槽上，才是"原地修改"
            lockRow(session, dbName, t, slot, LockManager.Mode.X);
            // undo 日志：记下改前影像，ROLLBACK 时按同一槽号原样写回
            recordUndo(session, dbName, t, UndoOp.UPDATE, slot, before, false, -1);
            for (SetExpr e : sets) {
                Object val = e.baseCol >= 0 ? r[e.baseCol] : e.literal;
                if (e.aop != null) {
                    Object rv = e.rhsCol >= 0 ? r[e.rhsCol] : e.rhsLit;
                    val = applyArith(val, e.aop, rv);
                }
                r[e.ci] = coerce(t.cols.get(e.ci), val);
            }
            checkRowConstraints(t, r, r); // 改后校验唯一 / CHECK / 外键（跳过当前行本身）
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("old", Arrays.asList(before));
            p.put("new", Arrays.asList(r));
            if (slot >= 0) {
                t.dirtySlots.add(slot);
            }
            walLog(session, WalManager.OP_UPDATE, dbName, t.name, "update row", 1, p);
        }
        touch(session, dbName, t);
        return QueryResult.ofAffected(matched.size());
    }

    private QueryResult doDelete(SessionContext session, Ts ts) throws EngineException {
        ts.expectKw("DELETE");
        ts.expectKw("FROM");
        String[] dt = parseQualifiedTable(session, ts);
        String dbName = dt[0];
        Table t = requireTable(dbName, dt[1]);
        requirePriv(session, dbName, t.name, Priv.DELETE);
        String tableAlias = eatTableAlias(ts, "WHERE"); // 支持 `DELETE FROM db.tbl alias WHERE ...`
        Pred where = ts.eatKw("WHERE") ? parseWhere(ts) : null;
        Combined c = singleTableCombined(t, tableAlias);
        List<Object[]> matched = filterTableRows(t, c, where);
        // 并发控制：同 UPDATE，表级 IX + 被删行的行级 X 锁
        lockTable(session, dbName, t, LockManager.Mode.IX);
        for (Object[] r : matched) {
            // 行级 redo 日志：记录被删除的完整行，崩溃后按主键幂等删除
            walLog(session, WalManager.OP_DELETE, dbName, t.name, "delete row", 1,
                    WalManager.rowPayload("row", r));
            // 记录随机读写策略之"删除"：只在这条记录的槽上打删除标记，
            // 既不把后面的行往前挪，也不动数据区里已经写好的字节。
            int slot = t.rows.indexOf(r);
            lockRow(session, dbName, t, slot, LockManager.Mode.X);
            // undo 日志：记下被删行的完整内容与槽号，ROLLBACK 时原位复活
            recordUndo(session, dbName, t, UndoOp.DELETE, slot, r, false, -1);
            if (slot >= 0) {
                t.rows.set(slot, null);
                t.deletedSlots.put(slot, true);
                t.dirtySlots.add(slot);
            }
        }
        int removed = matched.size();
        touch(session, dbName, t);
        return QueryResult.ofAffected(removed);
    }

    private Combined singleTableCombined(Table t) {
        return singleTableCombined(t, null);
    }

    /** 单表 Combined：列名以 `前缀.列名` 命名。prefix 为表别名（未起别名时传 null，用表名），
     *  这样 UPDATE/DELETE 的 WHERE 里 `alias.col` 限定写法也能正确解析（贴近真实 MySQL 行为）。 */
    private Combined singleTableCombined(Table t, String prefix) {
        String p = prefix != null ? prefix : t.name;
        Combined c = new Combined();
        lastTableRows = new ArrayList<>();
        lastTables = new ArrayList<>();
        lastTableRows.add(t.rows);
        lastTables.add(t);
        for (ColumnDef cd : t.cols) {
            String qualName = p + "." + cd.name;
            c.outCols.add(new ColumnDef(qualName, cd.type, cd.nullable, cd.pk, cd.def));
            c.src.add(new int[]{0, t.indexOf(cd.name)});
        }
        return c;
    }

    /** 解析 `[db.]table` 目标（分词器把点号拆成 DOT token）。返回 String[]{dbName, tableName}。 */
    private String[] parseQualifiedTable(SessionContext session, Ts ts) throws EngineException {
        String first = ts.expect(TokenType.IDENT, "表名").text;
        String tname = first;
        if (ts.peek().type == TokenType.DOT) {
            ts.next();
            tname = ts.expect(TokenType.IDENT, "表名").text;
        }
        String dbName = tname.equals(first) ? requireDb(session).name : first;
        return new String[]{dbName, tname};
    }

    /** 吃掉可选的表别名（`alias` 或 `AS alias`）。stopKw 为后续子句关键字（UPDATE→SET，DELETE→WHERE），
     *  遇到即停；返回别名（无别名返回 null）。 */
    private String eatTableAlias(Ts ts, String stopKw) throws EngineException {
        if (ts.eatKw("AS")) {
            return ts.expect(TokenType.IDENT, "别名").text;
        }
        if (ts.peek().type == TokenType.IDENT && !ts.peek().text.equalsIgnoreCase(stopKw)) {
            return ts.next().text;
        }
        return null;
    }

    private Object applyArith(Object a, String op, Object b) {
        double x = ((Number) toNum(a)).doubleValue();
        double y = ((Number) toNum(b)).doubleValue();
        return switch (op) {
            case "+" -> x + y;
            case "-" -> x - y;
            case "*" -> x * y;
            case "/" -> y == 0 ? 0 : x / y;
            default -> x;
        };
    }

    private Number toNum(Object o) {
        if (o instanceof Number) {
            return (Number) o;
        }
        if (o instanceof String) {
            try {
                return Double.parseDouble((String) o);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    /** 把 SET 计算得到的值 coerce 回目标列声明的类型（如 INT 列上的算术结果 Double 转回 Integer）。 */
    private Object coerce(ColumnDef cd, Object v) {
        if (v == null) {
            return null;
        }
        switch (cd.type) {
            case INT -> {
                if (v instanceof Number) {
                    return ((Number) v).intValue();
                }
                return v;
            }
            case BIGINT -> {
                if (v instanceof Number) {
                    return ((Number) v).longValue();
                }
                return v;
            }
            case DOUBLE, DECIMAL -> {
                if (v instanceof Number) {
                    return ((Number) v).doubleValue();
                }
                return v;
            }
            case BOOLEAN -> {
                if (v instanceof Boolean) {
                    return v;
                }
                if (v instanceof Number) {
                    return ((Number) v).doubleValue() != 0;
                }
                String s = String.valueOf(v);
                return !"0".equals(s) && !"false".equalsIgnoreCase(s);
            }
            case VARCHAR, CHAR, TEXT -> {
                return String.valueOf(v);
            }
            default -> {
                return v;
            }
        }
    }

    // ===================== 词法位置辅助 =====================

    static final class Ts {
        private final List<Token> t;
        private int i = 0;

        Ts(List<Token> t) {
            this.t = t;
        }

        boolean hasNext() {
            return i < t.size() && t.get(i).type != TokenType.EOF;
        }

        Token peek() {
            return t.get(i);
        }

        Token peekAt(int off) {
            int k = i + off;
            return k < t.size() ? t.get(k) : new Token(TokenType.EOF, "");
        }

        Token next() {
            return t.get(i++);
        }

        Token expect(TokenType ty, String what) throws EngineException {
            Token x = peek();
            if (x.type != ty) {
                throw new EngineException(ErrorCode.SYNTAX_ERROR, "期望 " + what + " 但得到 '" + x.text + "'");
            }
            return next();
        }

        void expectKw(String kw) throws EngineException {
            Token x = peek();
            if (x.type != TokenType.IDENT || !x.text.equalsIgnoreCase(kw)) {
                throw new EngineException(ErrorCode.SYNTAX_ERROR, "期望关键字 " + kw + " 但得到 '" + x.text + "'");
            }
            next();
        }

        boolean eat(TokenType ty) {
            if (hasNext() && peek().type == ty) {
                i++;
                return true;
            }
            return false;
        }

        boolean eatKw(String kw) {
            if (hasNext() && peek().type == TokenType.IDENT && peek().text.equalsIgnoreCase(kw)) {
                i++;
                return true;
            }
            return false;
        }
    }
}
