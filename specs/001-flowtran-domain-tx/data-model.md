# Data Model: flowtran 数据驱动交易链路

**Feature**: 001-flowtran-domain-tx  
**Phase**: Phase 1 Output  
**Date**: 2026-03-26

---

## 一、核心数据流

```
benchmarkdb / mall_admin
    ├── flowtran          → FlowtranDomain / FlowtranTransaction（领域 + 交易列表）
    ├── flow_step         → FlowStepNode（交易链路步骤）
    ├── service           ┐
    ├── service_detail    ├─→ ServiceNodeCache（启动时全量加载到内存 Map）
    ├── component         │
    └── component_detail  ┘

ServiceNodeCache（内存）
    └── Map<String, NodeCacheEntry>
            key:   "{service_type_id}.{service_id}"  ← service节点
                   "{component_id}.{service_id}"     ← component节点
            value: NodeCacheEntry { 名称 + 接口字段 + 节点分类 + 领域 }
```

---

## 二、Java 实体 / VO 设计

### 2.1 FlowtranDomain（领域 VO）

```java
// 响应 GET /api/flowtran/domains
public class FlowtranDomain {
    String domainKey;     // "dept" / "loan" / ...
    String domainName;    // "存款领域" / "贷款领域" ...（静态映射）
    long   txCount;       // SELECT COUNT(*) FROM flowtran WHERE domain_key=?
    String icon;          // 静态配置
}
```

**领域静态映射**（DomainKeyResolver）：

| domainKey | 中文名 | AxonLink domain_key |
|-----------|--------|-------------------|
| `dept`    | 存款领域（对公） | `deposit` |
| `loan`    | 贷款领域 | `loan` |
| `sett`    | 结算领域 | `settlement` |
| `comm`    | 公共领域 | `public` |
| `unvr`    | 通联领域 | `unvr` |
| `aggr`    | 综合领域 | `aggr` |
| 其他 | 公共领域 | `public` |

### 2.2 FlowtranTransaction（交易 VO）

```java
// 响应 GET /api/flowtran/domains/{domainKey}/transactions
public class FlowtranTransaction {
    String id;          // flowtran.id，如 TC0076
    String longname;    // flowtran.longname，如「对公活期开户」
    String domainKey;   // 推断的领域标识
    String txnMode;     // flowtran.txn_mode（R/A）
    String fromJar;     // flowtran.from_jar
}
```

### 2.3 NodeCacheEntry（服务节点缓存值）

```java
// 存储在 ServiceNodeCache 的 value
public class NodeCacheEntry {
    // 来自 service_detail / component_detail
    String serviceName;                 // service.name（英文方法名）
    String serviceLongname;             // service.longname（中文名）
    String interfaceInputFieldTypes;    // 聚合：多行 field.type 逗号连接
    String interfaceInputFieldMultis;   // 聚合：多行 field.multi 逗号连接
    String interfaceOutputFieldTypes;
    String interfaceOutputFieldMultis;

    // 来自 service / component 主表
    String nodeKind;     // service_type（pcs/pbs）或 component_type（pbcb/pbcp/pbcc/pbct）
    String packagePath;  // serviceType.package，如 com.spdb.ccbs.dept.aps.servicetype.*
    String domainKey;    // 从 packagePath 第4段推断
}
```

### 2.4 FlowChainNode（链路节点 VO）

```java
// 流程编排节点，聚合 flow_step + NodeCacheEntry
public class FlowChainNode {
    int    step;           // flow_step.step，升序排列
    String nodeType;       // "service" 或 "method"
    String nodeName;       // flow_step.node_name（英文 ID，也是 cache key）
    String nodeLongname;   // flow_step.node_longname（中文名）
    String nodeKind;       // 从缓存取：pbs/pcs/pbcb/pbcc/pbct/pbcp（未命中则 null）
    String domainKey;      // 从缓存 packagePath 推断（method 类型 = 本交易领域）
    boolean crossDomain;   // domainKey ≠ 交易所属领域

    // 以下仅 service 类型且缓存命中时才有值
    String inputFieldTypes;
    String inputFieldMultis;
    String outputFieldTypes;
    String outputFieldMultis;
}
```

