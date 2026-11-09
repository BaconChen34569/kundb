# KUN-DB 小型 DBMS 详细设计方案

> 路线：Java 17 全自研内核 · 周期 6–8 周 · 目标冲高分 · 客户端 = CLI + 远程客户端

---

## 1. 目标与功能清单

### 1.1 必选功能（必须 100% 覆盖）

| 功能 | 实现要点 |
|---|---|
| 建库 / 删库 | `CREATE DATABASE` / `DROP DATABASE` / `USE`，一个库对应一个磁盘目录 |
| 建表 / 改表 / 删表 | `CREATE TABLE` / `ALTER TABLE ADD|DROP COLUMN` / `DROP TABLE`，列类型、主键、NOT NULL、DEFAULT |
| 表数据基本操作 | `INSERT` / `SELECT` / `UPDATE` / `DELETE`，含 WHERE 过滤、投影、排序、聚合 |
| 访问控制 | 用户、角色、`GRANT` / `REVOKE`，库级 + 表级权限，会话级身份 |
| 视图 | `CREATE VIEW` / `DROP VIEW`，逻辑视图（存 SQL，查询时展开） |
| 两表连接查询 | `INNER` / `LEFT` / `RIGHT` / `FULL` JOIN，Nested Loop Join + Hash Join |

### 1.2 可选功能（按优先级排序，冲高分建议全做）

| 优先级 | 功能 | 说明 |
|---|---|---|
| P0 | **索引** | B+ 树，支持唯一索引、范围扫描、索引下推 |
| P0 | **日志 / 事务** | WAL + 崩溃恢复 + 检查点，读已提交隔离级别 |
| P1 | **任意多表连接** | 左深树 + 连接顺序选择（贪心 / DP） |
| P1 | **远程访问** | Netty 服务端 + 远程客户端，含握手认证 |
| P2 | **表空间管理** | 区（extent）分配、位图页空闲管理、表段（segment） |
| P2 | 执行计划 | `EXPLAIN` 输出算子树，答辩演示利器 |
| P3 | 物化视图 / 外部排序 / 统计信息 | 有余力再做 |

---

## 2. 技术选型

| 环节 | 选择 | 理由 |
|---|---|---|
| 语言 / JDK | Java 17 | 内存安全、调试快、与现有技术栈一致 |
| 构建 | Maven 多模块 | 模块边界清晰，便于答辩展示分层 |
| SQL 解析 | **手写 Lexer + 递归下降 Parser** | 约 600 行，无第三方依赖，语法可控，答辩时能逐行讲清；ANTLR4 作为备选（省事但"不是自己写的"） |
| JSON（仅远程协议用） | Jackson | 远程协议编解码 |
| 网络（远程访问） | Netty 4.x | 自定义二进制帧协议 |
| CLI | 自研 REPL + ASCII 表格渲染 | 参考 mysql 客户端体验 |
| 测试 | JUnit 5 + 自定义 SQL 脚本回放 | 端到端可回归 |
| 序列化 | 全部手写 `ByteBuffer` 编解码 | 存储层是核心考点，不应依赖框架 |

---

## 3. 工程结构

```
KUN-DB/
├── pom.xml                    父 POM，Java 17，依赖管理
├── kundb-common/              类型系统、异常、字节工具、配置、RID
├── kundb-storage/             页管理、缓冲池、堆表、B+ 树、表空间、WAL
├── kundb-catalog/             系统表、权限模型、视图元数据
├── kundb-sql/                 Lexer、Parser、AST 节点
├── kundb-planner/             Binder（语义分析 + 权限校验）、优化器、物理计划
├── kundb-executor/            火山模型算子、DDL/DML 执行入口
├── kundb-txn/                 事务管理、锁表、WAL 与恢复
├── kundb-server/              Netty 远程服务端、会话、认证
├── kundb-cli/                 交互式命令行客户端
└── kundb-tests/               单元测试 + 集成测试 SQL 脚本
```

依赖方向严格单向：`cli/server → executor → planner → sql/catalog → storage → common`。

---

## 4. 存储引擎设计

### 4.1 磁盘文件组织

```
data/
└─ {database}/
   ├─ kundb.meta        系统目录与表空间元数据（固定前 8 页为元数据区）
   ├─ kundb.wal         预写日志
   ├─ {table}.db        表数据文件（堆表 + 索引统一放在同一文件的不同段）
   └─ {table}.idx       索引文件（可选拆分，便于演示表空间）
```

