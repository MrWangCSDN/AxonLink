# 全量链路导出 DAO 方法清单实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal** 全量链路下载新增第五个 DAO方法清单 Sheet，每个方法单独一行。

**Architecture** 复用 `getChain(txId).chain.data.dao`，按同一次链路的 `tableCode` 关联数据库表元数据，以导出服务生成流式工作簿。

**Tech Stack** Java 17、Spring MVC、Apache POI、JUnit 5。

**Spec** `/Users/java/obsidian/01 Engineering/axon-link-server/交易链路按领域全量导出-系统设计.md` 及配套数据模型、API 接口文档。

## Global Constraints

- 用户已明确选择新增 DAO方法清单 Sheet，每方法一行。
- 新 Sheet 列为交易码、交易名称、表英文名、表中文名、表所属领域编码、表所属领域名称、来源工程、DAO类名、DAO方法名。
- 方法名优先 `methodName`，回退 `name`，文本去首尾空白。
- 同交易的重复 DAO 节点去重，跨交易保留，同名不同类保留。
- 表领域和工程来自同一次链路的表节点，DAO 类名优先 DAO 节点。
- 缺少表元数据的有效方法仍输出，未知字段留空。
- DAO 行按交易、表、DAO 类、方法、节点编码升序排列。

## Review Focus

- 同表多个方法各自保留，方法名和表及 DAO 类对应准确。
- 同方法跨交易保留，导出行不串交易。
- 无 DAO 层的历史链路生成仅有表头的新 Sheet。
- 部分元数据缺失时方法仍可导出，字段回退采用有据来源。
- 入口鉴权、分页读取、局部失败和实际 Excel 结构通过回归验证。

### Task 1 导出实现与回归

- [x] 读取链路生产节点字段及现有导出契约，更新 Obsidian 设计。
- [x] 先运行第五 Sheet、多方法、跨交易和历史数据用例，确认缺少新 Sheet 的失败。
- [x] 提取 DAO 行并关联同交易表元数据，生成九列 Sheet 和筛选。
- [x] 运行服务、控制器及全量后端测试，逐项核对既有失败基线。

### Task 2 入口一致性与交付

- [x] 更新控制器及前端下载 API 的 Sheet 说明。
- [x] 独立检查字段来源、去重排序和旧数据兼容。
- [x] 执行前端测试与生产构建，将验证代码同步原项目。
