#!/bin/bash
# ============================================================
# 查某张表在 GaussDB 上的所有索引及其列顺序
# 用途：跑 test-sql-inspect.sh 前先看看表到底有哪些索引，
#      好确定用哪几个字段来测"良"和"优"
# ============================================================
# 使用方法：
#   1. 修改下面的连接参数（或从 application-local.yml 拿）
#   2. ./check-table-indexes.sh kapb_txn_log
# ============================================================

TABLE="${1:-kapb_txn_log}"

# ── 按内网真实情况改这里 ─────────────────────────────────
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_NAME="${DB_NAME:-your_db_dev}"
DB_USER="${DB_USER:-gaussdb_user}"
# 密码从环境变量读取，避免写入脚本文件
# 使用：export PGPASSWORD=xxxxx; ./check-table-indexes.sh
# ────────────────────────────────────────────────────────

cat <<EOF
查询目标表：$TABLE
连接：$DB_USER@$DB_HOST:$DB_PORT/$DB_NAME

EOF

psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" <<SQL
SELECT
  i.relname     AS index_name,
  ix.indisprimary AS is_pk,
  ix.indisunique  AS is_unique,
  am.amname     AS index_type,
  string_agg(a.attname, ', ' ORDER BY k.ord) AS columns_ordered
FROM pg_index ix
JOIN pg_class t         ON t.oid = ix.indrelid
JOIN pg_class i         ON i.oid = ix.indexrelid
JOIN pg_am am           ON am.oid = i.relam
JOIN pg_namespace n     ON n.oid = t.relnamespace
JOIN unnest(ix.indkey) WITH ORDINALITY AS k(attnum, ord) ON TRUE
JOIN pg_attribute a     ON a.attrelid = t.oid AND a.attnum = k.attnum
WHERE t.relname = '$TABLE'
  AND n.nspname = current_schema()
GROUP BY i.relname, ix.indisprimary, ix.indisunique, am.amname
ORDER BY ix.indisprimary DESC, ix.indisunique DESC, i.relname;
SQL
