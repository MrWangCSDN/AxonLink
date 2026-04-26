#!/bin/bash
# ============================================================
# Phase 2a.1 · SQL 索引命中评级 · 内网测试脚本
# ============================================================
# 使用方法：
#   1. 改下面的 TABLE / INDEX_* 变量，填你们内网真实存在的表和索引
#   2. 在内网服务器执行：bash test-sql-inspect.sh
#   3. 每个 case 会打印 ① 期望评级  ② 实际响应
# ============================================================

BASE_URL="http://localhost:8123"
ENDPOINT="${BASE_URL}/api/ai/dao-index/debug/analyze-sql"
ENV="dev"

# ── ⚠️ 必填：按内网真实情况改下面 4 行 ────────────────────
TABLE="kapb_txn_log"                       # 真实存在的表名
COL_A="ahrn_trid"                          # 某复合索引的第 1 列
COL_B="mnt_tlr_seqnum"                     # 某复合索引的第 2 列
COL_C="mnt_bkgrd_seqnum"                   # 某复合索引的第 3 列（可选，如果没三列索引可以空）
COL_NON_INDEX="rmrk"                       # 肯定没有索引的字段（用作"差"的对照组）
# ────────────────────────────────────────────────────────────

# 检查 python3 可用性（用于 JSON 美化输出）
if command -v python3 >/dev/null 2>&1; then
    JSON_FMT="python3 -m json.tool"
else
    JSON_FMT="cat"
fi

run_case() {
    local title="$1"
    local expected="$2"
    local sql="$3"
    echo ""
    echo "═══════════════════════════════════════════════════════════════"
    echo "CASE: $title"
    echo "期望评级: $expected"
    echo "SQL:     $sql"
    echo "───────────────────────────────────────────────────────────────"
    curl -s -X POST "$ENDPOINT" \
         -H "Content-Type: application/json" \
         -d "{\"sql\": \"$sql\", \"env\": \"$ENV\"}" \
         | $JSON_FMT
}

echo "目标接口: $ENDPOINT"
echo "目标环境: $ENV"
echo "目标表:   $TABLE"

# ══════════════════════════════════════════════════════════════
# 第一组：占位符兼容性测试（验证 #xxx# / ${xxx} / :xxx 自动转换）
# ══════════════════════════════════════════════════════════════

run_case "占位符 #xxx# (sunline / iBatis)" \
         "应正常解析，不报 SQL 错" \
         "SELECT * FROM $TABLE WHERE $COL_A = #ahrnTrid#"

run_case "占位符 \${xxx} (MyBatis)" \
         "应正常解析" \
         "SELECT * FROM $TABLE WHERE $COL_A = \${ahrnTrid}"

run_case "占位符 :xxx (Spring JDBC)" \
         "应正常解析（注意避开 PG 的 ::int 类型转换）" \
         "SELECT * FROM $TABLE WHERE $COL_A = :ahrnTrid"

run_case "标准 JDBC 占位符 ?" \
         "应正常解析" \
         "SELECT * FROM $TABLE WHERE $COL_A = ?"

# ══════════════════════════════════════════════════════════════
# 第二组：评级正确性测试（最左匹配判定）
# ══════════════════════════════════════════════════════════════

run_case "单列等值 (应命中复合索引的第 1 列)" \
         "良 — 命中某索引前 1 列" \
         "SELECT * FROM $TABLE WHERE $COL_A = ?"

run_case "前 2 列等值" \
         "良 或 优（取决于该复合索引是 2 列还是 3 列）" \
         "SELECT * FROM $TABLE WHERE $COL_A = ? AND $COL_B = ?"

run_case "前 3 列等值（若有 3 列复合索引则优）" \
         "优 或 良" \
         "SELECT * FROM $TABLE WHERE $COL_A = ? AND $COL_B = ? AND $COL_C = ?"

run_case "跳列失效（第 1 列 + 第 3 列，跳过第 2 列）" \
         "良 — 应只命中第 1 列，第 3 列因跳列失效" \
         "SELECT * FROM $TABLE WHERE $COL_A = ? AND $COL_C = ?"

run_case "跳过最左列（只给第 2 列）" \
         "差 — 最左匹配断裂" \
         "SELECT * FROM $TABLE WHERE $COL_B = ?"

run_case "查询无索引字段" \
         "差 — 没有任何索引的最左列能匹配 $COL_NON_INDEX" \
         "SELECT * FROM $TABLE WHERE $COL_NON_INDEX = ?"

run_case "无 WHERE 条件（全表扫描）" \
         "差 — 谓词为空" \
         "SELECT * FROM $TABLE"

run_case "IN 谓词（视为等值）" \
         "良 / 优 — IN 和 = 同等处理" \
         "SELECT * FROM $TABLE WHERE $COL_A IN (?, ?, ?)"

# ══════════════════════════════════════════════════════════════
# 第三组：sunline 真实 DAO 风格（综合场景）
# ══════════════════════════════════════════════════════════════

run_case "真实 DAO 风格 · 单条件查询" \
         "按你们该表索引判定" \
         "SELECT * FROM $TABLE WHERE $COL_A = #ahrnTrid#"

run_case "真实 DAO 风格 · 多条件 + IN" \
         "按你们该表索引判定" \
         "SELECT * FROM $TABLE WHERE $COL_A = #ahrnTrid# AND $COL_B IN (#v1#, #v2#)"

# ══════════════════════════════════════════════════════════════
# 第四组：2a.1 已知不支持场景（预期会有 warning 或判偏低）
# ══════════════════════════════════════════════════════════════

run_case "范围查询（2a.1 暂不识别，会判偏低 ⚠️ 2a.2 修复）" \
         "目前会判'差'或只算等值部分；2a.2 会支持范围谓词" \
         "SELECT * FROM $TABLE WHERE $COL_A > ?"

run_case "LIKE 查询（2a.1 暂不识别）" \
         "目前会判'差'；2a.2 会区分前缀 LIKE 和前导通配" \
         "SELECT * FROM $TABLE WHERE $COL_A LIKE 'ABC%'"

run_case "UPDATE（2a.1 暂不支持，仅 SELECT）" \
         "警告：不支持该 SQL 类型" \
         "UPDATE $TABLE SET rmrk = ? WHERE $COL_A = ?"

echo ""
echo "═══════════════════════════════════════════════════════════════"
echo "全部测试完成"
echo "═══════════════════════════════════════════════════════════════"
