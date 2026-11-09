# KUN-DB 线协议规格（Wire Protocol Specification）

> ⚠️ **已退役（DEPRECATED）**：本文件描述的**自研帧协议**（`[magic:2B=0x4B55][length:4B][type:1B][payload]`）已被 **MySQL 线协议** 取代。
> 原因：用户要求 KUN-DB 能被 DataGrip / Navicat / Qt(QMYSQL) 等标准工具直连；采用 MySQL 线协议后无需为每种客户端写适配器。
> 新协议规格见 **`docs/mysql-protocol.md`**。以下旧内容仅作历史参考，不再作为实现契约。

> 版本：v1.0 ｜ 状态：**冻结（两端实现的唯一契约）**
> 适用：KUN-DB 服务端（Java / Netty） ↔ Qt 客户端（C++ / QTcpSocket）
> 作用：本文件是开发要求「附加功能2：执行引擎与交互界面分离（远程访问）」的通信契约。任何一端改动协议前，必须回到本文件评审并升级版本号。

---

## 1. 目的与范围

KUN-DB 按 C/S 架构运行：

- **服务端**是 DBMS 执行引擎，以**后台进程**方式运行，在某个 TCP 端口上监听。
- **客户端**（Qt 编写的"类 DataGrip"工具）通过 TCP 与服务端通讯：建连 → 认证 → 发 SQL → 收结果 → 展示。

本协议的唯一目标是定义**两端之间"帧长什么样、消息有哪些、每个消息的字段是什么"**。数据库文件（附加功能1 的预分配 / 随机读写）只由服务端独占管理，客户端永远不直接碰磁盘——它只通过本协议与引擎对话。

> 为什么需要独立协议文档：TCP 是**字节流**，没有"消息边界"。如果两端对"一帧从哪开始、到哪结束、字段叫什么"理解不一致，联调会陷入无尽的对拍。把协议冻结成一份文档，服务端和客户端就能**各自开发、各自写单测、最后才联调**。

---

## 2. 传输层

| 项 | 约定 |
|---|---|
| 传输协议 | TCP（面向连接、可靠、有序） |
| 默认端口 | `8020`（可配置；服务端启动参数 `--port`，客户端连接参数 `host:port`） |
| 字符编码 | 全部文本（含 JSON 负载）使用 **UTF-8，无 BOM** |
| 加密 | 无（课程项目，仅本地 / 局域网演示）。密码以明文出现在 JSON 中——**仅适用于受信任网络**；若需公网，后续可加 TLS（属于协议演进，不在 v1.0 范围） |
| 连接生命周期 | 建立 TCP 连接 → 发 `AUTH` 认证 → 进入会话期（可发任意 `QUERY`）→ 主动关闭或空闲超时断开 |
| 心跳 | 客户端每 **30 秒**发 `PING`；服务端回 `PONG`。服务端 **90 秒**内无任何活动（含 `PING`）则主动断开 |
| 并发 | 一个 TCP 连接对应一个会话（一个用户 / 一个当前库）。多用户各自建连；客户端也可建多条连接对应多 Console |

---

## 3. 帧格式（Frame）

### 3.1 字节布局

```
 0               1 2               5 6         7
+---------------+---+---------------+-----------+=========================+
|    magic      |       length    |   type    |        payload          |
|   2 bytes     |     4 bytes     |  1 byte   |       length bytes      |
+---------------+---+---------------+-----------+=========================+
```

| 字段 | 长度 | 类型 | 字节序 | 说明 |
|---|---|---|---|---|
| `magic` | 2 B | 固定值 `0x4B55` | — | 帧头魔数。`0x4B='K'`, `0x55='U'`，作为"这是 KUN-DB 帧"的标识，便于接收端在乱序/残包时快速校验 |
| `length` | 4 B | `uint32` | **大端（网络字节序）** | `payload` 的字节长度（**不含** magic/length/type 自身）。取值范围 `0 .. 16,777,215` |
| `type` | 1 B | 枚举（见 §4） | — | 消息类型 |
| `payload` | `length` B | UTF-8 JSON | — | 消息体，详见 §5 |

