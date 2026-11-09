-- 崩溃恢复演示 · 第二段（在"正常重启"的实例上执行）
-- 重启命令（不带 -Dkundb.crash）：
--   java -Dkundb.datadir=kundb-data-crash -cp ... com.kundb.server.KunDbServer --port 8032
-- 期望：id=99 那一行虽然从未落过盘，但 COMMIT 日志在，启动时应由 WAL redo 补回来。

USE crashtest
SELECT * FROM ct
SHOW WAL STATUS
SHOW LOGS 12
