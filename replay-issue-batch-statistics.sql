-- 回放问题指定批次分组统计（MySQL 8+）
-- 请将下面的值替换成需要统计的出现批次号。
SET @batch_name = '请替换为批次号';

-- 1. 已修复、无需处理：按照领域、是否沙箱和问题类型统计。
WITH group_dim AS (
    SELECT 1 sort_no, '公共组' group_name, 0 is_sandbox, '公共组' display_name
    UNION ALL SELECT 2, '存款组', 0, '存款组'
    UNION ALL SELECT 3, '公共组', 1, '沙箱-公共组'
    UNION ALL SELECT 4, '存款组', 1, '沙箱-存款组'
    UNION ALL SELECT 5, '结算组', 1, '沙箱-结算组'
    UNION ALL SELECT 6, '贷款组', 1, '沙箱-贷款组'
    UNION ALL SELECT 7, '结算组', 0, '结算组'
    UNION ALL SELECT 8, '贷款组', 0, '贷款组'
),
batch_data AS (
    SELECT DISTINCT
           i.id,
           i.group_name,
           i.is_sandbox,
           i.issue_type
      FROM dii_replay_issue_occurrence_batch ob
      JOIN dii_replay_issue i
        ON i.id = ob.replay_issue_id
     WHERE ob.batch_name = @batch_name
       AND i.issue_status IN ('已修复', '无需处理')
)
SELECT
    g.display_name AS `领域`,
    SUM(CASE WHEN d.issue_type = '代码问题'       THEN 1 ELSE 0 END) AS `代码问题`,
    SUM(CASE WHEN d.issue_type = '参数问题'       THEN 1 ELSE 0 END) AS `参数问题`,
    SUM(CASE WHEN d.issue_type = '合理差异'       THEN 1 ELSE 0 END) AS `合理差异`,
    SUM(CASE WHEN d.issue_type = '外围问题'       THEN 1 ELSE 0 END) AS `外围问题`,
    SUM(CASE WHEN d.issue_type = '平台问题'       THEN 1 ELSE 0 END) AS `平台问题`,
    SUM(CASE WHEN d.issue_type = '新核心下线'     THEN 1 ELSE 0 END) AS `新核心下线`,
    SUM(CASE WHEN d.issue_type = '规则性差异问题' THEN 1 ELSE 0 END) AS `规则性差异问题`,
    SUM(CASE WHEN d.issue_type = '迁移问题'       THEN 1 ELSE 0 END) AS `迁移问题`,
    SUM(CASE WHEN d.issue_type = '防腐问题'       THEN 1 ELSE 0 END) AS `防腐问题`,
    SUM(CASE
            WHEN d.id IS NOT NULL
             AND (d.issue_type = '其他问题'
                  OR d.issue_type IS NULL
                  OR TRIM(d.issue_type) = '')
            THEN 1 ELSE 0
        END) AS `其他问题`,
    COUNT(d.id) AS `问题总数`
FROM group_dim g
LEFT JOIN batch_data d
       ON d.group_name = g.group_name
      AND d.is_sandbox = g.is_sandbox
GROUP BY g.sort_no, g.display_name
ORDER BY g.sort_no;

-- 2. 非已修复、非无需处理：按照领域、是否沙箱和问题状态统计。
WITH group_dim AS (
    SELECT 1 sort_no, '公共组' group_name, 0 is_sandbox, '公共组' display_name
    UNION ALL SELECT 2, '存款组', 0, '存款组'
    UNION ALL SELECT 3, '公共组', 1, '沙箱-公共组'
    UNION ALL SELECT 4, '存款组', 1, '沙箱-存款组'
    UNION ALL SELECT 5, '结算组', 1, '沙箱-结算组'
    UNION ALL SELECT 6, '贷款组', 1, '沙箱-贷款组'
    UNION ALL SELECT 7, '结算组', 0, '结算组'
    UNION ALL SELECT 8, '贷款组', 0, '贷款组'
),
batch_data AS (
    SELECT DISTINCT
           i.id,
           i.group_name,
           i.is_sandbox,
           i.issue_status
      FROM dii_replay_issue_occurrence_batch ob
      JOIN dii_replay_issue i
        ON i.id = ob.replay_issue_id
     WHERE ob.batch_name = @batch_name
       AND i.issue_status NOT IN ('已修复', '无需处理')
)
SELECT
    g.display_name AS `领域`,
    SUM(CASE WHEN d.issue_status = '新建'       THEN 1 ELSE 0 END) AS `新建`,
    SUM(CASE WHEN d.issue_status = '打开'       THEN 1 ELSE 0 END) AS `打开`,
    SUM(CASE WHEN d.issue_status = '重新打开'   THEN 1 ELSE 0 END) AS `重新打开`,
    SUM(CASE WHEN d.issue_status = '延后修复'   THEN 1 ELSE 0 END) AS `延后修复`,
    SUM(CASE WHEN d.issue_status = '修复待验证' THEN 1 ELSE 0 END) AS `修复待验证`,
    COUNT(d.id) AS `未修复总数`
FROM group_dim g
LEFT JOIN batch_data d
       ON d.group_name = g.group_name
      AND d.is_sandbox = g.is_sandbox
GROUP BY g.sort_no, g.display_name
ORDER BY g.sort_no;

-- 如需改为按“问题所属领域”统计，请将两个 batch_data 中的 i.group_name 替换为：
-- COALESCE(NULLIF(TRIM(i.issue_domain), ''), i.group_name) AS group_name