> **字节序说明（重要）**：帧头里多字节整数（`length`）一律用**大端（网络字节序）**。这与 C++ 版内部"小端存储页数据"是两回事——内部存储是磁盘格式，线上协议是网络格式，二者隔离，不要混用。Java 用 `ByteBuffer` 默认大端，Qt 端用 `QDataStream` 时需显式 `setByteOrder(QDataStream::BigEndian)`，或手工移位。

### 3.2 分片 / 粘包处理（TCP 流式必读）

TCP 不保留消息边界，一个 `QUERY` 帧可能被拆成两次 `read`，也可能两个帧一次到达。两端**必须**维护一个"收帧缓冲"，按以下状态机重组：

1. 持续 `recv` 追加到缓冲区；
2. 缓冲区 ≥ 7 字节时，读出 `magic` 校验（不符则判定为协议错误，关闭连接）；
3. 读出 `length`，若缓冲区长度 ≥ `7 + length`，切出完整一帧交给业务处理，从缓冲区移除；否则继续 `recv`；
4. 若 `length` 超过 `MAX_PAYLOAD`（见下），直接关闭连接（防止恶意 / 错误膨胀）。

### 3.3 长度约束

| 常量 | 值 | 说明 |
|---|---|---|
| `MAX_PAYLOAD` | `16 * 1024 * 1024`（16 MB） | 单帧 `payload` 上限。超过即 `PROTOCOL_ERROR` 并断连 |
| `MAX_SQL_LENGTH` | `1,000,000` 字符 | 单条 SQL 上限（约 1 MB），超长 SQL 先由客户端拦截 |

---

## 4. 消息类型表

| `type` | 名称 | 方向 | 含义 |
|---|---|---|---|
| `0x01` | `AUTH` | 客户端 → 服务端 | 认证，建立会话 |
| `0x02` | `QUERY` | 客户端 → 服务端 | 提交一条 SQL 语句执行 |
| `0x03` | `RESULT` | 服务端 → 客户端 | 执行成功的结果（查询返回列+行；写操作返回影响行数） |
| `0x04` | `ERROR` | 服务端 → 客户端 | 执行失败 / 协议 / 认证错误 |
| `0x05` | `PING` | 客户端 → 服务端 | 心跳探测，负载为空 `{}` |
| `0x06` | `PONG` | 服务端 → 客户端 | 心跳应答，负载为 `{"time": <epoch_ms>}` |

> 认证失败时服务端回 `ERROR`（非 `RESULT`），见 §5.1。

---

## 5. 消息负载（JSON 字段定义）

所有 `payload` 为 UTF-8 JSON 对象。字段名用小写驼峰 / 小写下划线保持一致即可，**本规范以如下字段名为准**。

### 5.1 AUTH（type=0x01）

**请求（客户端发）**

```json
{ "user": "root", "password": "123456" }
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `user` | string | 是 | 用户名 |
| `password` | string | 是 | 明文密码（受信任网络内） |

**成功（服务端回 `RESULT`，type=0x03）**

```json
{
  "kind": "auth_ok",
  "sessionId": "a1b2c3d4",
  "serverVersion": "KUN-DB 1.0",
  "currentDatabase": null
}
```

**失败（服务端回 `ERROR`，type=0x04）**

```json
{ "code": "ACCESS_DENIED", "message": "用户名或密码错误", "sqlState": "28000", "queryId": null }
```

> 设计取舍：认证结果复用 `RESULT` / `ERROR` 两种既有消息，不新増类型，保持类型表稳定。`kind` 字段让客户端区分"这是一次认证成功回包"还是"一次查询结果"。

### 5.2 QUERY（type=0x02）

**请求（客户端发）**

```json
{ "queryId": 1, "sql": "SELECT id, name FROM student WHERE grade >= 90" }
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `queryId` | int | 是 | 客户端自增的请求序号（从 1 开始）。服务端在对应的 `RESULT` / `ERROR` 中原样回显，用于**异步匹配**——客户端发请求和收响应是异步的，靠 `queryId` 配对 |
| `sql` | string | 是 | 一条 SQL 语句。**一条 `QUERY` 帧只含一条语句**（结尾 `;` 可省略或保留）；多语句请拆成多个 `QUERY` 帧依次发送 |

### 5.3 RESULT（type=0x03）

**查询类（SELECT / 多表连接 / 视图）**

