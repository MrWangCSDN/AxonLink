#!/usr/bin/env bash
# ============================================================
# 协同人邮件功能启动脚本（内网 / 外网通用）
# 用法：在解压后的 axon-link-server 根目录执行
#   bash scripts/intranet-mail-test/02-start-intranet.sh
#
# 端口、SMTP、SSL/STARTTLS 等差异<b>全部通过配置文件控制</b>（application-local.yml 或 ENV 变量）。
# 本脚本不做环境判断，只读取 ENV（若已设）→ 转给 JVM 启动参数。
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BASE_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
cd "${BASE_DIR}"

# ── 1. 数据库（结果库 benchmarkdb）──────────────────────────────────
# 内网默认值已写在 application.yml；如地址/账号不同，用 ENV 覆盖：
export DII_RESULT_URL="${DII_RESULT_URL:-jdbc:mysql://21.64.203.16:3306/benchmarkdb?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true}"
export DII_RESULT_USERNAME="${DII_RESULT_USERNAME:-benchmark}"
export DII_RESULT_PASSWORD="${DII_RESULT_PASSWORD:-benchmark123}"

# ── 2. SMTP 邮件配置（端口、SSL/STARTTLS 等全在 application.yml / application-local.yml 里配置）────
# ENV 覆盖可选；脚本<b>不做端口判断</b>，由配置文件控制 SSL/STARTTLS 行为：
#   application.yml 默认 465+SSL；如需 25/587 等其他配置，编辑 application-local.yml 覆盖。
export MAIL_HOST="${MAIL_HOST:-}"
export MAIL_PORT="${MAIL_PORT:-}"
export MAIL_USERNAME="${MAIL_USERNAME:-}"
export MAIL_PASSWORD="${MAIL_PASSWORD:-}"
export MAIL_FROM="${MAIL_FROM:-}"

# ── 3. 构建（如未构建则执行；首次部署需 JDK17 + Maven）─────────────
JAR="${BASE_DIR}/target/axon-link-server-1.0.0.jar"
if [ ! -f "${JAR}" ]; then
  echo "=== 未找到 jar，开始构建（需 JDK17 + Maven + 能访问依赖仓库）==="
  export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null || true)}"
  mvn clean package -DskipTests
fi

# ── 4. 启动 ──────────────────────────────────────────────────────
# SMTP 选项从 application.yml / application-local.yml 读取；
# ENV（MAIL_HOST 等）经 Spring 映射后作为 spring.mail.* 覆盖。
echo "=== 启动 AxonLink（邮件功能）==="
echo "  数据源: ${DII_RESULT_URL%%\?*}"
echo "  SMTP  : ${MAIL_HOST:-<从 application.yml 读取>}:${MAIL_PORT:-<从 application.yml 读取>}  from=${MAIL_FROM:-<从 application.yml 读取>}"
echo "  配置 : application-local.yml / ENV 覆盖（如需改 SMTP 行为请改 application-local.yml）"
java -jar "${JAR}" \
  ${MAIL_HOST:+--spring.mail.host="${MAIL_HOST}"} \
  ${MAIL_PORT:+--spring.mail.port="${MAIL_PORT}"} \
  ${MAIL_USERNAME:+--spring.mail.username="${MAIL_USERNAME}"} \
  ${MAIL_PASSWORD:+--spring.mail.password="${MAIL_PASSWORD}"} \
  ${MAIL_FROM:+--axon-link.mail.from="${MAIL_FROM}"}

# ============================================================
# 配置说明：
#   内网 SMTP 行为（如 25 端口明文 / 465 SSL / 587 STARTTLS）请在 application-local.yml 覆盖：
#     spring:
#       mail:
#         host: smtp.spdb.com
#         port: 25
#         properties:
#           mail.smtp.ssl.enable: false
#           mail.smtp.starttls.enable: true
#   或用 ENV 覆盖对应属性。
# ============================================================