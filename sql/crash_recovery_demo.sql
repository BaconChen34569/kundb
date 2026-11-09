-- 崩溃恢复演示 · 第一段（在"会崩溃"的实例上执行）
-- 服务端启动方式（故意制造"已提交但未落盘"的崩溃现场）：
--   java -Dkundb.datadir=kundb-data-crash -Dkundb.crash=commit -cp ... com.kundb.server.KunDbServer --port 8032
-- -Dkundb.crash=commit 的含义：写完 COMMIT 日志、还没来得及把脏表落盘，进程立刻退出。

CREATE DATABASE crashtest
USE crashtest
CREATE TABLE ct (id INT PRIMARY KEY, v VARCHAR)

-- autocommit 插入：写 WAL 后立刻落盘（正常路径）
INSERT INTO ct VALUES (1, 'before-crash')

-- 事务：COMMIT 时先写日志、再落盘，而崩溃就发生在这两步之间
BEGIN
INSERT INTO ct VALUES (99, 'lost-but-redone')
COMMIT
