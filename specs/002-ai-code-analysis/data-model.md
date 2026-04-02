# Data Model: AI 交易代码解读与技术检查

**Feature**: 002-ai-code-analysis  
**Phase**: Phase 1 Output  
**Date**: 2026-04-01

---

## 一、核心数据流

```text
Neo4j 图谱
    ├── Transaction
    ├── FlowServiceStep / FlowMethodStep
    ├── ServiceType / ServiceOperation
    ├── IMPLEMENTS_BY
    ├── CALLS / SYS_UTIL_CALLS / SELF_CALLS
    └── DAO_CALLS

ProjectIndexer / SourceController
    └── classFqn + methodName → Java 文件 + 方法片段

RuleEngine
    └── 确定性技术检查结果

AnalysisContextService
    └── ContextBundle

GLM5
    └── BusinessAnalysis + TechnicalAnalysis
```

---

## 二、核心对象设计

## 2.1 AnalysisRequest

```java
public class AnalysisRequest {
    List<String> mode;          // ["business", "technical"]
    List<String> selectedTrail; // 路径分析时使用
    Integer maxMethods;
    Integer maxTables;
    Integer maxSnippets;
    Boolean includeCodeEvidence;
    Boolean sync;
}
```

---

## 2.2 AnalysisResponse

```java
public class AnalysisResponse {
    String analysisId;
    String targetType;      // transaction / path / node
    String targetId;
    String status;          // SUCCESS / FAILED / TIMEOUT
    List<String> selectedTrail;

    BusinessAnalysis business;
    TechnicalAnalysis technical;
    ContextMeta contextMeta;
    List<CodeEvidence> evidence;
}
```

---

## 2.3 ContextBundle

上下文包是整个 AI 分析的核心输入对象。

```java
public class ContextBundle {
    AnalysisTargetMeta meta;
    Map<String, Object> chain;
    List<LogicalNodeRef> logicalNodes;
    List<CodeEvidence> codeEvidence;
    List<TechnicalFinding> deterministicFindings;
    PromptHint promptHint;
}
```

### 字段说明

| 字段 | 说明 |
|------|------|
| `meta` | 交易/路径/节点元信息 |
| `chain` | 来自 `FlowtranService.getChain(...)` 的链路结构 |
| `logicalNodes` | 本次分析纳入的节点列表 |
| `codeEvidence` | 送入模型的关键代码片段 |
| `deterministicFindings` | 规则引擎先跑出的技术检查结果 |
| `promptHint` | Prompt 组装辅助信息 |

---

## 2.4 AnalysisTargetMeta

```java
public class AnalysisTargetMeta {
    String targetType;   // transaction / path / node
    String txId;
    String targetId;
    String txName;
    String domainKey;
    String domainName;
}
```

---

## 2.5 LogicalNodeRef

表示本次 AI 分析中实际纳入的服务/构件/表/方法节点。

```java
public class LogicalNodeRef {
    String code;
    String name;
    String prefix;       // pbs / pcs / pbcb / pbcp / pbcc / pbct / method / table
    String domainKey;
    String domainName;
    String nodeType;     // service / component / data / method
    List<String> methodSignatures;
}
```

---

## 2.6 CodeEvidence

```java
public class CodeEvidence {
    String classFqn;
    String methodName;
    String signature;
    String sourceFile;
    String snippet;
    Integer startLine;
    Integer endLine;
    String language;     // java / xml
    String evidenceType; // entry / service_impl / sysutil / dao / risk
}
```

说明：

- `snippet` 只保留关键片段，不保存整文件
- `evidenceType` 用于标识该片段被纳入上下文的原因

---

## 2.7 BusinessAnalysis

```java
public class BusinessAnalysis {
    String summary;
    String businessGoal;
    List<BusinessStepExplain> mainFlow;
    List<CrossDomainNote> crossDomainNotes;
    List<DataImpactExplain> dataImpact;
}
```

### BusinessStepExplain

```java
public class BusinessStepExplain {
    Integer step;
    String nodeCode;
    String nodeName;
    String meaning;
    List<String> evidence;
}
```

