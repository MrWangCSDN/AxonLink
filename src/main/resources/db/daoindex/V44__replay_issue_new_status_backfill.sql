-- 执行前核对：没有最终处理方案的“打开”问题数量。
-- SELECT COUNT(*) AS pending_new_status_backfill
--   FROM dii_replay_issue
--  WHERE issue_status = '打开'
--    AND NULLIF(TRIM(final_solution), '') IS NULL;
--
-- 历史数据治理：没有最终处理方案的“打开”问题统一回到“新建”。
UPDATE dii_replay_issue
   SET issue_status = '新建'
 WHERE issue_status = '打开'
   AND NULLIF(TRIM(final_solution), '') IS NULL;
