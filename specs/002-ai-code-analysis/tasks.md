# Tasks: 基于交易链路图的 AI 代码解读与技术检查

**Branch**: `002-ai-code-analysis`  
**Input**: [design.md](design.md) · [data-model.md](data-model.md) · [contracts/api-spec.md](contracts/api-spec.md)  
**Date**: 2026-04-01

---

## Phase 1: Setup（基础准备）

**Purpose**: 建立 AI 模块目录、配置类与客户端接口

- [ ] T001 在 `src/main/java/com/axonlink/` 下新增 `ai/` 包结构
  - `controller/`
  - `service/`
  - `service/impl/`
  - `client/`
  - `dto/`
  - `prompt/`

- [ ] T002 新增 `src/main/java/com/axonlink/config/AiAnalysisConfig.java`
  - `@ConfigurationProperties(prefix = "ai.analysis")`
  - 字段：
    - `enabled`
    - `provider`
    - `baseUrl`
    - `apiKey`
    - `model`
    - `connectTimeoutSeconds`
    - `timeoutSeconds`
    - `maxContextChars`
    - `maxMethods`
    - `maxTables`
    - `maxSnippets`

- [ ] T003 更新 `src/main/resources/application.yml`
  - 增加 `ai.analysis` 配置节

- [ ] T004 更新 `src/main/resources/application-local.yml.example`
  - 增加内网 GLM5 示例配置

**Checkpoint**: 配置可注入，项目编译通过

---

## Phase 2: LLM 客户端（阻塞 AI 调用）

**Purpose**: 建立内网 GLM5 调用基础设施

- [ ] T005 新增 `src/main/java/com/axonlink/ai/client/LlmClient.java`
  - 定义统一方法：`LlmResult chat(LlmRequest request)`

- [ ] T006 新增 `src/main/java/com/axonlink/ai/dto/LlmRequest.java`
- [ ] T007 新增 `src/main/java/com/axonlink/ai/dto/LlmResult.java`

- [ ] T008 新增 `src/main/java/com/axonlink/ai/client/Glm5Client.java`
  - 使用 `WebClient`
  - 支持：
    - base-url
    - api-key
    - model
    - timeout
  - 若 GLM5 为 OpenAI 兼容协议，先按 `/v1/chat/completions` 实现

- [ ] T009 增加基础异常处理
  - 超时转换为业务异常
  - 连接失败转换为 `503`
  - 响应为空转换为失败结果

**Checkpoint**: 能通过一个简单 prompt 调通内网 GLM5

---

## Phase 3: 数据对象与上下文构建

**Purpose**: 建立 AI 分析的核心上下文模型

- [ ] T010 新增 `AnalysisRequest`
- [ ] T011 新增 `AnalysisResponse`
- [ ] T012 新增 `ContextBundle`
- [ ] T013 新增 `AnalysisTargetMeta`
- [ ] T014 新增 `LogicalNodeRef`
- [ ] T015 新增 `CodeEvidence`
- [ ] T016 新增 `ContextMeta`
- [ ] T017 新增 `PromptHint`

- [ ] T018 新增 `AnalysisContextService`
  - 输入：`txId / path / node`
  - 输出：`ContextBundle`

- [ ] T019 在 `AnalysisContextService` 中复用 `FlowtranService.getChain(txId)`
  - 获取交易的：
    - orchestration
    - service
    - component
    - data
    - relations

- [ ] T020 新增实现方法提取逻辑
  - 通过 Neo4j 查询：
    - `ServiceOperation -> IMPLEMENTS_BY -> Method`
  - 收集关键方法签名

- [ ] T021 新增代码片段抽取逻辑
  - 复用 `ProjectIndexer`
  - 按 `classFqn + methodName` 抽取方法片段
  - 每个片段限制长度

**Checkpoint**: 给定 `txId` 能组装出完整 `ContextBundle`

---

## Phase 4: 技术规则引擎

**Purpose**: 先由程序做确定性技术检查

- [ ] T022 新增 `TechnicalFinding` DTO
- [ ] T023 新增 `RuleCheckContext` DTO
- [ ] T024 新增 `RuleCheckResult` DTO

- [ ] T025 新增 `RuleEngineService`
  - 输入：`RuleCheckContext`
  - 输出：`RuleCheckResult`

- [ ] T026 实现规则：调用链过深
  - 超过阈值标记 `CALL_DEPTH`

- [ ] T027 实现规则：调用环
  - 图中存在 `CALLS / SELF_CALLS` 闭环

- [ ] T028 实现规则：单方法 fan-out 过大

- [ ] T029 实现规则：单交易表数量过多

- [ ] T030 实现规则：明显递归

- [ ] T031 实现规则：可疑无限循环
  - 关键字级别初筛

