-- 回放问题清单：导入批次数量核对脚本（MySQL）
-- 用法：先设置本次导入生成的覆盖批次号，例如 20260812-203015-123。
SET @coverage_round = '请替换为覆盖批次号';

-- 1. 本次导入总量及处理结果（以导入记录为准）
SELECT id AS import_round_id,
       round_code AS coverage_round,
       imported_at,
       input_rows AS imported_total,
       created_rows AS created_total,
       updated_rows AS updated_total,
       ignored_rows AS ignored_total,
       auto_repaired_rows AS auto_repaired_total
  FROM dii_replay_import_round
 WHERE round_code = @coverage_round
 ORDER BY imported_at DESC, id DESC
 LIMIT 1;

-- 2. 本次批次实际出现的去重问题数（同一个 issue_key 只计一次）
SELECT COUNT(DISTINCT issue_key) AS appeared_issue_total
  FROM dii_replay_issue_round
 WHERE round_id = (
       SELECT id FROM dii_replay_import_round
        WHERE round_code = @coverage_round
        ORDER BY imported_at DESC, id DESC LIMIT 1
 );

-- 3. 本次批次各状态数量（按本次导入后的最终状态统计）
SELECT COALESCE(i.issue_status, '未知') AS issue_status,
       COUNT(DISTINCT i.issue_key) AS issue_total
  FROM dii_replay_issue_round r
  JOIN dii_replay_issue i ON i.id = r.replay_issue_id
 WHERE r.round_id = (
       SELECT id FROM dii_replay_import_round
        WHERE round_code = @coverage_round
        ORDER BY imported_at DESC, id DESC LIMIT 1
 )
 GROUP BY i.issue_status
 ORDER BY FIELD(i.issue_status, '新建', '打开', '重新打开', '延后修复', '修复待验证', '已修复');

-- 4. 如果只知道 Excel 的批次字段，可按 batch_no 查询当前清单数量和状态
SET @excel_batch = '请替换为 Excel 批次';
SELECT COUNT(*) AS current_batch_total,
       SUM(issue_status = '新建') AS new_total,
       SUM(issue_status = '打开') AS open_total,
       SUM(issue_status = '重新打开') AS reopened_total,
       SUM(issue_status = '延后修复') AS deferred_total,
       SUM(issue_status = '修复待验证') AS pending_verification_total,
       SUM(issue_status = '已修复') AS fixed_total
  FROM dii_replay_issue
 WHERE TRIM(batch_no) = TRIM(@excel_batch);

-- 5. 查询最近导入批次号，便于复制到 @coverage_round
SELECT id, round_code AS coverage_round, imported_at, input_rows,
       created_rows, updated_rows, ignored_rows, auto_repaired_rows
  FROM dii_replay_import_round
 ORDER BY imported_at DESC, id DESC
 LIMIT 20;
