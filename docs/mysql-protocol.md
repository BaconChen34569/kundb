# KUN-DB 线协议规格（MySQL 线协议）

> 版本：v1.0 ｜ 状态：**现行实现（冻结）**
> 适用：KUN-DB 服务端（Java / Netty） ↔ 任意 MySQL 客户端（DataGrip / Navicat / Qt QMYSQL / mysql 命令行 / JDBC）
> 作用：本文件是「附加功能2：执行引擎与交互界面分离（远程访问）」的通信契约。采用标准 MySQL 线协议，使标准工具无需定制适配器即可直连。

---

## 1. 为什么是 MySQL 线协议（而非自研帧）

用户需求：KUN-DB 要能被 DataGrip 等 GUI 工具、以及后续自研的 Qt 客户端同时连接。
标准 MySQL 线协议是业界事实标准，DataGrip / Navicat / Qt `QMYSQL` 驱动 / `mysql` 命令行 / JDBC 原生支持。
采用它，意味着 **「KUN-DB 在客户端看来就是一台 MySQL 服务器」**，零适配成本。

> 旧版自研帧协议（`docs/protocol.md`）已退役。

---

## 2. 传输层

- 传输：**TCP**。服务端用 Netty 监听（默认端口 `FrameConstants.DEFAULT_PORT = 8020`，可用 `--port` 覆盖；8020 为项目约定端口，与标准 MySQL 3306 区分，且本机 3306 已被真实 MySQL 8.0.41 占用）。
- 分包：每个 MySQL 数据包格式为 `[3 字节小端长度][1 字节 seq][payload]`，长度不含头 4 字节；单包上限 16 MB（`SimpleInMemoryEngine`/`MysqlPacketDecoder` 检查，超界断连）。

```
+--------+--------+--------+--------+--------------+
| len(3B)| seq(1B)|       payload (len 字节)      |
+--------+--------+--------+--------+--------------+
```

---

## 3. 连接握手（Protocol v10）

服务端 `channelActive` 时发送 **Handshake v10** 包（实现见 `MysqlHandshake.build`）：

| 字段 | 内容 |
|---|---|
| protocol version | `0x0A` |
| server version | NUL 结尾字符串，当前为 `KUN-DB-0.1` |
| connection id | 4 字节 |
| auth-plugin-data (part1) | 8 字节 |
| filler | `0x00` |
| capability flags (low 2B) | 含 `CLIENT_PROTOCOL_41` / `CLIENT_SECURE_CONNECTION` / `CLIENT_PLUGIN_AUTH` |
| character set | 1 字节 |
| status flags | 2 字节（含 `SERVER_STATUS_AUTOCOMMIT`） |
| capability flags (high 2B) | |
| length of auth-plugin-data | 1 字节 |
| reserved | 10 字节 `0x00` |
| auth-plugin-data (part2) | 12 字节（末字节 NUL） |
| auth plugin name | NUL 结尾：`mysql_native_password` |

握手包中嵌入 **20 字节 scramble**（由 `MysqlHandshake.newScramble()` 生成，part1 8B + part2 12B），用于对客户端做挑战应答鉴权。

### 客户端握手响应（HandshakeResponse41）

`[capabilities(4B)][max-packet(4B)][charset(1B)][reserved(23B)][username NUL][auth-response 长度编码][db NUL?][auth-plugin-name NUL?]`

- **关键约定**：仅当客户端声明 `CLIENT_CONNECT_WITH_DB (0x08)` 时才在 auth-response 之后读「库名」。否则后续字节是 auth 插件名，服务端**不应**误读为库名（这是早期联调踩过的坑，已在 `handleHandshakeResponse` 修正）。

---

## 4. 鉴权（mysql_native_password）

挑战应答公式（实现见 `NativeAuth.verify`）：

```
token = SHA1( SHA1(password) )  XOR  SHA1( scramble + SHA1(SHA1(password)) )
```

