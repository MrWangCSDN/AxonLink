-- 在 diiResult 数据源对应的数据库执行。
-- 应用升级并完成 V89 迁移后执行本脚本。
-- batch_no 按真实主键填写，DB20261028-1 与 DB20261028-01 是不同批次。
SET @target_batch_no = 'DB20261028-1';
SET @target_version_no = '20261027-141302';

-- 先确认实际批次键以及目标版本。
SELECT batch_no, batch_date, registration_version_id
FROM dii_replay_database_issue_batch
WHERE batch_no IN (@target_batch_no, 'DB20261028-01');

SELECT id, version_no, generated_at, generated_name, table_count, field_count
FROM dii_replay_db_compare_version
WHERE version_no = @target_version_no;

START TRANSACTION;

-- 锁定批次，显示原绑定，避免与同批次导入并发修改。
SELECT b.batch_no, b.registration_version_id AS previous_version_id,
       old_v.version_no AS previous_version_no,
       target_v.id AS target_version_id, target_v.version_no AS target_version_no
FROM dii_replay_database_issue_batch b
LEFT JOIN dii_replay_db_compare_version old_v ON old_v.id = b.registration_version_id
LEFT JOIN dii_replay_db_compare_version target_v ON target_v.version_no = @target_version_no
WHERE b.batch_no = @target_batch_no
FOR UPDATE;

-- 仅首次绑定或保持相同绑定，已有关联其他版本时保持原值。
-- 目标批次或目标版本不存在时，更新行数为 0。
UPDATE dii_replay_database_issue_batch b
JOIN dii_replay_db_compare_version v ON v.version_no = @target_version_no
SET b.registration_version_id = v.id
WHERE b.batch_no = @target_batch_no
  AND (b.registration_version_id IS NULL OR b.registration_version_id = v.id);

SELECT ROW_COUNT() AS affected_rows;

-- 幂等重跑时 affected_rows 可能为 0，以本条的 maintenance_result 确认结果。
SELECT b.batch_no, b.registration_version_id, v.version_no,
       v.generated_at, v.generated_name,
       CASE WHEN v.version_no = @target_version_no THEN '绑定成功'
            WHEN b.registration_version_id IS NULL THEN '尚未绑定，请检查目标版本'
            ELSE '已有其他版本绑定，请核对原版本' END AS maintenance_result
FROM dii_replay_database_issue_batch b
LEFT JOIN dii_replay_db_compare_version v ON v.id = b.registration_version_id
WHERE b.batch_no = @target_batch_no;

COMMIT;

-- 重新导出该批次报表后，领域与字段总数使用此版本中实际参与表的快照。
-- 表名在版本中缺失或跨 schema 重名时，报表会给出明确错误，需要核对绑定版本。
