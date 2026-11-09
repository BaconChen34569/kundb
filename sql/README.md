# 演示与验收 SQL

这些脚本用于验证内核能力，按顺序在已启动的 KUN-DB 服务端（默认 8020）上执行即可。
脚本自 `dev-tools/` 中的验收用例整理而来，已统一为 UTF-8 / LF；不含任何真实业务数据。

| 文件 | 覆盖范围 |
|---|---|
| `acceptance.sql` | 基础 DDL / DML、查询与结果集验收 |
| `acceptance_app.sql` | 应用层场景用例 |
| `acceptance_filemgmt.sql` | 文件与数据管理相关用例 |
| `acceptance_wal_ts.sql` | WAL 与表空间相关用例 |
| `crash_recovery_demo.sql` | 崩溃恢复演示脚本 |
| `crash_recovery_verify.sql` | 崩溃恢复结果校验 |

> 执行前请备份 `kundb-data/`；部分用例会建库、建表或触发 CHECKPOINT。
> SQL 语法速查与已知限制见官网文档中心。
