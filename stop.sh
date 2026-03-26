#!/bin/bash
# ================================================
# AxonLink — 停止脚本
# ================================================

BASE_DIR="$(cd "$(dirname "$0")" && pwd)"
PID_FILE="$BASE_DIR/axon-link.pid"
LOG="$BASE_DIR/axon-link.log"

# ── 从 PID 文件停止 ───────────────────────────────
if [ -f "$PID_FILE" ]; then
    PID=$(cat "$PID_FILE")
    if kill -0 "$PID" 2>/dev/null; then
        echo "[AxonLink] 正在停止 (PID=$PID)..."
        kill "$PID"
        # 等待进程退出（最多 15s）
        for i in $(seq 1 15); do
            sleep 1
            if ! kill -0 "$PID" 2>/dev/null; then
                rm -f "$PID_FILE"
                echo "[AxonLink] ✅ 已停止"
                exit 0
            fi
        done
        # 超时强杀
        echo "[WARN] 优雅停止超时，强制终止..."
        kill -9 "$PID" 2>/dev/null
        rm -f "$PID_FILE"
        echo "[AxonLink] 已强制终止"
    else
        echo "[AxonLink] PID=$PID 进程已不存在，清理 PID 文件"
        rm -f "$PID_FILE"
    fi
    exit 0
fi

# ── 兜底：按进程名查找 ────────────────────────────
PIDS=$(pgrep -f "axon-link-server.*\.jar" 2>/dev/null)
if [ -n "$PIDS" ]; then
    echo "[AxonLink] 未找到 PID 文件，按进程名停止 (PIDs=$PIDS)..."
    kill $PIDS
    sleep 2
    echo "[AxonLink] ✅ 已停止"
else
    echo "[AxonLink] 服务未在运行"
fi