> 简化决策：每个表一个 `.db` 文件，页号在文件内唯一；RID = `(pageNo << 16) | slotNo`，用 `long` 打包。

### 4.2 页结构（8 KB，Slotted Page）

```
┌──────────────────────────────────────────────┐
│ PageHeader (32 B)                            │
│  pageId:int  pageType:byte  freeOffset:short │
│  slotCount:short  prevPage:int  nextPage:int │
│  pageLSN:long  checksum:int                  │
├──────────────────────────────────────────────┤
│  元组数据区（从低地址向高地址增长）            │
│  ...                                         │
├──────────────────────────────────────────────┤
│  槽目录（从页尾向低地址增长）                  │
│  每个 slot: offset:short + length:short (4 B) │
└──────────────────────────────────────────────┘
```

`pageType`：`0` 元数据 / `1` 数据页 / `2` 索引内部节点 / `3` 索引叶子节点 / `4` 位图页 / `5` 溢出页。

- 空闲空间判断：`freeOffset + slotCount*4 + 4 <= PAGE_SIZE`
- 删除元组：置 slot 的 `offset = -1`（墓碑），空间延后整理（或简单标记复用）
- 变长列超长时使用**溢出页**（`pageType=5`），元组内存 `(overflowPageNo, totalLen)`

### 4.3 行（元组）格式

```
Tuple = [ nullBitmap: ceil(colCount/8) B ][ 定长列区 ][ 变长列区 ]
```

- 定长列：`INT(4) BIGINT(8) DOUBLE(8) DATE(4) DATETIME(8) BOOLEAN(1) CHAR(n)(n)`
- 变长列：`VARCHAR(n)` 前缀 2B 长度 + 实际字节；更新导致行变长且页内空间不足时触发**行迁移**到新页，原位置留转发指针
- `DECIMAL(p,s)` 存为字符串定长编码，避免浮点精度问题

### 4.4 缓冲池（Buffer Pool）

- 固定大小（默认 1024 页 = 8 MB），`ConcurrentHashMap<PageId, Frame>`
- 替换策略：**LRU**（先做，简单可靠）；有余力升级为 **Clock / LRU-K**（加分点，可对比命中率）
- `pin / unpin(dirty)` 引用计数；脏页在 `unpin` 时记录 `pageLSN`
- **刷盘约束（WAL 法则）**：`pageLSN <= flushedLSN` 才允许刷盘，保证 WAL 先于数据落盘
- 页面锁：`ReentrantReadWriteLock` 保护单页并发读写

### 4.5 表空间管理（可选 P2）

- 空间按**区（Extent）**分配，1 区 = 64 页（512 KB），减少随机 I/O
- 每个段（Segment）维护**位图页**记录页空闲状态
- `SHOW TABLESPACES` / `ALTER TABLESPACE ... ADD DATAFILE` 作为演示命令

---

## 5. 系统目录 Catalog

系统表本身也用页存储，通过**固定页号自举**：`kundb.meta` 的第 0 页是目录根（记录各系统表的首页号）。

| 系统表 | 字段 |
|---|---|
| `sys_database` | db_id, name, charset, create_time |
| `sys_table` | table_id, db_id, name, first_page_no, col_count, row_count, create_time |
| `sys_column` | table_id, col_name, type, type_len, nullable, is_primary, default_val, ordinal |
| `sys_index` | index_id, table_id, name, col_names, is_unique, root_page_no |
| `sys_user` | user_id, name, password_hash, host, create_time |
| `sys_role` | role_id, name |
| `sys_user_role` | user_id, role_id |
| `sys_privilege` | principal_type(USER/ROLE), principal_id, obj_type(DB/TABLE), obj_name, privilege, grant_option |
| `sys_view` | view_id, db_id, name, sql_text, owner, create_time |

启动流程：读 `kundb.meta` 第 0 页 → 加载系统表 → 缓存到内存 `CatalogCache`（表结构变更时同步 flush）。

---

## 6. SQL 层

### 6.1 支持的 SQL 子集

