-- 在后端实际连接的 MySQL 结果库中新建 SQL 会话执行。
-- 结果库由 dao-index-analysis.result-datasource.url / DII_RESULT_URL 配置。
-- 仅补回写入锁初始化记录，不删除或修改问题、批次和历史数据。
-- 本脚本可重复执行；锁表本身须已通过 V77 创建。

SELECT DATABASE() AS current_database;

INSERT INTO dii_replay_database_issue_write_lock (id)
VALUES (1)
ON DUPLICATE KEY UPDATE id = 1;

-- 显式提交，确保应用连接能够看到新增锁行。
COMMIT;

SELECT id
FROM dii_replay_database_issue_write_lock
WHERE id = 1;
