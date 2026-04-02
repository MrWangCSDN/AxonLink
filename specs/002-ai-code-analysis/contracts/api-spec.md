# API Contract: AI 交易代码解读与技术检查

**Base Path**: `/api/ai`  
**Response Wrapper**: `R<T>` (`{ code, message, data }`)  
**Date**: 2026-04-01

---

## 1. 设计目标

本接口用于支撑基于交易链路图的 AI 分析能力，输出两类结果：

- 业务解读
- 技术检查

第一阶段提供三类分析入口：

- 交易级分析
- 路径级分析
- 节点级分析

---

## 2. POST /api/ai/analysis/transactions/{txId}

对整笔交易做 AI 分析。

**Path Params**:

- `txId`: 交易编号，如 `TD001`

**Request Body**:

```json
{
  "mode": ["business", "technical"],
  "maxMethods": 12,
  "maxTables": 20,
  "maxSnippets": 12,
  "includeCodeEvidence": true,
  "sync": true
}
```

**字段说明**:

- `mode`
  - 可选值：`business`、`technical`
  - 可同时传两个
- `maxMethods`
  - 允许进入上下文的最大方法数
- `maxTables`
  - 允许进入上下文的最大表数
- `maxSnippets`
  - 允许进入 Prompt 的最大代码片段数
- `includeCodeEvidence`
  - 是否返回证据片段
- `sync`
  - 是否同步等待 AI 结果

**Response** `R<AiAnalysisResponse>`:

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "analysisId": "tx:TD001:20260401103015",
    "targetType": "transaction",
    "targetId": "TD001",
    "status": "SUCCESS",
    "business": {
      "summary": "该交易用于对公贷款发放前进行资料预校验。",
      "businessGoal": "校验贷款发放前的账户、内部户、还款信息是否齐备。",
      "mainFlow": [
        {
          "step": 1,
          "nodeCode": "LnOpenAcctPrcApsSvtp.LnGetRlvcAcctInfoPrePrcAps",
          "nodeName": "获取放款和贷款还款账号对应的模块信息",
          "meaning": "读取放款前需要用到的账户模块信息"
        }
      ],
      "crossDomainNotes": [
        {
          "fromDomain": "loan",
          "toDomain": "deposit",
          "reason": "贷款交易需要读取存款账户信息"
        }
      ]
    },
    "technical": {
      "summary": "未发现确定性严重问题，但调用链偏深。",
      "findings": [
        {
          "level": "MEDIUM",
          "type": "CALL_DEPTH",
          "title": "调用链过深",
          "description": "该交易从入口服务到数据访问存在 8 层调用。",
          "ruleSource": "deterministic",
          "evidence": [
            "IoTaIntrlAcctPbsImpl.qryTaIntrlQryAcctInfoPbs",
            "TaIntrlQryAcctPbcbImpl.qryTaIntrlQryAcctInfoPbcb"
          ]
        }
      ],
      "manualReviewHints": [
        "存在静态转换工具链，建议人工复核对象生命周期。"
      ]
    },
    "contextMeta": {
      "serviceCount": 6,
      "componentCount": 5,
      "tableCount": 3,
      "snippetCount": 8,
      "ruleFindingCount": 4,
      "llmProvider": "glm5",
      "llmModel": "glm-5"
    },
    "evidence": [
      {
        "classFqn": "com.spdb.ccbs.comm.pbs.serviceimpl.tiac.IoTaIntrlAcctPbsImpl",
        "methodName": "qryTaIntrlQryAcctInfoPbs",
        "signature": "com.spdb...IoTaIntrlAcctPbsImpl#qryTaIntrlQryAcctInfoPbs(...)",
        "sourceFile": "/home/facility/code/ccbs-comm-impl/...",
        "snippet": "public Options<IoTaIntrlAcctPbsType...."
      }
    ]
  }
}
```

---

## 3. POST /api/ai/analysis/paths

对交易中的单条已选路径做 AI 分析。

**Request Body**:

```json
{
  "txId": "TD001",
  "selectedTrail": [
    "LnOpenAcctPrcApsSvtp.LnGetRlvcAcctInfoPrePrcAps",
    "TaIntrlQryAcctPbcbSvtp.TaIntrlQryAcctInfoPrcPbcb"
  ],
  "mode": ["business", "technical"],
  "maxMethods": 10,
  "maxTables": 10,
  "includeCodeEvidence": true
}
```

**Response** `R<AiAnalysisResponse>`:

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "analysisId": "path:TD001:abc123",
    "targetType": "path",
    "targetId": "TD001",
    "selectedTrail": [
      "LnOpenAcctPrcApsSvtp.LnGetRlvcAcctInfoPrePrcAps",
      "TaIntrlQryAcctPbcbSvtp.TaIntrlQryAcctInfoPrcPbcb"
    ],
    "status": "SUCCESS",
    "business": {},
    "technical": {},
    "contextMeta": {}
  }
}
```

