#!/bin/bash
# ================================================
# 交易链路分析平台 — 一键构建脚本
# 执行后生成 target/trans-link-server-1.0.0.jar
# ================================================
set -e

echo "=== [1/3] 构建前端 ==="
cd frontend
npm install
npm run build
cd ..

echo "=== [2/3] 构建后端 ==="
export JAVA_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null || echo $JAVA_HOME)
mvn clean package -DskipTests

echo "=== [3/3] 完成 ==="
echo "启动命令: java -jar target/axon-link-server-1.0.0.jar"
echo "访问地址: http://localhost:8123"
