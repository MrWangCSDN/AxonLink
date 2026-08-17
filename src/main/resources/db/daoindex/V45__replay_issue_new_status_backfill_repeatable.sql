-- 回放问题状态治理：导入前已存在的“打开”问题，且用户尚未填写最终处理方案时，归为“新建”。
-- 执行前核对本次将治理的数量：
-- SELECT COUNT(*) AS pending_new_status_backfill
--   FROM dii_replay_issue
--  WHERE issue_status = '打开'
--    AND NULLIF(TRIM(final_solution), '') IS NULL;
--
-- 按分组核对：
-- SELECT group_name, COUNT(*) AS pending_new_status_backfill
--   FROM dii_replay_issue
--  WHERE issue_status = '打开'
--    AND NULLIF(TRIM(final_solution), '') IS NULL
--  GROUP BY group_name
--  ORDER BY group_name;

UPDATE dii_replay_issue
   SET issue_status = '新建'
 WHERE issue_status = '打开'
   AND NULLIF(TRIM(final_solution), '') IS NULL;