- [ ] T032 实现规则：异常处理不足
  - `catch(Exception)` / 空 catch / 无日志

- [ ] T033 实现规则：可疑资源未关闭

**Checkpoint**: 给定交易上下文，能返回一组确定性/疑似问题

---

## Phase 5: Prompt 组装与分析服务

**Purpose**: 将上下文和规则结果转换为 GLM5 可消费输入

- [ ] T034 新增 `BusinessPromptBuilder`
  - 输入：`ContextBundle`
  - 输出：业务解读 prompt

- [ ] T035 新增 `TechnicalPromptBuilder`
  - 输入：`ContextBundle + RuleCheckResult`
  - 输出：技术检查 prompt

- [ ] T036 新增 `BusinessAnalysis` DTO
- [ ] T037 新增 `TechnicalAnalysis` DTO
- [ ] T038 新增 `BusinessStepExplain` DTO
- [ ] T039 新增 `CrossDomainNote` DTO
- [ ] T040 新增 `DataImpactExplain` DTO

- [ ] T041 新增 `BusinessExplainService`
  - 调 `LlmClient`
  - 解析 JSON 结果

- [ ] T042 新增 `TechnicalCheckService`
  - 调 `LlmClient`
  - 解析 JSON 结果

- [ ] T043 新增 `AnalysisOrchestrator`
  - 编排：
    - context
    - rules
    - business prompt
    - technical prompt
    - result merge

**Checkpoint**: 能对单个交易输出结构化业务解读与技术检查结果

---

## Phase 6: AI 接口层

**Purpose**: 提供前端可调用的 REST 接口

- [ ] T044 新增 `AnalysisController`
  - `POST /api/ai/analysis/transactions/{txId}`
  - `POST /api/ai/analysis/paths`
  - `POST /api/ai/analysis/nodes`
  - `GET  /api/ai/analysis/{analysisId}`
  - `GET  /api/ai/config`

- [ ] T045 增加参数校验
  - `mode`
  - `txId`
  - `selectedTrail`
  - `nodeCode`

- [ ] T046 增加异常处理
  - 交易不存在
  - 模型不可用
  - prompt 超限

**Checkpoint**: 接口可返回完整结构化响应

---

## Phase 7: 前端接入

**Purpose**: 在交易详情页展示 AI 解读结果

- [ ] T047 更新 `frontend/src/api/index.js`
  - 新增：
    - `analyzeTransactionByAi`
    - `analyzePathByAi`
    - `analyzeNodeByAi`
    - `getAiConfig`

- [ ] T048 在交易详情页增加 `AI 分析` 入口
  - 建议位置：交易详情顶部操作区

- [ ] T049 新增 `AiAnalysisPanel.vue`
  - 分区：
    - 业务解读
    - 技术检查
    - 证据

- [ ] T050 支持从当前交易触发分析

- [ ] T051 支持从当前选中路径触发分析

- [ ] T052 支持点击证据跳转到代码片段
  - 复用当前 Monaco 代码查看器

**Checkpoint**: 页面可查看 AI 分析结果，并可联动代码片段

---

## Phase 8: 缓存与稳定性增强

**Purpose**: 控制模型调用成本并提升响应稳定性

- [ ] T053 增加分析结果缓存
  - 内存缓存即可

- [ ] T054 增加缓存键版本控制
  - `promptVersion`
  - `graphVersion`

- [ ] T055 增加上下文裁剪策略
  - 最大方法数
  - 最大片段数
  - 最大表数

- [ ] T056 增加脱敏逻辑
  - 敏感配置
  - 密钥
  - URL

- [ ] T057 增加模型调用审计日志

**Checkpoint**: AI 分析可稳定重复调用，具备基本性能与安全控制

---

## Phase 9: 验证与收尾

**Purpose**: 验证业务效果与技术效果

- [ ] T058 交易级业务解读验证
  - 选择一笔典型交易，验证业务解读是否与实际业务一致

- [ ] T059 技术检查规则验证
  - 人工构造若干样例，验证规则是否能正确命中

- [ ] T060 GLM5 接入验证
  - 模型异常、超时、空响应场景验证

- [ ] T061 前端交互验证
  - 分析结果展示
  - 证据点击跳转
  - 路径级分析

- [ ] T062 输出质量复核
  - 确认模型不会把疑似问题说成确定性问题

---

## 建议实施顺序

### 第一迭代（最小可行）

- T001 ~ T021
- T022 ~ T029
- T034 ~ T045

目标：
- 跑通“交易级业务解读 + 技术检查”

### 第二迭代（体验完善）

- T047 ~ T057

目标：
- 前端可视化展示
- 缓存、裁剪、审计到位

### 第三迭代（效果提升）

- T058 ~ T062

目标：
- 质量优化与稳定性验证
