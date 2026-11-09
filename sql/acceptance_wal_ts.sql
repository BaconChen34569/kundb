-- KUN-DB 日志系统（WAL）与表空间管理 —— 验收脚本
-- 用法（服务端需先启动，端口按实际替换；本脚本默认在隔离端口 8031 上跑）：
--   run-test-client.bat 127.0.0.1 8031 root 123456  <  dev-tools\acceptance_wal_ts.sql
-- 说明：本脚本使用独立的 -Dkundb.datadir=kundb-data-ts 数据目录，不污染 kundb-data。

-- ========== 0. 连通性 ==========
SELECT 1

-- ========== 1. 表空间管理 ==========
-- 1.1 查看默认表空间（系统自带 kundspace）
SHOW TABLESPACES

-- 1.2 新建一个表空间：初始 2 个区（512KB），开启自动扩展
CREATE TABLESPACE ts_demo SIZE 524288 AUTOEXTEND ON

-- 1.3 再建一个不自动扩展的表空间（用于演示"空间不足"的保护）
CREATE TABLESPACE ts_fixed SIZE 262144 AUTOEXTEND OFF

-- 1.4 再看一次（应能看到 3 个表空间）
SHOW TABLESPACES

-- ========== 2. 建库建表，并指定表空间 ==========
CREATE DATABASE tsacc
USE tsacc

-- 2.1 建在 ts_demo 上的表
CREATE TABLE t_demo (id INT PRIMARY KEY, name VARCHAR, score INT) TABLESPACE ts_demo

-- 2.2 建在默认表空间上的表（不写 TABLESPACE 子句）
CREATE TABLE t_default (id INT PRIMARY KEY, tag VARCHAR)

-- 2.3 写入数据
INSERT INTO t_demo VALUES (1, 'alice', 90)
INSERT INTO t_demo VALUES (2, 'bob', 85), (3, 'cindy', 88)
INSERT INTO t_default VALUES (1, 'x')

-- 2.4 看两个表空间各自的数据文件（预分配 = 整数个区 256KB）
SHOW TABLESPACE ts_demo
SHOW TABLESPACE kundspace
SHOW TABLESPACES

-- ========== 3. 日志系统（WAL）==========
-- 3.1 日志运行状况：当前 LSN / 检查点 LSN / 待重做记录数 / 归档数
SHOW WAL STATUS

-- 3.2 看最近 20 条日志（含 LSN、时间、用户、操作、对象）
SHOW LOGS 20

-- 3.3 事务日志：BEGIN / INSERT / COMMIT 三条一组
BEGIN
INSERT INTO t_demo VALUES (4, 'david', 77)
UPDATE t_demo SET score = score + 5 WHERE id = 1
COMMIT
SHOW LOGS 8

-- 3.4 未提交事务：ROLLBACK 后数据不变，且日志里留下 ROLLBACK 记录
BEGIN
DELETE FROM t_demo WHERE id = 2
ROLLBACK
SELECT * FROM t_demo
SHOW LOGS 6

-- ========== 4. CHECKPOINT：落盘 + 打检查点 + 归档旧日志 ==========
CHECKPOINT
SHOW WAL STATUS
-- 再写一条日志，验证检查点之后 LSN 继续增长
INSERT INTO t_demo VALUES (5, 'eric', 66)
SHOW WAL STATUS
SHOW LOGS 5

-- ========== 5. 手动扩展表空间 ==========
ALTER TABLESPACE ts_demo EXTEND 2
SHOW TABLESPACES

-- ========== 6. 自动扩展：表变大后自动加一个区 ==========
-- （t_demo 每插入一行都会重算真实字节数，超过已分配空间时自动扩展）
INSERT INTO t_demo VALUES (6, 'frank', 55)
SHOW TABLESPACE ts_demo

-- ========== 7. 删除与回收 ==========
-- 7.1 有表占用的表空间不允许删除
DROP TABLESPACE ts_demo
DROP TABLE t_demo
SHOW TABLESPACES
-- 7.2 表删掉后，表空间可以删除
DROP TABLESPACE ts_demo
DROP TABLESPACE ts_fixed
SHOW TABLESPACES

-- ========== 8. 收尾 ==========
SHOW TABLES
SELECT * FROM t_default
SHOW LOGS 10
