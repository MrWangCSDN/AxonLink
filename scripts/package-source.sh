#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
OUTPUT="${1:-axon-link-server-source.zip}"

if [[ "${OUTPUT}" != /* ]]; then
  OUTPUT="${REPO_ROOT}/${OUTPUT}"
fi

if [[ "${OUTPUT}" != *.zip ]]; then
  echo "源码包路径必须以 .zip 结尾: ${OUTPUT}" >&2
  exit 1
fi

SOURCE_PATHS=(
  pom.xml
  src
  scripts
  docs
  specs
  build.sh
  start.sh
  stop.sh
  compile-and-index.sh
  .gitignore
)

mkdir -p "$(dirname "${OUTPUT}")"
rm -f -- "${OUTPUT}"

cd "${REPO_ROOT}"
zip -qr "${OUTPUT}" "${SOURCE_PATHS[@]}" -x '.DS_Store' '*/.DS_Store'
unzip -tq "${OUTPUT}"

REQUIRED_REGISTRY='src/main/java/com/axonlink/ai/daoindex/datasource/TargetDataSourceRegistry.java'
if [[ "$(unzip -Z1 "${OUTPUT}" "${REQUIRED_REGISTRY}")" != "${REQUIRED_REGISTRY}" ]]; then
  echo "源码包缺少必要文件: ${REQUIRED_REGISTRY}" >&2
  exit 1
fi

echo "源码包已生成: ${OUTPUT}"