---

## 4. POST /api/ai/analysis/nodes

对单个服务/构件节点做 AI 分析。

**Request Body**:

```json
{
  "nodeCode": "IoTaIntrlAcctPbsSvtp.TaIntrlQryAcctInfoPbs",
  "nodePrefix": "pbs",
  "txId": "TD001",
  "mode": ["business", "technical"],
  "includeCodeEvidence": true
}
```

**Response** `R<AiAnalysisResponse>`:

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "analysisId": "node:IoTaIntrlAcctPbsSvtp.TaIntrlQryAcctInfoPbs",
    "targetType": "node",
    "targetId": "IoTaIntrlAcctPbsSvtp.TaIntrlQryAcctInfoPbs",
    "status": "SUCCESS",
    "business": {
      "summary": "该服务用于内部户账户信息查询。"
    },
    "technical": {
      "summary": "该节点主要风险集中在下游构件依赖较多。"
    }
  }
}
```

---

## 5. GET /api/ai/analysis/{analysisId}

获取某次分析结果。

**Path Params**:

- `analysisId`: 分析任务唯一 ID

**Response** `R<AiAnalysisResponse>`:

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "analysisId": "tx:TD001:20260401103015",
    "status": "SUCCESS",
    "business": {},
    "technical": {}
  }
}
```

---

## 6. GET /api/ai/config

查询当前 AI 能力配置。

**Response** `R<Map<String, Object>>`:

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "enabled": true,
    "provider": "glm5",
    "model": "glm-5",
    "baseUrl": "http://glm5-internal.example.com",
    "syncEnabled": true,
    "maxMethods": 12,
    "maxTables": 20,
    "maxSnippets": 12
  }
}
```

---

## 7. 错误码约定

| code | 说明 |
|------|------|
| 200 | 成功 |
| 400 | 参数错误 |
| 404 | 交易 / 路径 / 节点不存在 |
| 422 | 上下文构建失败 |
| 503 | GLM5 服务不可用 |
| 504 | GLM5 调用超时 |
| 500 | 服务端异常 |

---

## 8. 返回对象说明

### AiAnalysisResponse

| 字段 | 类型 | 说明 |
|------|------|------|
| `analysisId` | String | 分析任务唯一 ID |
| `targetType` | String | `transaction` / `path` / `node` |
| `targetId` | String | 目标标识 |
| `status` | String | `SUCCESS` / `FAILED` / `TIMEOUT` |
| `business` | Object | 业务解读结果 |
| `technical` | Object | 技术检查结果 |
| `contextMeta` | Object | 上下文统计 |
| `evidence` | Array | 代码和图谱证据 |

### TechnicalFinding

| 字段 | 类型 | 说明 |
|------|------|------|
| `level` | String | `HIGH` / `MEDIUM` / `LOW` / `INFO` |
| `type` | String | 规则编码 |
| `title` | String | 简短标题 |
| `description` | String | 风险说明 |
| `ruleSource` | String | `deterministic` / `suspected` |
| `evidence` | Array | 命中证据 |
