-- 回放数据库比对字段登记：2026-10-15 当天变更历史（MySQL）
-- 在应用结果库中执行；仅查询，不修改数据。
-- 时间按应用写入 operated_at 的本地时间；本次查询 2026-10-15 全天。
-- 查询其他日期时，同时调整下面的起止时间，结束时间为次日零点。
-- 一行表示一个变更明细，同一事件可能出现多行，事件 ID 可用于归组。
-- LEFT JOIN 保留没有明细的操作事件；不依赖当前登记记录，已删除登记也能查到。
-- 前后值保留审计表原始内容；NULL 表示当时未记录该侧值。
-- operator_username 来自已部署的 V66 迁移。

SELECT
    e.id                   AS `变更事件ID`,
    e.registration_id      AS `登记ID`,
    e.schema_name          AS `库名`,
    e.table_name           AS `表英文名`,
    CASE e.operation
        WHEN 'CREATE'     THEN '新增登记'
        WHEN 'UPDATE'     THEN '修改'
        WHEN 'IMPORT'     THEN '初始化导入'
        WHEN 'DELETE'     THEN '删除'
        WHEN 'REREGISTER' THEN '重新登记'
        ELSE e.operation
    END                    AS `操作类型`,
    e.operator_emp_no      AS `操作人工号`,
    e.operator_username    AS `操作人账号`,
    e.operator_name        AS `操作人姓名`,
    e.operated_at          AS `操作时间`,
    e.registration_version AS `登记版本`,
    e.change_count         AS `本次变更项数`,
    e.reason               AS `操作原因`,
    d.detail_order         AS `明细序号`,
    CASE d.change_type
        WHEN 'ADD'     THEN '新增'
        WHEN 'MODIFY'  THEN '修改'
        WHEN 'DELETE'  THEN '删除'
        WHEN 'REORDER' THEN '排序'
        ELSE d.change_type
    END                    AS `变更类型`,
    d.field_code           AS `变更字段编码`,
    d.field_label          AS `变更内容`,
    d.before_value         AS `修改前`,
    d.after_value          AS `修改后`
FROM dii_replay_db_compare_audit_event e
LEFT JOIN dii_replay_db_compare_audit_detail d
       ON d.audit_event_id = e.id
WHERE e.operated_at >= '2026-10-15 00:00:00'
  AND e.operated_at <  '2026-10-16 00:00:00'
ORDER BY e.operated_at DESC, e.id DESC, d.detail_order ASC, d.id ASC;