```json
{
  "queryId": 1,
  "kind": "result_set",
  "columns": [
    { "name": "id",     "type": "INT",      "nullable": false },
    { "name": "name",   "type": "VARCHAR",  "nullable": false },
    { "name": "birth",  "type": "DATE",     "nullable": true  }
  ],
  "rows": [
    [ 1, "张三", "2005-03-12" ],
    [ 2, "李四", null ]
  ],
  "rowCount": 2,
  "elapsedMs": 12,
  "hasMore": false
}
```

**写操作类（INSERT / UPDATE / DELETE / DDL）**

```json
{
  "queryId": 2,
  "kind": "affected",
  "columns": [],
  "rows": [],
  "affectedRows": 3,
  "rowCount": 0,
  "elapsedMs": 5,
  "hasMore": false
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `queryId` | int | 与请求对应 |
| `kind` | string | `"result_set"`（有行列）或 `"affected"`（仅影响行数） |
| `columns` | array | 列元数据；写操作为空数组 |
| `rows` | array | 行数据，每行是单元格数组；单元格类型见 §6 |
| `rowCount` | int | 返回行数（写操作为 0） |
| `affectedRows` | int | 受影响行数（写操作有意义，查询为 0） |
| `elapsedMs` | int | 服务端执行耗时（毫秒），用于客户端状态栏展示 |
| `hasMore` | bool | 是否还有后续批次（v1.0 始终 `false`；为未来分页预留） |

### 5.4 ERROR（type=0x04）

```json
{ "queryId": 1, "code": "SYNTAX_ERROR", "message": "expect 'FROM' near 'FORM'", "sqlState": "42000" }
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `queryId` | int / null | 对应请求序号；认证失败等无请求上下文时为 `null` |
| `code` | string | 机器可读错误码（见 §7） |
| `message` | string | 人类可读信息，原样展示给客户端用户 |
| `sqlState` | string | 可选，仿 SQL 标准 5 位状态码，便于高级展示 |

### 5.5 PING / PONG（type=0x05 / 0x06）

- `PING` 负载：`{}`
- `PONG` 负载：`{ "time": 1726270000000 }`（`time` 为服务端收到时的 epoch 毫秒）

---

## 6. 列类型与 JSON 值映射

服务端在 `columns[].type` 中给出**规范类型名**，客户端据此把 `rows` 中的 JSON 原生值映射到自身控件。

| 规范类型名 | 存储含义 | `rows` 中 JSON 表示 | 示例 | Qt 客户端建议类型 |
|---|---|---|---|---|
| `INT` | 4 字节整数 | JSON number（整数） | `42` | `int` / `QVariant::Int` |
| `BIGINT` | 8 字节整数 | JSON number（整数） | `9000000000` | `qlonglong` |
| `DOUBLE` | 8 字节浮点 | JSON number（浮点） | `3.14` | `double` |
| `DECIMAL` | 定长精度小数 | **JSON string**（保精度，避免浮点误差） | `"123.45"` | `QString` → 显示用 |
| `VARCHAR` | 变长字符串 | JSON string | `"张三"` | `QString` |
| `CHAR` | 定长字符串 | JSON string | `"A"` | `QString` |
| `BOOLEAN` | 布尔 | JSON `true` / `false` | `true` | `bool` |
| `DATE` | 日期 | JSON string `YYYY-MM-DD` | `"2005-03-12"` | `QDate` |
| `DATETIME` | 日期时间 | JSON string `YYYY-MM-DD HH:MM:SS` | `"2005-03-12 10:30:00"` | `QDateTime` |
| `TEXT` | 长文本 | JSON string | `"..."` | `QString` |
| （空值） | 任意类型 NULL | JSON `null` | `null` | `QVariant()` 空 |

> **关键约定**：`rows` 中的值是**类型化 JSON 原生值**（数字就是数字、字符串就是字符串、空就是 `null`），不是"全部转成字符串"。这样 Qt 端按 `columns[i].type` 直接映射，无需二次解析。唯独 `DECIMAL` 用字符串保持精度——这是和浮点列最易踩坑的点，务必注意。

---

## 7. 错误码表（ERROR.code）

