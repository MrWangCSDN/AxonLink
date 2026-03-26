# API Contract: FlowtranController

**Base Path**: `/api/flowtran`  
**Response Wrapper**: `R<T>` (`{ code, message, data }`)  
**Date**: 2026-03-26

---

## GET /api/flowtran/domains

返回所有领域列表（来自 flowtran 表 GROUP BY domain_key）。

**Response** `R<List<FlowtranDomain>>`:
```json
{
  "code": 200,
  "message": "success",
  "data": [
    {
      "domainKey": "dept",
      "domainName": "存款领域",
      "txCount": 21,
      "icon": "bank"
    },
    {
      "domainKey": "loan",
      "domainName": "贷款领域",
      "txCount": 0,
      "icon": "credit-card"
    }
  ]
}
```

---

## GET /api/flowtran/domains/{domainKey}/transactions

分页查询某领域下的交易列表。

**Path Params**: `domainKey` = `dept` / `loan` / `sett` 等  
**Query Params**:
- `page` (int, default 1)
- `size` (int, default 20)
- `keyword` (String, optional) — 模糊匹配 `id` 或 `longname`

**Response** `R<Map<String, Object>>`:
```json
{
  "code": 200,
  "data": {
    "list": [
      {
        "id": "TC0033",
        "longname": "对公活期开户",
        "domainKey": "dept",
        "txnMode": "A",
        "fromJar": "ccbs-dept-impl.master:dept-pbf/..."
      }
    ],
    "total": 21,
    "page": 1,
    "size": 20
  }
}
```

---

## GET /api/flowtran/transactions/{txId}/chain

获取某交易的完整链路（flow_step + ServiceNodeCache 富化）。

**Path Params**: `txId` = flowtran.id，如 `TC0033`

**Response** `R<FlowtranChain>`:
```json
{
  "code": 200,
  "data": {
    "txId": "TC0033",
    "txLongname": "对公活期开户",
    "domainKey": "dept",
    "steps": [
      {
        "step": 1,
        "nodeType": "service",
        "nodeName": "IoDpOpenDeptAcctApsSvtp.openDeptAcct",
        "nodeLongname": "对公活期开户服务",
        "nodeKind": "pbs",
        "domainKey": "dept",
        "crossDomain": false,
        "inputFieldTypes": "IoDpOpenDeptAcctApsType,IoDpAcctBaseInfoType",
        "inputFieldMultis": "false,false",
        "outputFieldTypes": "IoDpOpenDeptAcctApsOutType",
        "outputFieldMultis": "false"
      },
      {
        "step": 2,
        "nodeType": "method",
        "nodeName": "validateAccountInfo",
        "nodeLongname": "账户信息校验",
        "nodeKind": null,
        "domainKey": "dept",
        "crossDomain": false
      }
    ],
    "meta": {
      "txnMode": "A",
      "fromJar": "ccbs-dept-impl.master:dept-pbf/..."
    }
  }
}
```

---

## POST /api/flowtran/cache/refresh

热重载 ServiceNodeCache（不重启服务）。

**Request Body**: 无  
**Response** `R<Map<String, Object>>`:
```json
{
  "code": 200,
  "data": {
    "serviceCount": 1240,
    "componentCount": 3560,
    "loadedAt": "2026-03-26T14:00:00",
    "elapsedMs": 186
  }
}
```

---

## GET /api/flowtran/cache/stats

查询 ServiceNodeCache 当前状态。

**Response** `R<Map<String, Object>>`:
```json
{
  "code": 200,
  "data": {
    "loaded": true,
    "serviceCount": 1240,
    "componentCount": 3560,
    "totalCount": 4800,
    "loadedAt": "2026-03-26T13:45:00",
    "datasource": "local"
  }
}
```

---

## 错误码约定

| code | 说明 |
|------|------|
| 200 | 成功 |
| 404 | 交易/领域不存在 |
| 503 | flowtran 数据源不可用（连接失败时） |
| 500 | 服务端异常 |
