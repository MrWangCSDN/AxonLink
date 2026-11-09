-- MySQL 8.0+ / MySQL 9.x；请在结果库的新会话中整段执行。
-- 不修改业务表，只创建本会话临时表；须有 CREATE TEMPORARY TABLES 权限。
-- 1. NULL 自动取业务日期最新的已导入批次；指定批次时改为实际值，例如 'DB20261029-01'。
-- 2. 问题总数：该批次最后一次导入仍实际出现、differenceCount>0 的表级/字段级问题数。
--    分类使用问题当前的 issue_type，包含目前已修复的问题；不是“当前未解决问题数”。
-- 3. 未处理：issue_type 为 NULL、空串或全空格；排查进度=(问题总数-未处理)/问题总数。
-- 4. “主键相同、字段不同”按用户确认的字段级 difference_count 求和；
--    一条记录多个字段不同时会分别计数，不是按主键去重后的差异记录数。
-- 5. “字段总数”读取这些表【当前唯一有效登记配置】中的字段数，不是历史批次原始字段数。
--    有参与表缺少登记或存在跨 schema 多份有效登记时，该领域及合计字段总数返回 NULL。
-- 6. 问题类型列来自数据库实际非空 issue_type，自动适应新增/改名，不写死截图旧枚举。
--    领域取当前表人员清单，与页面相同；未映射的表保留在“未分配领域”，不丢弃。

SET @rdb_report_batch_no = NULL;
SET @rdb_report_batch_no = COALESCE(NULLIF(TRIM(@rdb_report_batch_no), ''), (
    SELECT batch_no FROM dii_replay_database_issue_batch
    ORDER BY batch_date DESC, imported_at DESC, batch_no DESC LIMIT 1
));
SET @rdb_saved_group_concat_max_len = @@SESSION.group_concat_max_len;
SET SESSION group_concat_max_len = 1048576;

DROP TEMPORARY TABLE IF EXISTS tmp_rdb_report_scope;
DROP TEMPORARY TABLE IF EXISTS tmp_rdb_report_tables;
DROP TEMPORARY TABLE IF EXISTS tmp_rdb_report_metrics;
DROP TEMPORARY TABLE IF EXISTS tmp_rdb_report_domains;

-- 每个 issue 只取指定批次最后一次导入事件，避免同批次重导把已消失的问题再次统计。
-- incoming_snapshot=NULL 表示该次导入未出现/自动修复，该问题不纳入本批次。
-- 仅没有该批次导入历史的旧数据，才回退当前行批次和 data_snapshot。
CREATE TEMPORARY TABLE tmp_rdb_report_scope AS
WITH ranked_imports AS (
    SELECT h.issue_id, h.incoming_snapshot,
           ROW_NUMBER() OVER (PARTITION BY h.issue_id ORDER BY h.operation_at DESC, h.id DESC) AS rn
    FROM dii_replay_database_issue_history h
    WHERE h.batch_no = @rdb_report_batch_no AND h.import_event = 1
), batch_rows AS (
    SELECT i.id AS issue_row_id, i.table_name, i.issue_level,
           NULLIF(TRIM(i.issue_type), '') AS issue_type,
           h.incoming_snapshot AS raw_snapshot
    FROM ranked_imports h JOIN dii_replay_database_issue i ON i.id = h.issue_id
    WHERE h.rn = 1 AND h.incoming_snapshot IS NOT NULL
    UNION ALL
    SELECT i.id, i.table_name, i.issue_level, NULLIF(TRIM(i.issue_type), ''), i.data_snapshot
    FROM dii_replay_database_issue i
    WHERE i.batch_no = @rdb_report_batch_no
      AND NOT EXISTS (SELECT 1 FROM ranked_imports h WHERE h.issue_id = i.id)
), normalized AS (
    SELECT b.*, CASE WHEN JSON_VALID(b.raw_snapshot) THEN b.raw_snapshot ELSE '{}' END AS payload
    FROM batch_rows b
)
SELECT n.issue_row_id, LOWER(n.table_name) AS table_name, n.issue_level, n.issue_type,
       COALESCE(NULLIF(TRIM(p.domain_name), ''), '未分配领域') AS domain_name,
       CAST(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(n.payload, '$.differenceCount')), '0') AS UNSIGNED) AS difference_count,
       CAST(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(n.payload, '$.total528')), '0') AS UNSIGNED) AS total_528,
       CAST(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(n.payload, '$.totalCcbs')), '0') AS UNSIGNED) AS total_ccbs,
       CAST(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(n.payload, '$.only528Count')), '0') AS UNSIGNED) AS only_528,
       CAST(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(n.payload, '$.onlyCcbsCount')), '0') AS UNSIGNED) AS only_ccbs
FROM normalized n
LEFT JOIN dii_replay_database_table_person p ON p.table_name = LOWER(n.table_name);