### 2.5 FlowtranChain（完整链路响应）

```java
// 响应 GET /api/flowtran/transactions/{txId}/chain
public class FlowtranChain {
    String              txId;            // flowtran.id
    String              txLongname;      // flowtran.longname
    String              domainKey;       // 领域
    List<FlowChainNode> steps;           // 按 step 升序的节点列表
    Map<String, Object> meta;            // txn_mode, from_jar 等附加信息
}
```

---

## 三、ServiceNodeCache 内存结构

```
ServiceNodeCache
├── Map<String, NodeCacheEntry> nodeMap    // 全量 key-value
│       key 格式：
│           service 节点："{service_type_id}.{service_id}"
│           component 节点："{component_id}.{service_id}"
│       value：NodeCacheEntry（同一 service_id 的多行 field 聚合为单条）
│
├── volatile long    loadedAt        // 加载时间戳
├── volatile int     serviceCount    // service 节点条数
├── volatile int     componentCount  // component 节点条数
└── volatile boolean loaded          // 是否已完成首次加载
```

**加载 SQL（两条，启动时并行执行）**：

```sql
-- ① service 节点
SELECT
  sd.service_type_id, sd.service_id,
  sd.service_name, sd.service_longname,
  sd.interface_input_field_type,  sd.interface_input_field_multi,
  sd.interface_output_field_type, sd.interface_output_field_multi,
  s.service_type   AS node_kind,
  s.package_path
FROM service_detail sd
JOIN service s ON s.id = sd.service_type_id;

-- ② component 节点
SELECT
  cd.component_id, cd.service_id,
  cd.service_name, cd.service_longname,
  cd.interface_input_field_type,  cd.interface_input_field_multi,
  cd.interface_output_field_type, cd.interface_output_field_multi,
  c.component_type AS node_kind,
  c.package_path
FROM component_detail cd
JOIN component c ON c.id = cd.component_id;
```

**聚合逻辑**（key 相同的多行 field 合并为单条 NodeCacheEntry）：

```
按 {type_id}.{service_id} 分组：
  serviceName      = 取第一行（同 service_id 的 name 相同）
  serviceLongname  = 取第一行
  inputFieldTypes  = String.join(",", 所有 interface_input_field_type 非空值)
  inputFieldMultis = String.join(",", 所有 interface_input_field_multi 非空值)
  outputFieldTypes = String.join(",", 所有 interface_output_field_type 非空值)
  outputFieldMultis= String.join(",", 所有 interface_output_field_multi 非空值)
  nodeKind         = 取任意行（相同）
  packagePath      = 取任意行（相同）
  domainKey        = DomainKeyResolver.resolve(packagePath)
```

---

## 四、数据库表关系（已有，不新增）

```
flowtran ──── (1:N) ──── flow_step
  ↓ domain_key           ↓ node_name（即 cache key）
  ↓ 推断领域              ↓ 命中 ServiceNodeCache
                              ↓
                         service + service_detail（pbs/pcs）
                         component + component_detail（pbcb/pbcp/pbcc/pbct）
```

---

## 五、配置项新增（application.yml）

```yaml
flowtran:
  datasource: ${FLOWTRAN_DATASOURCE:local}   # local / intranet（仅文档标记，实际由 spring.datasource 控制）
  local:
    url:      ${FLOWTRAN_LOCAL_URL:${spring.datasource.url}}
    username: ${FLOWTRAN_LOCAL_USERNAME:${spring.datasource.username}}
    password: ${FLOWTRAN_LOCAL_PASSWORD:${spring.datasource.password}}
  intranet:
    url:      ${FLOWTRAN_INTRANET_URL:jdbc:mysql://21.64.203.16:3306/benchmarkdb?...}
    username: ${FLOWTRAN_INTRANET_USERNAME:benchmark}
    password: ${FLOWTRAN_INTRANET_PASSWORD:benchmark123}
  cache:
    enabled: true   # 是否启动时加载 ServiceNodeCache
```
