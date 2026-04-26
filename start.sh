#!/bin/bash
# ================================================
# AxonLink — 启动脚本（Linux / macOS 通用）
# ================================================

# ── 路径定义 ─────────────────────────────────────
BASE_DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="$BASE_DIR/target/axon-link-server-1.0.0.jar"
CONFIG="$BASE_DIR/src/main/resources/application-local.yml"
LOG="$BASE_DIR/axon-link.log"
PID_FILE="$BASE_DIR/axon-link.pid"

# ── Java 路径检测 ─────────────────────────────────
# 优先使用环境变量 JAVA_HOME，其次 macOS java_home 工具，最后 PATH 中的 java
if [ -n "$JAVA_HOME" ]; then
    JAVA="$JAVA_HOME/bin/java"
elif command -v /usr/libexec/java_home &>/dev/null; then
    JAVA_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null || /usr/libexec/java_home 2>/dev/null)
    JAVA="$JAVA_HOME/bin/java"
else
    JAVA="java"
fi

# ── 前置检查 ──────────────────────────────────────
if [ ! -f "$JAR" ]; then
    echo "[ERROR] 找不到 JAR 包：$JAR"
    echo "        请先执行：mvn package -DskipTests"
    exit 1
fi

if ! "$JAVA" -version &>/dev/null 2>&1; then
    echo "[ERROR] 找不到 Java，请设置 JAVA_HOME 环境变量"
    exit 1
fi

JAVA_VER=$("$JAVA" -version 2>&1 | head -1)
echo "[AxonLink] Java: $JAVA_VER"

# ── 检查是否已运行 ────────────────────────────────
if [ -f "$PID_FILE" ]; then
    OLD_PID=$(cat "$PID_FILE")
    if kill -0 "$OLD_PID" 2>/dev/null; then
        echo "[AxonLink] 服务已在运行 (PID=$OLD_PID)，如需重启请先执行 ./stop.sh"
        exit 0
    else
        echo "[AxonLink] 清理过期 PID 文件"
        rm -f "$PID_FILE"
    fi
fi

# ── 组装启动参数 ──────────────────────────────────
JVM_OPTS="-Xms256m -Xmx1024m"
APP_ARGS=""

# ── HTTP 代理（容器部署场景）──────────────────────
# 自动读环境变量：HTTP_PROXY / HTTPS_PROXY / NO_PROXY（大小写都认）
# 格式：http://proxy-host:port 或 http://user:pass@proxy-host:port
#
# 示例：
#   export HTTP_PROXY=http://10.0.0.1:8080
#   export HTTPS_PROXY=http://10.0.0.1:8080
#   export NO_PROXY=localhost,127.0.0.1,.spdb.com
#   ./start.sh
#
# 解析出 host+port 后翻译成 JVM 系统属性，Java 的 HttpClient 才会真正走代理。
parse_proxy() {
    # 入参 $1 = 代理 URL，设置同名环境变量 _PROXY_HOST / _PROXY_PORT
    local url="$1"
    # 去掉协议前缀
    url="${url#http://}"
    url="${url#https://}"
    # 去掉 user:pass@
    url="${url##*@}"
    # 去掉末尾 / 和路径
    url="${url%%/*}"
    _PROXY_HOST="${url%:*}"
    _PROXY_PORT="${url##*:}"
    # 没端口则默认 80
    [ "$_PROXY_HOST" = "$_PROXY_PORT" ] && _PROXY_PORT=80
}

HTTP_PROXY_VAL="${HTTP_PROXY:-$http_proxy}"
HTTPS_PROXY_VAL="${HTTPS_PROXY:-$https_proxy}"
NO_PROXY_VAL="${NO_PROXY:-$no_proxy}"

if [ -n "$HTTP_PROXY_VAL" ]; then
    parse_proxy "$HTTP_PROXY_VAL"
    JVM_OPTS="$JVM_OPTS -Dhttp.proxyHost=$_PROXY_HOST -Dhttp.proxyPort=$_PROXY_PORT"
    echo "[AxonLink] HTTP 代理：$_PROXY_HOST:$_PROXY_PORT"
fi

if [ -n "$HTTPS_PROXY_VAL" ]; then
    parse_proxy "$HTTPS_PROXY_VAL"
    JVM_OPTS="$JVM_OPTS -Dhttps.proxyHost=$_PROXY_HOST -Dhttps.proxyPort=$_PROXY_PORT"
    echo "[AxonLink] HTTPS 代理：$_PROXY_HOST:$_PROXY_PORT"
fi

if [ -n "$NO_PROXY_VAL" ]; then
    # Java 要求 nonProxyHosts 用 | 分隔，逗号分隔要转成 |
    JAVA_NO_PROXY=$(echo "$NO_PROXY_VAL" | sed 's/,/|/g')
    JVM_OPTS="$JVM_OPTS -Dhttp.nonProxyHosts=$JAVA_NO_PROXY -Dhttps.nonProxyHosts=$JAVA_NO_PROXY"
    echo "[AxonLink] 代理白名单：$NO_PROXY_VAL"
fi

# 允许通过 JAVA_OPTS 额外追加 JVM 参数（比如 -Dfile.encoding=UTF-8）
if [ -n "$JAVA_OPTS" ]; then
    JVM_OPTS="$JVM_OPTS $JAVA_OPTS"
fi

# 如果 application-local.yml 存在，通过 --spring.config.import 加载
if [ -f "$CONFIG" ]; then
    APP_ARGS="$APP_ARGS --spring.config.import=optional:file:${CONFIG}"
fi

# 支持外部覆盖参数：./start.sh --server.port=8124
APP_ARGS="$APP_ARGS $*"

# ── 启动 ──────────────────────────────────────────
echo "[AxonLink] 启动中，日志 → $LOG"
nohup "$JAVA" $JVM_OPTS -jar "$JAR" $APP_ARGS >> "$LOG" 2>&1 &
APP_PID=$!
echo $APP_PID > "$PID_FILE"
echo "[AxonLink] 进程 PID=$APP_PID，等待就绪..."

# ── 等待启动（最多 60s）──────────────────────────
for i in $(seq 1 12); do
    sleep 5
    if grep -q "Started AxonLinkApplication" "$LOG" 2>/dev/null; then
        # 提取监听端口
        PORT=$(grep -oE 'port(s)?: [0-9]+' "$LOG" 2>/dev/null | tail -1 | grep -oE '[0-9]+' || echo "8123")
        LOCAL_IP=$(hostname -I 2>/dev/null | awk '{print $1}' || echo "localhost")
        echo "[AxonLink] ✅ 启动成功！访问地址：http://${LOCAL_IP}:8123"
        exit 0
    fi
    if grep -q "APPLICATION FAILED\|BUILD FAILURE" "$LOG" 2>/dev/null; then
        echo "[ERROR] 启动失败，请查看日志：$LOG"
        tail -20 "$LOG"
        rm -f "$PID_FILE"
        exit 1
    fi
    echo "  等待中... ($((i * 5))s)"
done

echo "[WARN] 启动超时（60s），请检查日志：$LOG"
