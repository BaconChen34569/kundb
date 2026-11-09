package com.kundb.server.engine;

import com.kundb.server.session.SessionContext;

import java.util.List;

/**
 * 执行引擎接口：服务端收到 QUERY 后交给引擎执行，收到 AUTH 时交给引擎校验身份。
 * <p>这是 DBMS 内核对"远程服务端"暴露的最小契约。第二步先用 {@link SimpleInMemoryEngine}
 * 实现一份可跑通协议的最小子集；后续 W3–W7 再替换为正式的 Lexer/Parser/Planner/Executor 内核，
 * 只要保持本接口不变，服务端网络层无需改动。</p>
 */
public interface ExecutionEngine {

    /** 引擎启动时的初始化（建系统库、加载用户等）。 */
    void init() throws Exception;

    /** 校验用户名/密码，成功返回 true。 */
    boolean authenticate(String user, String password) throws EngineException;

    /** 返回该用户的明文密码（MySQL 协议 native_password 校验用）；未知用户返回 null。 */
    String lookupPassword(String user);

    /**
     * 在当前会话上下文下执行一条 SQL，返回结构化结果。
     *
     * @param session 当前会话（含当前库）；执行 USE 等会改变会话状态
     * @param sql     一条 SQL 语句
     */
    QueryResult execute(SessionContext session, String sql) throws EngineException;

    // ===================== 元数据（供 MySQL 协议层兼容 DataGrip）=====================

    boolean databaseExists(String db);

    List<String> listDatabases();

    /** @param db 库名；为 null 时由实现按"当前库"解析（无当前库则抛 UNKNOWN_DATABASE）。 */
    List<String> listTables(String db) throws EngineException;

    /** 返回 [表名, 类型] 列表，类型取值 "BASE TABLE" / "VIEW"，供 SHOW FULL TABLES。 */
    List<List<Object>> listTablesWithType(String db) throws EngineException;

    /** 该对象是否为视图（供 SHOW CREATE TABLE / 元数据探测区分视图与基表）。 */
    boolean isView(String db, String table) throws EngineException;

    /** 返回视图清单：每行 [视图名, 视图定义 SELECT 文本]，供 information_schema.views。 */
    List<List<String>> listViews(String db) throws EngineException;

    /** 返回表上的二级索引信息：每行 [索引名, 列名]（主键由 describe 的 pk 推断，不在此列）。 */
    List<List<String>> listIndexes(String db, String table) throws EngineException;

    List<ColumnMeta> describe(String db, String table) throws EngineException;

    String createTableDdl(String db, String table) throws EngineException;

    /** 表注释；库/表不存在或无注释时返回空串。
     *  供 information_schema.TABLES.TABLE_COMMENT 与 SHOW TABLE STATUS.Comment（DataGrip/Navicat 表列表）。 */
    String tableComment(String db, String table);

    /** 列出表的外键：每行 [子表列, 父表名, 父表列]，供 information_schema.key_column_usage / referential_constraints。 */
    List<String[]> listForeignKeys(String db, String table) throws EngineException;

    // ===================== 连接生命周期（并发控制用）=====================

    /** 连接建立时登记（供 SHOW PROCESSLIST 展示、并沿用全局默认隔离级别）。默认空实现。 */
    default void onConnect(SessionContext session) { }

    /**
     * 连接断开时清理：结束未提交的事务、释放它持有的全部锁。
     * 不做的话，异常断开的连接会把锁永久占住（其他连接会一直等到超时）。默认空实现。
     */
    default void onDisconnect(SessionContext session) { }
}
