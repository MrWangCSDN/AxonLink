#!/bin/bash
# ================================================
# 交易链路分析平台 — 一键构建脚本
# 执行后生成 target/trans-link-server-1.0.0.jar
# ================================================
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
FRONTEND_DIR="${SCRIPT_DIR}/../axon-link-frontend"

if [ ! -d "${FRONTEND_DIR}" ]; then
  echo "前端目录不存在: ${FRONTEND_DIR}"
  exit 1
fi

echo "=== [1/3] 构建前端 ==="
cd "${FRONTEND_DIR}"
npm install
npm run build
cd "${SCRIPT_DIR}"

echo "=== [2/3] 构建后端 ==="
export JAVA_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null || echo $JAVA_HOME)
mvn clean package -DskipTests

echo "=== [3/3] 完成 ==="
echo "启动命令: java -jar target/axon-link-server-1.0.0.jar"
echo "访问地址: http://localhost:8123"