- 服务端用 `engine.lookupPassword(username)` 取出明文密码，按上式算出期望 token，与客户端回传 token 比对。
- 校验通过 → 发 `OK_Packet (0x00)`；失败 → 发 `ERR_Packet (1045, "Access denied ...")`。
- 默认账号：`root` / `123456`（见 `SimpleInMemoryEngine.init()` 中 `users.put("root","123456")`）。

---

## 5. 命令阶段

鉴权后进入命令循环，由 `MysqlServerHandler.handleCommand` 分发（类型见 `MysqlPacket` payload 首字节）：

| 命令 | 字节 | 行为 |
|---|---|---|
| `COM_QUERY` | `0x03` | payload 为 SQL 文本。`routeMetadata` 先拦截元数据类查询（见 §7），其余交给 `engine.execute(session, sql)`（已剥离反引号）。 |
| `COM_PING` | `0x0E` | 回 `OK_Packet`，用于保活探测。 |
| `COM_INIT_DB` | `0x02` | 切换当前库；库不存在回 `ERR 1049`。 |
| `COM_QUIT` | `0x01` | 关闭连接。 |
| 其它 | — | 回 `OK_Packet`，避免标准工具因未知命令报错。 |

> 心跳：`IdleStateHandler(90s)` 读空闲即断开。

---

## 6. 结果集编码

`sendResultSet` 按 MySQL 文本协议编码：

1. **列数**：长度编码整数（lenenc int）。
2. **列定义**：每个列一个 `ColumnDefinition41`（schema/table/org_table/name/org_name/charset/column_length/type/flags），由 `MysqlCodec.columnDef` + `typeMapping(ColumnType)` 生成（`MysqlCodec` 中维护类型→MySQL 类型/字符集/长度/标志的映射）。
3. **EOF_Packet (`0xFE`)**：列定义后。
4. **行数据**：每行各字段用长度编码字符串（`NULL` 用 `0xFB`）；由 `MysqlCodec.writeRowValue` 编码。
5. **EOF_Packet (`0xFE`)**：行结束后。

非结果集命令（INSERT/DDL）回 `OK_Packet(affectedRows, ...)`；错误回 `ERR_Packet`，错误码映射见 `sendErr`：

| 语义 | MySQL 码 |
|---|---|
| ACCESS_DENIED / AUTH_REQUIRED | 1045 |
| SYNTAX_ERROR | 1064 |
| UNKNOWN_DATABASE | 1049 |
| UNKNOWN_TABLE | 1146 |
| UNKNOWN_COLUMN | 1054 |
| DUPLICATE_KEY | 1062 |
| TYPE_MISMATCH | 1366 |
| PROTOCOL_ERROR / 其它 | 1105 / 1205 |

---

## 7. 元数据兼容（供 DataGrip / Navicat 探测）

为让标准工具「打开连接就能看到库/表/字段」，服务端对以下查询做路由与翻译（`routeMetadata` / `routeShow` / `routeInfoSchema`），不直接走引擎：

- `SET ...`（autocommit / names 等）：忽略，回 `OK`。
- `SELECT 1` / `SELECT 1+1` / `SELECT VERSION()`。
- `SELECT @@version` / `SELECT @@xxx`：返回对应系统变量（version=`KUN-DB-0.1`，version_comment=`KUN-DB simple DBMS`）。
- `SHOW DATABASES` / `SHOW SCHEMAS`
- `SHOW [FULL] TABLES [FROM db]`
- `SHOW [FULL] COLUMNS FROM tbl [FROM db]` / `SHOW FIELDS ...`
- `SHOW CREATE TABLE [db.]tbl`
- `SHOW INDEX|KEYS FROM [db.]tbl`
- `information_schema` 视图：`schemata` / `tables` / `columns` / `statistics`（按 `table_schema` / `table_name` 过滤）

这些查询直接读 `engine.listDatabases() / listTables(db) / describe(db,tbl) / createTableDdl(db,tbl)`，故**无需为元数据新增任何引擎命令**。

---

## 8. 引擎支持的 SQL 子集