```sql
-- DDL
CREATE DATABASE db;  DROP DATABASE db;  USE db;
CREATE TABLE t (id INT PRIMARY KEY, name VARCHAR(32) NOT NULL, age INT DEFAULT 0);
ALTER TABLE t ADD COLUMN email VARCHAR(64);   ALTER TABLE t DROP COLUMN age;
DROP TABLE t;
CREATE [UNIQUE] INDEX idx_name ON t (col);    DROP INDEX idx_name;
CREATE VIEW v AS SELECT ... ;                 DROP VIEW v;

-- DML
INSERT INTO t (c1, c2) VALUES (?, ?), (?, ?);
SELECT [DISTINCT] cols | agg(...) FROM t [alias]
       [JOIN t2 ON ...] [WHERE ...] [GROUP BY ...] [HAVING ...] [ORDER BY ...] [LIMIT n];
UPDATE t SET c = expr [WHERE ...];
DELETE FROM t [WHERE ...];

-- DCL
CREATE USER u IDENTIFIED BY 'pwd';   CREATE ROLE r;
GRANT SELECT, INSERT ON db.t TO u [WITH GRANT OPTION];
REVOKE SELECT ON db.t FROM u;

-- 事务 / 运维
BEGIN;  COMMIT;  ROLLBACK;
EXPLAIN SELECT ...;   SHOW TABLES;   SHOW DATABASES;   DESC t;
```

### 6.2 解析器（手写递归下降）

1. **Lexer**：关键字表 + 标识符 / 数字 / 字符串 / 运算符 / 注释（`--`、`/* */`）
2. **Parser**：按优先级递归下降

```
statement  := selectStmt | insertStmt | updateStmt | deleteStmt
            | createStmt | dropStmt | alterStmt | grantStmt | revokeStmt | txnStmt
selectStmt := SELECT selectList FROM tableRefs
              [WHERE expr] [GROUP BY exprs [HAVING expr]]
              [ORDER BY orderItems] [LIMIT n]
tableRefs  := tableRef (',' tableRef | [INNER|LEFT|RIGHT|FULL] JOIN tableRef ON expr)*
expr       := orExpr
orExpr     := andExpr (OR andExpr)*
andExpr    := eqExpr  (AND eqExpr)*
eqExpr     := relExpr ((=|!=|<>) relExpr)?
relExpr    := addExpr ((<|<=|>|>=|LIKE|IN|BETWEEN) ...)?
addExpr    := mulExpr (('+'|'-') mulExpr)*
mulExpr    := unary   (('*'|'/') unary)*
unary      := ('-'|'NOT')? primary
primary    := literal | columnRef | funcCall | '(' expr ')' | subquery
```

3. **AST**：`SelectStmt / InsertStmt / ... / BinaryExpr / ColumnRef / Literal / FuncCall / SubQuery`

### 6.3 Binder（语义分析）

- 解析当前 `USE` 的库，把表名/列名解析为内部 ID
- **权限校验点就在这里**：根据会话用户检查 `SELECT/INSERT/UPDATE/DELETE` 权限
- **视图展开**：把 `sys_view.sql_text` 解析成子查询替换表引用，递归展开并检测循环依赖
- 类型推导与隐式转换校验
- 输出**逻辑计划**（LogicalPlan 树）

### 6.4 优化器（RBO，规则式）

按序应用：

1. 列裁剪（去除未使用的投影列）
2. 谓词下推（WHERE 条件下推到扫描算子）
3. 常量折叠（`1+2` → `3`）
4. 连接顺序选择：基于表 row_count 估算，小表驱动；N ≤ 6 时用 **DP**（`dp[subset]`），更大用贪心 + 随机交换
5. 连接算法选择：等值连接且一侧可放入内存 → **Hash Join**；内表有索引 → **Index Nested Loop Join**；否则 NLJ
6. 索引选择：WHERE 有索引列等值/范围条件 → `IndexScan`（回表代价估算，选择率 > 20% 时退回全表扫描）
7. 投影/聚合下推、LIMIT 下推

输出**物理计划**（算子树），`EXPLAIN` 直接打印这棵树。

### 6.5 执行器（火山模型）

统一接口：

```java
public interface Operator {
    void open();
    Row next();      // 返回 null 表示结束
    void close();
    Schema outputSchema();
}
```

算子清单：

`SeqScan` `IndexScan` `IndexRangeScan` `Projection` `Filter` `NestedLoopJoin` `HashJoin` `SortMergeJoin` `HashAggregate` `Sort` `Limit` `Distinct` `Insert` `Update` `Delete` `Values`

---

## 7. 索引：B+ 树

### 7.1 节点布局（8 KB 页）

```
内部节点 (pageType=2)：[keyCount:short][keys: keyCount*KEY_LEN][children: (keyCount+1)*4]
叶子节点 (pageType=3)：[keyCount:short][keys: keyCount*KEY_LEN][rids: keyCount*8][nextLeaf:int]
```

