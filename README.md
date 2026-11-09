# KUN-DB

**从零手写的小型数据库管理系统**（课程课题「小型 DBMS」）。
Java 17 实现内核，Qt 6 / C++ 实现桌面客户端；SQL 解析、执行、优化、事务与存储引擎全部自研。

> 对外表述统一为：**实现了 MySQL 线协议的一个子集**，让常见数据库客户端可以连接。
> 本项目不是 MySQL 兼容实现，与 Oracle 无关联；也未使用任何第三方 SQL 解析器或优化器。

## 能力概览

| 方向 | 已实现 |
|---|---|
| SQL | DDL / DML、`ALTER`、视图、子查询与 `UNION`、隐式连接、`GROUP BY` / `HAVING`、`LIMIT` 三种写法、`EXPLAIN` |
| 执行 | 基于代价的优化器、连接顺序动态规划、谓词下推、哈希索引 |
| 事务 | 事务与 undo、WAL + CHECKPOINT、MVCC 复用 undo、表级 / 行级 S·X 锁、读已提交 / 可重复读 / 串行化 |
| 存储 | 表空间、JSON 表持久化、崩溃恢复 |
| 安全 | 用户与访问控制、鉴权握手 |
| 接入 | MySQL 线协议子集（TCP 8020）、命令行客户端、自研 Qt GUI 客户端；可用 DataGrip / Navicat / MySQL Workbench / JDBC 连接 |

本仓库 `docs/` 收录架构与协议设计文档（含早期路线对比稿，具体实现以源码为准）；
完整能力清单与已知边界见官网文档中心。

## 两个仓库

采用**双仓库**结构，两个仓库各自独立、互不嵌套：

| 仓库 | 内容 |
|---|---|
| **本仓库** `BaconChen34569/kundb` | 内核源码、Qt 客户端源码、技术文档、合规与发布规则 |
| `BaconChen34569/kundb-web` | 官网（下载分发 + 24 篇文档中心 + 留言反馈），Spring Boot 3.2 / Vue 3 / Vite |

本仓库不含官网代码、不含任何安装包二进制、不含运行期数据。

## 目录结构

```text
kundb-common/       公共模型与协议结构
kundb-server/       SQL 解析 / 执行 / 优化 / 事务 / 存储 / 网络服务端
kundb-qt/           Qt 6 桌面客户端（src/ + CMakeLists.txt）
sql/                验收与演示用 SQL 脚本
docs/               架构、线协议、详细设计等自撰技术文档
releases/notes/     各发布线的 Release 说明草稿
test_client.py      Python 协议自测客户端
RELEASES.md         三条发布线的版本规则与发布流程
```

## 快速开始

需要 JDK 17+ 与 Maven 3.9+（在 PATH 中）。GUI 客户端另需 Qt 6（Widgets、Network）与 CMake 3.16+。

```bash
# 1. 构建（必须用 install，确保 kundb-common 的 jar 被刷新）
mvn -q install -DskipTests

# 2. 生成依赖 classpath 并启动服务端（默认端口 8020）
mvn -q -pl kundb-server dependency:build-classpath -Dmdep.outputFile=cp.txt
java -cp "kundb-server/target/classes:kundb-common/target/classes:$(cat kundb-server/cp.txt)" \
     com.kundb.server.KunDbServer --port 8020
```

Windows 把 classpath 分隔符 `:` 换成 `;`，`$(cat ...)` 换成 PowerShell 的
`$(Get-Content kundb-server/cp.txt -Raw)`。也可以直接用
`mvn -pl kundb-server exec:java -Dexec.mainClass=com.kundb.server.KunDbServer -Dexec.args="--port 8020"`
（前提是刚跑过 `mvn install`）。

```bash
# 3. 协议自测（脚本内置演示账号，仅限本地测试）
python test_client.py

# 4. 构建 GUI 客户端
cmake -S kundb-qt -B kundb-qt/build && cmake --build kundb-qt/build

# 说明：仓库暂未收录 JUnit 用例，功能验收请用 sql/ 下的脚本
```

服务端启动后可用 Qt 客户端、命令行客户端，或 DataGrip / Navicat / MySQL Workbench / JDBC 连接
`127.0.0.1:8020`。SQL 语法速查见官网文档中心。

> 数据目录 `kundb-data/` 是运行期产物，不进 Git。默认演示账号仅用于本地测试，请勿用于生产。

## 安装包与版本

二进制产物（MSI 安装版、便携版 ZIP、Qt 客户端 ZIP）**只通过 GitHub Releases 发布**，
不纳入 Git 历史，也不放在仓库目录里。三条发布线版本号互相独立、tag 前缀区分：

| 发布线 | 当前版本 | tag | 产物 |
|---|---|---|---|
| 主发行（内核 + MSI） | 1.1.1 | `v1.1.1` | `KUN-DB-1.1.1.msi` |
| 便携版 | 1.1.1 | `portable-v1.1.1` | `KUN-DB-1.1.1-portable.zip` |
| Qt 桌面客户端 | **1.0.0（独立版本线）** | `qt-v1.0.0` | `KUN-DB-QtClient-1.0.0.zip` |

Qt 客户端的版本号**不跟随**内核版本，只有客户端自身改动才升版。
完整规则、升版清单与发布步骤见 [RELEASES.md](RELEASES.md)，
Release 说明草稿见 `releases/notes/`。

## 已知限制

- 可重复读隔离级别未实现间隙锁，存在幻读可能。
- 仅支持 MySQL 线协议子集，部分客户端内省语句、预处理语句与类型可能不完整。
- 单机、教学用途，未做生产级性能与可靠性验证；请勿存放重要数据。

## 构建脚本说明

MSI / 便携版 / Qt ZIP 的打包依赖本机 jpackage、WiX Toolset 与 Qt 工具链，
相关工程脚本含本机环境路径，未随本仓库开源；需要复现构建请参考 `RELEASES.md` 的产物清单
与官网文档，或直接使用 Releases 中的二进制。

## 许可

[MIT](LICENSE.md)。第三方组件及其许可见 [NOTICE.md](NOTICE.md)，
贡献方式见 [CONTRIBUTING.md](CONTRIBUTING.md)。