`SimpleInMemoryEngine.execute` 覆盖（足以让客户端连上来建库、建表、增删查）：

- `CREATE DATABASE|SCHEMA` / `DROP DATABASE|SCHEMA`
- `USE db`
- `CREATE TABLE t (col type [NOT NULL] [PRIMARY KEY] [DEFAULT x], ...)` / `DROP TABLE t`
- `INSERT INTO t [(c1,c2)] VALUES (...),(...)`
- `SELECT ... FROM t1[, t2] [WHERE a=b AND c>d] [ORDER BY x [ASC|DESC]] [LIMIT n]`、`COUNT(*)`、两表连接（嵌套循环）、`db.table` 限定写法
- `SHOW DATABASES` / `SHOW TABLES` / `DESC tbl`
- 反引号与 `--`/`/* */` 注释在交给引擎前被剥离

> 当前为「最小可用内核」：尚无 `UPDATE`/`DELETE`、聚合（`GROUP BY`/`JOIN ON`）、事务、多用户权限。后续按设计文档 §6.2 用正式 Lexer/Parser/Planner 替换时，保持 `ExecutionEngine` 接口不变即可。

---

## 9. 持久化（重启不丢数据）

`SimpleInMemoryEngine` 为**文件式自研存储**：

- 启动 `init()`：建 `kundb-data/`（可用 `--datadir` 指定）→ 加载账号 → `loadAll()` 扫描目录 → 若空则 `seedDemo()`（演示库 `school`：student/course/score）并落盘。
- 变更落盘：`CREATE/DROP DATABASE` 建/删目录；`CREATE/DROP TABLE`、`INSERT` 写/删 `<db>/<table>.json`。
- 文件格式（JSON，Jackson 序列化）：
  ```json
  { "name": "t", "columns": [{"name":"id","type":"INT","nullable":false,"pk":true,"def":null}], "rows": [[1,"a"],[2,"b"]] }
  ```
- 删除库/表同步清目录，避免脏数据。

由此满足用户需求 2：**数据落在磁盘，进程重启后从 `kundb-data/` 重新加载，历史数据不丢**。端到端验证见 `_mysqltest.py`（含「重启后数据仍在」断言）。

---

## 10. 如何运行与连接

服务端（Java 17，Maven 多模块）：

```bash
# 方式 A：Maven 直接跑
mvn -f pom.xml -pl kundb-server exec:java \
    -Dexec.mainClass=com.kundb.server.KunDbServer \
    -Dexec.args="--port 3307 --datadir kundb-data"

# 方式 B：先 install 再 java -jar（需 shade/assembly；当前用 exec:java 即可）
```

> **本机端口冲突（实测）**：本机 `3306` 已被真实 MySQL 8.0.41（记账系统）占用，故 KUN-DB 在本机使用 **`3307`**。
> 如需占标准 `3306`，请先停掉本机真实 MySQL，再不加 `--port` 启动。

DataGrip / Navicat 连接配置：

| 项 | 值 |
|---|---|
| 类型 | MySQL |
| Host | `localhost` |
| Port | `3307`（本机）/ `3306`（若已停真实 MySQL） |
| User | `root` |
| Password | `123456` |
| Database | `school`（演示库，可空） |
| SSL | 禁用 / 首选（服务端不强制） |
| 字符集 | `utf8mb4` |

连接成功后即可在对象树看到 `school` 下的 `student/course/score`，并直接跑 SQL。

---

## 11. 已知限制

- 仅文本协议（无 `COM_STMT_PREPARE` 预编译语句、无服务端游标）。
- 不支持 SSL（连接建议用「禁用/首选」而非「必需」）。
- SQL 子集有限（见 §8），无 `UPDATE`/`DELETE`/事务/多用户权限。
- 元数据以「兼容 DataGrip 探测」为目标，未 100% 覆盖所有 `information_schema` 视图与列。
- 单默认账号 `root`，无独立权限体系（访问控制为可选功能，后续扩展）。