### CrossDomainNote

```java
public class CrossDomainNote {
    String fromDomain;
    String toDomain;
    String reason;
}
```

### DataImpactExplain

```java
public class DataImpactExplain {
    String table;
    String meaning;
}
```

---

## 2.8 TechnicalAnalysis

```java
public class TechnicalAnalysis {
    String summary;
    List<TechnicalFinding> findings;
    List<String> manualReviewHints;
}
```

### TechnicalFinding

```java
public class TechnicalFinding {
    String level;        // HIGH / MEDIUM / LOW / INFO
    String type;         // CALL_DEPTH / CALL_CYCLE / POSSIBLE_RESOURCE_LEAK ...
    String title;
    String description;
    String ruleSource;   // deterministic / suspected
    List<String> evidence;
}
```

---

## 2.9 ContextMeta

```java
public class ContextMeta {
    Integer serviceCount;
    Integer componentCount;
    Integer tableCount;
    Integer snippetCount;
    Integer ruleFindingCount;
    String llmProvider;
    String llmModel;
}
```

---

## 2.10 PromptHint

```java
public class PromptHint {
    List<String> analysisTypes;
    Integer maxContextChars;
    Integer maxMethods;
    Integer maxTables;
    Integer maxSnippets;
}
```

---

## 三、规则引擎数据对象

## 3.1 RuleCheckContext

```java
public class RuleCheckContext {
    String txId;
    Map<String, Object> chain;
    List<LogicalNodeRef> logicalNodes;
    List<CodeEvidence> codeEvidence;
}
```

## 3.2 RuleCheckResult

```java
public class RuleCheckResult {
    List<TechnicalFinding> findings;
}
```

---

## 四、GLM5 客户端数据对象

## 4.1 LlmRequest

```java
public class LlmRequest {
    String provider;
    String model;
    String systemPrompt;
    String userPrompt;
    Double temperature;
    Integer maxTokens;
    Map<String, Object> metadata;
}
```

## 4.2 LlmResult

```java
public class LlmResult {
    String provider;
    String model;
    String rawText;
    String jsonText;
    Long elapsedMs;
    Boolean success;
    String errorMessage;
}
```

---

## 五、缓存键设计

第一阶段建议内存缓存，键建议如下：

```text
analysis:{targetType}:{targetId}:{pathHash}:{modeHash}:{promptVersion}:{graphVersion}
```

### 各段说明

| 段 | 说明 |
|----|------|
| `targetType` | `transaction` / `path` / `node` |
| `targetId` | 交易号或节点编码 |
| `pathHash` | 选中路径哈希 |
| `modeHash` | 分析模式哈希 |
| `promptVersion` | Prompt 版本 |
| `graphVersion` | 图谱版本或构建时间戳 |

---

## 六、配置模型

## 6.1 AI 总配置

```java
public class AiAnalysisConfig {
    boolean enabled;
    String provider;         // glm5
    String baseUrl;
    String apiKey;
    String model;
    int connectTimeoutSeconds;
    int timeoutSeconds;
    int maxContextChars;
    int maxMethods;
    int maxTables;
    int maxSnippets;
}
```

## 6.2 application.yml 草案

```yaml
ai:
  analysis:
    enabled: true
    provider: glm5
    base-url: http://glm5-internal.example.com
    api-key: ${GLM5_API_KEY:}
    model: glm-5
    connect-timeout-seconds: 10
    timeout-seconds: 120
    max-context-chars: 80000
    max-methods: 12
    max-tables: 20
    max-snippets: 12
```

---

## 七、与现有对象的关系

本设计不替换现有 `FlowtranService` 的输出，而是在其之上增量构建：

- `FlowtranService.getChain(txId)` → `ContextBundle.chain`
- `ServiceOperation / IMPLEMENTS_BY / Method` → `CodeEvidence`
- `ProjectIndexer` → `sourceFile + snippet`
- `Neo4jGraphBuilder` 图谱关系 → 技术规则输入

因此该数据模型与当前系统是兼容叠加关系。
