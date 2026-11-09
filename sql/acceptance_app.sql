-- ==========================================================
--  访问控制验收（用受限账号 app 登录，验证 GRANT / REVOKE 生效）
--  用法：run-test-client.bat 127.0.0.1 8020 app app123  <  dev-tools\acceptance_app.sql
--  前置：先以 root 跑过 acceptance.sql（里面建了 app 并授过权）
-- ==========================================================
USE accdemo
SELECT * FROM student
SELECT * FROM score
INSERT INTO student VALUES (9, 'hacker', 100)
DELETE FROM student WHERE id = 1
UPDATE student SET grade = 0 WHERE id = 1
CREATE TABLE t_evil (a INT)
DROP TABLE student