- 支持 `INT / BIGINT / DOUBLE / DATE` 作为 key（8 B 定长）；`VARCHAR` 取前 32 字节定长前缀
- 阶数估算：`(8192 - 32) / (8 + 4) ≈ 680`，实际实现时取 `min(计算值, 400)` 便于构造多层树做演示
- 叶子节点用 `nextLeaf` 串联，支持 `ORDER BY` 与范围扫描的顺序 I/O

### 7.2 操作

- **插入**：查找叶子 → 插入 → 溢出则分裂（中间 key 上提）→ 递归向上
- **删除**：查找 → 删除 → 下溢则**先借（再分布）后合并**
- **唯一索引**：插入前先探测，已存在则抛 `DuplicateKeyException`
- **非唯一索引**：key 后追加 RID 组成复合 key 保证唯一
- **并发**：采用 latch crabbing（先做保守版：整树 `ReentrantReadWriteLock`；有余力再改螃蟹锁，这是很好的加分项）

> 风险提示：删除合并是 B+ 树最易出 bug 的地方。**先实现"只分裂不合并"的宽松版本跑通功能，再补再分布/合并**，并写随机插入删除的一致性校验单测。

---

## 8. 事务与日志

### 8.1 WAL 记录格式

```
[ LSN:long ][ txnId:long ][ type:byte ][ pageId:int ][ offset:short ][ len:int ]
[ beforeImage: len B ][ afterImage: len B ][ prevLSN:long ]
```

`type`：`BEGIN / UPDATE / INSERT / DELETE / COMMIT / ABORT / CLR(补偿日志) / CHECKPOINT / END`

### 8.2 分档实现（按时间取舍）

| 档位 | 策略 | 需要什么 | 分数 |
|---|---|---|---|
| **L1 保底** | Force + No-Steal | 事务提交时一次性刷所有脏页；不需要 Undo，日志仅作操作审计 | 及格 |
| **L2 加分（建议）** | Steal + No-Force + 物理 Redo/Undo WAL | 完整 WAL、LSN 链、检查点、崩溃恢复 | 高分 |

### 8.3 崩溃恢复（L2，简化 ARIES）

1. **Analysis**：从最近 `CHECKPOINT` 扫描到日志尾，得到 `脏页表 DPT` 与 `活跃事务表 ATT`
2. **Redo**：从 DPT 中最小的 `recLSN` 开始重放所有 UPDATE 日志（幂等：比较 `pageLSN`）
3. **Undo**：对 ATT 中未提交事务，按 `prevLSN` 逆序回滚，每步写 `CLR` 防止重复回滚

### 8.4 隔离级别与锁

- 先做**单写者 + 多读者**：全局 `ReentrantReadWriteLock`，写事务串行（正确、简单）
- 有余力：实现行级锁（锁表 `HashMap<RID, LockEntry>`）+ **两阶段封锁 2PL**，支持 `READ COMMITTED`

---

## 9. 访问控制

- 身份：会话持有 `currentUser` + `currentDb`，登录后不可变
- 权限类型：`SELECT / INSERT / UPDATE / DELETE / CREATE / DROP / ALTER / INDEX / ALL PRIVILEGES`
- 对象层级：`*.*`（全局）、`db.*`（库级）、`db.table`（表级）
- 检查时机：**Binder 阶段**统一校验，未授权抛 `AccessDeniedException`
- 角色：`GRANT role TO user`，用户权限 = 自身权限 ∪ 角色权限
- 密码存储：SHA-256 + 盐（不要求工业级，但别明文）

---

## 10. 视图

- **逻辑视图**为主：`CREATE VIEW v AS <select>` 只存 SQL 文本到 `sys_view`
- 查询时：解析视图 SQL → 生成子查询 → 替换表引用 → 递归展开（用访问栈检测循环）
- 权限：视图创建者需具备底层表的 SELECT；查询视图时校验创建者对底层表的权限（Definer 语义）
- 加分可选项：物化视图（存实际数据 + 刷新命令）

---

## 11. 多表连接

- **逻辑计划**：`Join(Join(A,B),C)` 左深树
- **连接顺序**：DP 枚举子集（N ≤ 6），代价 = 输出行数估算；行数估算用 `row_count` × 选择率（等值 1/max(distinct,1)，范围 0.3）
- **算法**：
  - `NestedLoopJoin`：必做，通用
  - `HashJoin`：等值连接主力，build 侧放小表
  - `SortMergeJoin`：非等值大表场景（加分）
  - `IndexNestedLoopJoin`：内表有索引时（加分）