| code | 含义 | 触发场景 | 客户端建议表现 |
|---|---|---|---|
| `ACCESS_DENIED` | 认证失败 / 无权限 | 密码错、用户缺表权限 | 弹登录失败 / 结果区红字 |
| `AUTH_REQUIRED` | 未认证就发 QUERY | 先发 SQL 后认证 | 提示"请先连接" |
| `SYNTAX_ERROR` | SQL 语法错误 | 解析失败 | 结果区红字 + 定位 |
| `UNKNOWN_DATABASE` | 库不存在 | `USE` / 跨库引用 | 红字 |
| `UNKNOWN_TABLE` | 表不存在 | 引用未建表 | 红字 |
| `UNKNOWN_COLUMN` | 列不存在 | 引用未定义列 | 红字 |
| `DUPLICATE_KEY` | 主键 / 唯一索引冲突 | 重复插入 | 红字 |
| `TYPE_MISMATCH` | 类型不兼容 | 赋值类型错 | 红字 |
| `TXN_CONFLICT` | 事务冲突 / 死锁 | 并发写冲突 | 红字，提示重试 |
| `PROTOCOL_ERROR` | 协议层错误 | 魔数错 / 超长帧 / 类型未知 | 断连并提示"协议错误" |
| `SERVER_ERROR` | 服务端内部异常 | 未预期崩溃类 | 红字 + 日志 |

---

## 8. 会话与权限

- 一次成功 `AUTH` 后，该 TCP 连接绑定一个**会话**（当前用户 + 当前库 + 事务状态）。
- `QUERY` 帧**不携带**用户名 / 密码 / sessionId——服务端按"连接 → 会话"映射识别身份。客户端在 `AUTH` 成功后拿到的 `sessionId` 仅作展示 / 调试用。
- 权限在**服务端 Binder 阶段**统一校验（见 Java 设计文档第 9 节）；客户端无权限判断逻辑，越权由服务端回 `ACCESS_DENIED`。
- 切换当前库用 SQL `USE db;`（作为普通 `QUERY` 发送），`AUTH` 成功时 `currentDatabase` 为 `null`，需先 `USE`。

---

## 9. 交互时序示例

### 9.1 连接 + 认证

```
Client                                  Server
  |---- TCP connect (host:8020) -------->|
  |---- AUTH {user,password} ----------->|
  |<--- RESULT {kind:auth_ok,...} -------|   (失败则 <--- ERROR ACCESS_DENIED)
  |---- QUERY {USE school_db} ---------->|
  |<--- RESULT {kind:affected} ----------|
```

### 9.2 查询 + 结果

```
Client                                  Server
  |---- QUERY {queryId:1, sql:"SELECT..."} ->|
  |<--- RESULT {queryId:1, kind:result_set,  |
  |           columns:[...], rows:[...]} -----|
```

### 9.3 错误

```
Client                                  Server
  |---- QUERY {queryId:2, sql:"SELCT..."} ->|
  |<--- ERROR {queryId:2, code:SYNTAX_ERROR, |
  |           message:"expect 'FROM'..."} ---|
```

### 9.4 心跳

```
Client                                  Server
  |---- PING {} ------------------------->|
  |<--- PONG {"time":...} ----------------|
```

---

## 10. 字节级示例（AUTH 帧）

请求 `{"user":"root","password":"123456"}`（35 字节 UTF-8）：

```
偏移   十六进制
0x00   4B 55            <- magic 0x4B55
0x02   00 00 00 23      <- length = 35 (0x23)，大端
0x06   01               <- type = 0x01 (AUTH)
0x07   7B 22 75 73 65   <- payload 起始：{ "us e ...
       ...（共 35 字节 JSON）...
```

接收端重组：读到 ≥7 字节 → 校验 `4B 55` → 取 `length=35` → 等缓冲区凑满 `7+35=42` 字节 → 切出一帧 → 解析 `payload` 为 JSON。

---

## 11. 两端实现要点

### 11.1 Java 服务端（Netty）