-- 表总量只从“表级”记录取一次，不能把附带同一总量的每个字段级问题一起求和。
CREATE TEMPORARY TABLE tmp_rdb_report_tables AS
SELECT t.*, cfg.field_count
FROM (
    SELECT domain_name, table_name,
           MAX(CASE WHEN issue_level = '表级' THEN total_528 ELSE 0 END) AS total_528,
           MAX(CASE WHEN issue_level = '表级' THEN total_ccbs ELSE 0 END) AS total_ccbs,
           MAX(CASE WHEN issue_level = '表级' THEN only_528 ELSE 0 END) AS only_528,
           MAX(CASE WHEN issue_level = '表级' THEN only_ccbs ELSE 0 END) AS only_ccbs,
           SUM(CASE WHEN issue_level = '字段级' THEN difference_count ELSE 0 END) AS field_differences,
           CASE WHEN MAX(CASE WHEN issue_level = '表级' THEN difference_count ELSE 0 END) = 0
                     AND SUM(CASE WHEN issue_level = '字段级' THEN difference_count ELSE 0 END) = 0
                THEN 1 ELSE 0 END AS identical_table
    FROM tmp_rdb_report_scope
    GROUP BY domain_name, table_name
    HAVING SUM(issue_level = '表级') > 0
) t
LEFT JOIN (
    SELECT LOWER(r.table_name) AS table_name,
           CASE WHEN COUNT(DISTINCT r.id) = 1 THEN COUNT(f.id) ELSE NULL END AS field_count
    FROM dii_replay_db_compare_registration r
    LEFT JOIN dii_replay_db_compare_field f ON f.registration_id = r.id
    WHERE r.deleted = 0
    GROUP BY LOWER(r.table_name)
) cfg ON cfg.table_name = t.table_name;

CREATE TEMPORARY TABLE tmp_rdb_report_metrics AS
SELECT COALESCE(domain_name, '合计') AS domain_name,
       SUM(total_528) AS total_528, SUM(total_ccbs) AS total_ccbs,
       SUM(only_528) AS only_528, SUM(only_ccbs) AS only_ccbs,
       SUM(field_differences) AS field_differences, COUNT(*) AS table_count,
       CASE WHEN SUM(field_count IS NULL) > 0 THEN NULL ELSE SUM(field_count) END AS field_count,
       SUM(identical_table) AS identical_tables
FROM tmp_rdb_report_tables
GROUP BY domain_name WITH ROLLUP;

-- 保留截图四个领域的零值行，同时纳入数据库实际出现的其他领域。
CREATE TEMPORARY TABLE tmp_rdb_report_domains AS
SELECT '公共组' AS domain_name UNION SELECT '存款组' UNION SELECT '结算组'
UNION SELECT '贷款组' UNION SELECT '合计'
UNION SELECT domain_name FROM tmp_rdb_report_tables;

-- 使用 HEX 字面量和转义列名，类型名称中的引号/反引号不会破坏动态 SQL。
SELECT GROUP_CONCAT(CONCAT(
           ',SUM(CASE WHEN issue_type=CONVERT(0x', HEX(issue_type), ' USING utf8mb4) THEN 1 ELSE 0 END) AS ty_', seq
       ) ORDER BY seq SEPARATOR ''),
       GROUP_CONCAT(CONCAT(
           ',COALESCE(i.ty_', seq, ',0) AS `', REPLACE(issue_type, '`', '``'), '`'
       ) ORDER BY seq SEPARATOR '')
INTO @rdb_type_aggregates, @rdb_type_projection
FROM (
    SELECT issue_type, ROW_NUMBER() OVER (ORDER BY issue_type) AS seq
    FROM (
        SELECT DISTINCT NULLIF(TRIM(issue_type), '') AS issue_type
        FROM dii_replay_database_issue
        WHERE NULLIF(TRIM(issue_type), '') IS NOT NULL
    ) actual_types
) numbered_types;

SET @rdb_report_sql = CONCAT(
'SELECT d.domain_name AS `领域`,
 COALESCE(m.total_528,0) AS `528总量`, COALESCE(m.total_ccbs,0) AS `ccbs总量`,
 COALESCE(m.only_528,0) AS `仅528存在`, COALESCE(m.only_ccbs,0) AS `仅ccbs存在`,
 COALESCE(m.field_differences,0) AS `主键相同、字段不同`,
 COALESCE(m.table_count,0) AS `参与比对表数量`,
 CASE WHEN COALESCE(m.table_count,0)=0 THEN 0 ELSE m.field_count END AS `字段总数`,
 COALESCE(m.identical_tables,0) AS `比对一致表数量`,
 COALESCE(i.issue_total,0) AS `问题总数（注）`, COALESCE(i.unprocessed,0) AS `未处理`',
 COALESCE(@rdb_type_projection, ''),
 ',CONCAT(FORMAT(CASE WHEN COALESCE(i.issue_total,0)=0 THEN 0
       ELSE 100.0*(i.issue_total-i.unprocessed)/i.issue_total END,2),''%'') AS `排查进度`
 FROM tmp_rdb_report_domains d
 LEFT JOIN tmp_rdb_report_metrics m ON m.domain_name=d.domain_name
 LEFT JOIN (
   SELECT COALESCE(domain_name,''合计'') AS domain_name,
          COUNT(*) AS issue_total, SUM(issue_type IS NULL) AS unprocessed',
 COALESCE(@rdb_type_aggregates, ''),
 ' FROM tmp_rdb_report_scope WHERE difference_count>0
   GROUP BY domain_name WITH ROLLUP
 ) i ON i.domain_name=d.domain_name
 ORDER BY CASE d.domain_name WHEN ''公共组'' THEN 1 WHEN ''存款组'' THEN 2
          WHEN ''结算组'' THEN 3 WHEN ''贷款组'' THEN 4 WHEN ''合计'' THEN 999 ELSE 5 END,
          d.domain_name'
);
PREPARE rdb_report_statement FROM @rdb_report_sql;
EXECUTE rdb_report_statement;
DEALLOCATE PREPARE rdb_report_statement;

DROP TEMPORARY TABLE tmp_rdb_report_domains;
DROP TEMPORARY TABLE tmp_rdb_report_metrics;
DROP TEMPORARY TABLE tmp_rdb_report_tables;
DROP TEMPORARY TABLE tmp_rdb_report_scope;
SET SESSION group_concat_max_len = @rdb_saved_group_concat_max_len;