- **聚合**：`HashAggregate` 实现 GROUP BY + COUNT/SUM/AVG/MAX/MIN

---

## 12. 远程访问（Netty）

### 12.1 协议

```
帧格式： [ magic:2B=0x4B55 ][ length:4B ][ type:1B ][ payload: length B ]
type:    0x01 AUTH  0x02 QUERY  0x03 RESULT  0x04 ERROR  0x05 PING
payload: JSON（Jackson）
```

### 12.2 流程

1. 客户端 `AUTH {user, password}` → 服务端校验 `sys_user`，建立 Session
2. `QUERY {sql}` → 服务端执行 → `RESULT {columns[], rows[][], affectedRows, elapsedMs}` 或 `ERROR {code, message}`
3. 支持多会话并发，每个连接一个 `SessionContext`（当前库、当前用户、事务状态）

### 12.3 CLI 客户端

- 本地模式：直接调内核 API
- 远程模式：`--host --port --user --password` 走 Netty
- REPL 特性：命令历史、多行语句（以 `;` 结束）、`source file.sql`、ASCII 表格输出、`\d`、`\q`

---

## 13. 测试与演示

| 类型 | 内容 |
|---|---|
| 单元测试 | 页分配/回收、Slot 管理、B+ 树随机插入删除一致性、WAL 编解码、Lexer/Parser、权限矩阵 |
| 集成测试 | `tests/*.sql` 脚本 + 期望输出回放，覆盖全部必选功能 |
| 崩溃恢复测试 | 插一半 `kill -9` 进程 → 重启 → 校验数据一致性（**答辩最有说服力的演示**） |
| 性能对比 | 10 万行插入耗时；全表扫描 vs 索引扫描耗时对比（出图） |
| 远程测试 | 多客户端并发连接、权限隔离 |

---

## 14. 8 周里程碑

| 周 | 主题 | 交付物 |
|---|---|---|
| **W1** | 存储引擎基础 | 页管理、Slot 元组、堆表 CRUD、Buffer Pool + LRU、位图页空闲管理、单测通过 |
| **W2** | 类型系统 + Catalog + DDL | 数据类型体系、`sys_*` 系统表、元数据自举、`CREATE/DROP DATABASE/TABLE`、`ALTER` |
| **W3** | SQL 解析 + Binder | Lexer、递归下降 Parser、AST、语义分析、`INSERT` + 点查 + 全表扫描 |
| **W4** | 执行器 + CLI v1 | 火山模型算子、WHERE/投影/UPDATE/DELETE、CLI 交互式终端（本地模式） |
| **W5** | 连接查询 + 优化器 | NLJ / Hash Join、多表连接顺序（DP）、谓词下推、GROUP BY / ORDER BY / LIMIT |
| **W6** | 索引 + 优化器增强 | B+ 树插入/删除/范围扫描、唯一索引、索引选择、`EXPLAIN` |
| **W7** | 访问控制 + 视图 | 用户/角色、GRANT/REVOKE、会话、逻辑视图展开 |
| **W8** | 事务/日志 + 远程 + 收尾 | WAL、崩溃恢复、检查点、Netty 服务端、远程客户端、测试报告、演示脚本 |

**每周固定产出**：可运行的 demo + 单测 + 一页进度说明。避免最后一周集成爆炸。

---

## 15. 风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| B+ 树删除合并出 bug | 索引不可用 | 先只分裂不合并；写 10 万次随机插入删除后与 TreeMap 对比的一致性单测 |
| WAL 与 Buffer Pool 耦合出错 | 数据不一致 | 放最后做；**L1 保底方案**（Force + No-Steal）随时可切换 |
| 变长列更新导致行迁移复杂 | 存储层返工 | 限制单行 < 4 KB；超出走溢出页，溢出页不支持原地更新 |
| 优化器过度设计 | 时间黑洞 | RBO 固定 7 条规则封顶，不做代价模型的直方图统计 |
| 8 周不够 | 可选功能缺失 | 严格按优先级砍：必选 > 索引 > 日志 > 远程 > 多表优化 > 表空间 |

---

## 16. 答辩加分点清单

- `EXPLAIN` 展示优化前后的算子树对比
- 崩溃恢复现场演示：kill 进程 → 重启 → 数据自愈
- 索引生效前后性能对比图（10 万行数据）
- `SHOW TABLESPACES` 展示区/页分配情况
- 权限矩阵测试：A 用户查不到 B 用户的表
- 视图 + 多表连接 + 聚合的综合查询演示
- B+ 树可视化：把树结构 dump 成文本/图形