- 入站 `ByteToMessageDecoder`：按 §3.2 状态机把字节流切成 `Frame(magic, length, type, payloadBytes)`；魔数不符抛 `PROTOCOL_ERROR`。
- `payload` 用 **Jackson** `ObjectMapper` 解析为 `Map` / POJO；响应同样用 Jackson 序列化为 UTF-8 字节。
- 出站：写 `magic(2) + length(4,大端) + type(1) + jsonBytes`。可用 `ByteBuf` 手工写，确保大端。
- 每连接一个 `SessionContext`（当前库 / 用户 / 事务）；`AUTH` 建会话，`QUERY` 交给执行引擎，结果封装成 `RESULT` / `ERROR` 回写。
- 心跳：`IdleStateHandler` 读空闲 90s 断连；收到 `PING` 回 `PONG`。

### 11.2 Qt 客户端（C++ / QTcpSocket）

- 异步：`QTcpSocket` 信号 `readyRead()` → 把 `readAll()` 追加到 `m_recvBuffer` → 调用 `tryDecodeFrame()` 循环切帧（§3.2）。**禁止阻塞 `waitForReadyRead`**，保持 UI 流畅。
- 封装类 `KunDbConnection`：对外暴露 `connect(host,port)`、`authenticate(user,password)`、`execute(sql, callback)`、`ping()`；内部维护 `queryId` 自增与 `pendingQueries` 映射，收到 `RESULT/ERROR` 按 `queryId` 回调。
- 帧写出：用 `QByteArray` 拼 `magic(2) + quint32大端 + quint8 type + jsonUtf8`；JSON 用 **`QJsonDocument`**（`QJsonObject` 构造），`toJson(QJsonDocument::Compact)` 得 UTF-8 字节。
- 结果展示：把 `columns` / `rows` 喂给自定义 `QAbstractTableModel` 绑定 `QTableView`；单元格按 §6 映射 `QVariant`。
- 大端注意：`QDataStream` 默认大端，但手动 `append` 字节时用 `>>` 移位写 `quint32`，或 `qToBigEndian()`。

### 11.3 共同禁忌

- 不要在 `payload` 里放二进制（图片等）——协议只传 SQL 与结构化结果。
- 不要在 `QUERY` 帧里塞多条语句；多语句拆多帧。
- 不要假设一次 `read` 就是一帧（见 §3.2）。

---

## 12. 对象浏览器（左侧库→表→列树）如何复用本协议

客户端**不需要**任何专用元数据命令，直接把下列 SQL 当作普通 `QUERY` 发送、解析 `RESULT` 即可：

| 目的 | 发送的 SQL | 解析方式 |
|---|---|---|
| 列出所有库 | `SHOW DATABASES` | `rows` 第一列即库名 |
| 列出当前库所有表 / 视图 | `SHOW TABLES` | 列含类型标识，区分表 / 视图 |
| 查看表结构 | `DESC <table>` | `rows` 为列名 / 类型 / 是否可空 |
| 刷新树 | 重发上述 SQL | 定时或手动刷新 |

> 这样服务端零额外协议负担，客户端也能借此验证"服务端 SQL 子集是否覆盖元数据查询"。

---

## 13. 版本与演进约定

- 协议版本体现在 `AUTH` 成功回包的 `serverVersion` 中；本协议为 **v1.0**。
- 新增消息类型 → `type` 加枚举，**不回收**旧类型。
- 新增 JSON 字段 → **追加且可选**（旧客户端忽略未知字段）。
- 破坏性变更 → 升主版本（v2.0），两端同步升级并评审本文件。
- 任何改动先改本文件、再改代码、最后联调，禁止"代码先跑通再补文档"。

---

## 附：协议速查卡（贴显示器用）

```
帧:  [ magic 0x4B55 : 2B ][ length : 4B 大端 ][ type : 1B ][ payload : UTF-8 JSON ]

type: 0x01 AUTH   0x02 QUERY   0x03 RESULT   0x04 ERROR   0x05 PING   0x06 PONG
AUTH   C→S: {user,password}            S→C: RESULT{auth_ok,sessionId} / ERROR{ACCESS_DENIED}
QUERY  C→S: {queryId, sql}             S→C: RESULT{queryId,columns,rows,...} / ERROR{queryId,code,msg}
PING   C→S: {}                         S→C: PONG{time}
规则: 一条 QUERY = 一条 SQL；queryId 异步配对；值按列类型用 JSON 原生值；DECIMAL 用字符串保精度。
```
