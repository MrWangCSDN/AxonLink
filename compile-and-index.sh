#!/usr/bin/env bash
# ============================================================
# compile-and-index.sh
#
# 按依赖层次并行编译 9 个 Maven 工程，每层编译完成后立刻
# 触发 trans-link-server 的增量索引 API，无需等待全部编译。
#
# 使用前：
#   1. 修改下方 "== 工程路径配置 ==" 区域，填入实际路径
#   2. 如 trans-link-server 未在 8123 端口运行，修改 INDEX_URL
#   3. chmod +x compile-and-index.sh && ./compile-and-index.sh
# ============================================================

set -euo pipefail

# == trans-link-server 索引刷新地址 ==========================
INDEX_URL="http://localhost:8123/api/source/index/refresh"

# == Maven 编译选项 ==========================================
# -T 1C  = 每 CPU 核 1 线程（同一工程内并行模块）
# 如果各工程是独立 Maven 项目（非多模块），此 -T 只影响同一工程内的多模块
MVN_OPTS="-DskipTests -q"

# == 工程路径配置 ============================================
# 修改为你实际的工程绝对路径
AP_DIR="/path/to/ap"

LAYER2_DIRS=(
  "/path/to/comm-api"
  "/path/to/dept-api"
  "/path/to/loan-api"
  "/path/to/sett-api"
)

LAYER3_DIRS=(
  "/path/to/comm-impl"
  "/path/to/dept-impl"
  "/path/to/loan-impl"
  "/path/to/sett-impl"
)

# ============================================================
# 工具函数
# ============================================================

log() { echo "[$(date '+%H:%M:%S')] $*"; }

# 编译单个工程（传入目录路径）
compile_one() {
  local dir="$1"
  local name
  name=$(basename "$dir")
  log "▶ 编译 $name ..."
  local t0=$SECONDS
  if mvn -f "$dir/pom.xml" install $MVN_OPTS 2>&1 | \
       grep -E "BUILD (SUCCESS|FAILURE)|ERROR" | head -3; then
    log "✓ $name 完成，耗时 $((SECONDS - t0))s"
  else
    log "✗ $name 编译失败，请检查日志"
    return 1
  fi
}

# 并行编译一组工程，全部完成后返回
compile_layer() {
  local label="$1"; shift
  local dirs=("$@")
  log "═══ $label 开始（${#dirs[@]} 个工程并行）═══"
  local t0=$SECONDS
  local pids=()

  for dir in "${dirs[@]}"; do
    compile_one "$dir" &
    pids+=($!)
  done

  # 等待所有并行编译完成
  local failed=0
  for pid in "${pids[@]}"; do
    wait "$pid" || failed=$((failed + 1))
  done

  log "═══ $label 完成，耗时 $((SECONDS - t0))s（失败 $failed 个）═══"
  [[ $failed -eq 0 ]]
}

# 触发 trans-link-server 增量索引
trigger_index() {
  local label="$1"
  log "⟳ 触发索引刷新（$label）..."
  if curl -sf -X POST "$INDEX_URL" \
       -H "Content-Type: application/json" \
       --max-time 300 | \
       python3 -c "
import sys, json
d = json.load(sys.stdin)
print(f\"  收录类：{d.get('classCount',0)}  解析文件：{d.get('parsed',0)}  耗时：{d.get('elapsedMs',0)} ms\")
for r in d.get('roots', []):
    print(f\"    {r.get('parsed',0):4d} 个 | {r.get('ms',0):5d} ms | {r.get('root','')}\")
"; then
    log "✓ 索引刷新完成"
  else
    log "⚠ 索引刷新失败（trans-link-server 未启动？忽略继续）"
  fi
}

# ============================================================
# 主流程
# ============================================================

TOTAL_START=$SECONDS

# Layer 1：ap（其他所有工程的基础，必须先单独编译）
compile_one "$AP_DIR"
trigger_index "Layer 1: ap"

# Layer 2：所有 *-api 并行编译（依赖 ap，互相独立）
compile_layer "Layer 2: api 工程" "${LAYER2_DIRS[@]}"
trigger_index "Layer 2: api 工程"

# Layer 3：所有 *-impl 并行编译（依赖各自的 api）
compile_layer "Layer 3: impl 工程" "${LAYER3_DIRS[@]}"
trigger_index "Layer 3: impl 工程"

log "══════════════════════════════════════════════"
log "全部编译 + 索引完成，总耗时 $((SECONDS - TOTAL_START))s"
log "══════════════════════════════════════════════"
