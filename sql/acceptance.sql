-- ==========================================================
--  KUN-DB 功能验收脚本（用 test_client / run-test-client.bat 执行）
--
--  用法（服务端已启动的前提下，在项目根目录执行）：
--     run-test-client.bat  <  dev-tools\acceptance.sql
--     （即 run-test-client.bat  小于号  dev-tools\acceptance.sql）
--
--  说明：
--    * 以 -- 或 # 开头的行是注释，客户端会跳过，不会发给服务端。
--    * 第 1 行的 DROP DATABASE 是为了让脚本可重复执行；
--      首次运行时它会报一次"库不存在"，属正常现象，忽略即可。
--    * 重复执行时 CREATE USER 'app' 会报"用户已存在"，也属正常，可忽略。
-- ==========================================================

-- ---------- 0. 连通性 / 协议握手 ----------
ping
SELECT 1
SELECT @@version
SHOW DATABASES

-- ---------- 1. 建库 / 选库 ----------
DROP DATABASE accdemo
CREATE DATABASE accdemo
SHOW DATABASES
USE accdemo

-- ---------- 2. 建表 ----------
CREATE TABLE student (id INT PRIMARY KEY, name VARCHAR NOT NULL, grade INT)
CREATE TABLE score (sid INT, cid INT, point INT)
CREATE TABLE course (cid INT, cname VARCHAR)
SHOW TABLES
DESC student
SHOW CREATE TABLE student

-- ---------- 3. 插入 INSERT ----------
INSERT INTO student VALUES (1, 'zhangsan', 90)
INSERT INTO student VALUES (2, 'lisi', 85), (3, 'wangwu', 92)
INSERT INTO student (id, name, grade) VALUES (4, 'zhaoliu', 78)
INSERT INTO score VALUES (1, 101, 95), (1, 102, 88), (2, 101, 76), (3, 102, 99)
INSERT INTO course VALUES (101, 'math'), (102, 'physics')

-- ---------- 4. 单表查询 SELECT ----------
SELECT * FROM student
SELECT name, grade FROM student WHERE grade > 85
SELECT * FROM student ORDER BY grade DESC
SELECT * FROM student LIMIT 2
SELECT COUNT(*) FROM student
SELECT s.name AS stu_name, s.grade FROM student s WHERE s.grade >= 90

-- ---------- 5. 更新 UPDATE（常量赋值 + 算术赋值）----------
UPDATE student SET grade = grade + 5 WHERE id = 4
SELECT * FROM student WHERE id = 4
UPDATE student SET name = 'zhaoliu_new' WHERE id = 4
SELECT * FROM student WHERE id = 4

-- ---------- 6. 删除 DELETE ----------
DELETE FROM student WHERE id = 4
SELECT * FROM student

-- ---------- 7. 连接查询（两表 / 三表）----------
SELECT s.name, sc.cid, sc.point FROM student s, score sc WHERE s.id = sc.sid
SELECT s.name, sc.point FROM student s, score sc WHERE s.id = sc.sid AND sc.point > 80 AND s.grade > 85
SELECT s.name, c.cname, sc.point FROM student s, score sc, course c WHERE s.id = sc.sid AND sc.cid = c.cid

-- ---------- 8. 视图 VIEW ----------
CREATE VIEW v_good AS SELECT s.name, sc.point FROM student s, score sc WHERE s.id = sc.sid AND sc.point > 80
SELECT * FROM v_good
SHOW FULL TABLES
DROP VIEW v_good
SHOW FULL TABLES

-- ---------- 9. 索引 INDEX ----------
CREATE INDEX idx_grade ON student (grade)
SELECT * FROM student WHERE grade = 90
SHOW INDEX FROM student
DROP INDEX idx_grade ON student
SHOW INDEX FROM student

-- ---------- 10. 事务 BEGIN / ROLLBACK / COMMIT ----------
BEGIN
INSERT INTO student VALUES (5, 'temp_row', 60)
SELECT * FROM student
ROLLBACK
SELECT * FROM student
BEGIN
INSERT INTO student VALUES (6, 'kept_row', 70)
COMMIT
SELECT * FROM student

-- ---------- 11. 访问控制：建用户 / 授权 / 回收 ----------
CREATE USER 'app' IDENTIFIED BY 'app123'
GRANT SELECT ON accdemo.* TO 'app'
GRANT INSERT ON accdemo.student TO 'app'
REVOKE INSERT ON accdemo.student FROM 'app'

-- ---------- 12. 日志系统（WAL）：这里的命令都是只读/幂等的，可重复执行 ----------
SHOW WAL STATUS
SHOW LOGS 10
CHECKPOINT
SHOW WAL STATUS

-- ---------- 13. 表空间管理（只读查看；建/删表空间的完整流程见 acceptance_wal_ts.sql）----------
SHOW TABLESPACES
SHOW TABLESPACE kundspace

-- ---------- 14. 附加功能1 文件管理（只读查看；增删改的完整流程见 acceptance_filemgmt.sql）----------
-- 三段式记录文件的格式信息区（段1）与记录空间分配表（段2）
SHOW TABLE FILE student
SHOW FILE MAP student 8

-- ---------- 15. 收尾：确认数据仍在 ----------
SELECT * FROM student
SELECT * FROM score
SELECT * FROM course
SHOW DATABASES
