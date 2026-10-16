-- 回放数据库比对字段登记：今天的两类删除历史（MySQL，只读）
-- 查询日期：2026-10-15；在应用结果库执行。
-- 时间按应用写入 operated_at 的本地时间。
-- 查其他日期时，请同时修改两条 SQL 的起止时间。
-- 此处“整表删除”指删除比对登记，不是 DROP TABLE 或删除业务表数据。
-- 两条查询的“表中文名”均取当前登记记录的 table_comment，不是删除时的名称快照。
-- 整表登记删除为软删除，名称仍可关联；若登记记录已被物理清理，名称为空但保留删除历史。

-- 一、今天整表登记删除的历史：每次删除操作一行。
-- 即使该表后来重新登记，仍保留今天曾删除整表登记的记录。
-- 本次移除比对字段数只统计字段删除明细，不包含“删除状态”明细。
SELECT
    e.id                AS `变更事件ID`,
    e.registration_id   AS `登记ID`,
    e.schema_name       AS `库名`,
    e.table_name        AS `表英文名`,
    r.table_comment     AS `表中文名`,
    '整表登记删除'      AS `删除类型`,
    e.operator_emp_no   AS `操作人工号`,
    e.operator_username AS `操作人账号`,
    e.operator_name     AS `操作人姓名`,
    e.operated_at       AS `删除时间`,
    e.reason            AS `删除原因`,
    (
        SELECT COUNT(*)
        FROM dii_replay_db_compare_audit_detail d
        WHERE d.audit_event_id = e.id
          AND d.change_type = 'DELETE'
          AND d.field_code LIKE 'comparisonFields.%'
    )                   AS `本次移除比对字段数`
FROM dii_replay_db_compare_audit_event e
LEFT JOIN dii_replay_db_compare_registration r
       ON r.id = e.registration_id
WHERE e.operation = 'DELETE'
  AND e.operated_at >= '2026-10-15 00:00:00'
  AND e.operated_at <  '2026-10-16 00:00:00'
ORDER BY e.operated_at DESC, e.id DESC;

-- 二、今天发生字段删除，且表登记当前仍有效：每个被删除字段一行。
-- e.operation <> 'DELETE' 排除整表登记删除时产生的字段删除明细。
-- r.deleted = 0 表示当前登记仍有效；不是判断物理数据库表是否存在。
-- 字段中文名取删除时的审计值，不依赖已经被删除的当前字段记录。
-- 本查询统计“今天删除过”，字段后续重新加回也保留本次删除历史。
SELECT
    e.id                AS `变更事件ID`,
    e.registration_id   AS `登记ID`,
    e.schema_name       AS `库名`,
    e.table_name        AS `表英文名`,
    r.table_comment     AS `表中文名`,
    '比对字段删除'      AS `删除类型`,
    SUBSTRING(d.field_code, CHAR_LENGTH('comparisonFields.') + 1)
                        AS `被删除字段英文名`,
    d.before_value      AS `被删除字段中文名`,
    e.operator_emp_no   AS `操作人工号`,
    e.operator_username AS `操作人账号`,
    e.operator_name     AS `操作人姓名`,
    e.operated_at       AS `删除时间`,
    e.reason            AS `操作原因`
FROM dii_replay_db_compare_audit_event e
JOIN dii_replay_db_compare_audit_detail d
  ON d.audit_event_id = e.id
JOIN dii_replay_db_compare_registration r
  ON r.id = e.registration_id
 AND r.deleted = 0
WHERE e.operation <> 'DELETE'
  AND d.change_type = 'DELETE'
  AND d.field_code LIKE 'comparisonFields.%'
  AND e.operated_at >= '2026-10-15 00:00:00'
  AND e.operated_at <  '2026-10-16 00:00:00'
ORDER BY e.operated_at DESC, e.id DESC, d.detail_order ASC, d.id ASC;
