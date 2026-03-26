# Feature Specification: 从 flowtran 表驱动业务领域交易数据（双数据源 + 切换开关）

**Feature Branch**: `001-flowtran-domain-tx`  
**Created**: 2026-03-26  
**Updated**: 2026-03-26 v3（新增：服务层流程编排来源 + service/component 四表 Map 缓存）  
**Status**: Draft  

---

## 背景说明

AxonLink 需要同时支持两套运行环境，每套环境有独立的 `flowtran`/`flow_step` 数据库：

| 环境 | 名称 | 数据库地址 | 库名 | 账号 |
|------|------|-----------|------|------|
| **外网本地**（开发/测试） | `local` | `rm-uf65l2bqe8kpu0e219o.mysql.rds.aliyuncs.com:3306` | `mall_admin` | `mall / Liang@201314` |
| **内网**（生产/联调） | `intranet` | `21.64.203.16:3306` | `benchmarkdb` | `benchmark / benchmark123` |

两个环境的 `flowtran`、`flow_step` 表结构完全一致（见下方），
通过一个**配置开关**在启动时决定读取哪套数据，无需重新打包。

---

## 字段映射与领域推断规则（核心）

### flowtran 字段 → AxonLink 交易层展示映射

| flowtran 字段 | AxonLink 用途 | 说明 |
|--------------|--------------|------|
| `id` | 交易码（tx_code） | 直接作为交易编码展示，如 `TC0076`、`TC022` |
| `longname` | 交易名称 | 直接作为中文名称展示，如「对公活期开户」 |
| `package_path` | **领域推断**（核心） | 取第 4 段关键字推断业务领域，见下方规则 |
| `txn_mode` | 事务模式（可选展示） | `R`=只读查询，`A`=写入操作 |
| `from_jar` | 来源工程（辅助信息） | 可用于显示归属子工程 |

### package_path 领域推断规则

格式固定为：`com.spdb.ccbs.{领域}.{层}.{模块}.{功能}`

| package_path 第4段 | 推断领域 | AxonLink domain_key | 示例 package_path |
|-------------------|---------|-------------------|------------------|
| `dept` | 存款领域（对公存款） | `deposit` | `com.spdb.ccbs.dept.pbf.trans.qryMnt` |
| `loan` | 贷款领域 | `loan` | `com.spdb.ccbs.loan.pbf.trans.xxx` |
| `sett` | 结算领域 | `settlement` | `com.spdb.ccbs.sett.pbf.trans.xxx` |
| `comm` | 公共领域 | `public` | `com.spdb.ccbs.comm.pbf.trans.xxx` |
| `unvr` | 通联领域 | `unvr` | `com.spdb.ccbs.unvr.pbf.trans.xxx` |
| 其他/无法识别 | 公共领域（兜底） | `public` | — |

**提取规则**：以 `.` 分割 `package_path`，取 index=3（第4段）即为领域标识。
例：`com.spdb.ccbs.dept.pbf.trans.opnCnclAc`.split('.')[3] = `dept` → `deposit`

---

## 服务层展示：流程编排来源与缓存机制（核心新增）

### 一、flow_step → 服务层节点映射

交易展开后，服务层「流程编排」的数据来源：

| flow_step 字段 | 用途 | 说明 |
|---------------|------|------|
| `flow_id` | 关联 flowtran.id | 过滤条件 |
| `step` | 展示顺序 | **升序排列**，决定服务/构件在链路图中的先后顺序 |
| `node_type` | 节点类型 | `service` = 服务节点（pbs/pcs）；`method` = 方法节点（属于本领域构件） |
| `node_name` | 英文编码/ID | 作为缓存 key 的组成部分（格式见下），也直接展示为节点编码 |
| `node_longname` | 中文名称 | 直接展示为节点名称 |

**节点类型处理规则**：
- `node_type = method`：**一定属于本领域**，直接展示，无需跨域查询
- `node_type = service`：需通过 Map 缓存查询 `service` / `component` 详细信息，推断服务类型和归属领域

---

### 二、四张源数据表结构（来源：benchmarkdb / benchmarkdb local）

#### 2.1 service 主表（.pbs.xml / .pcs.xml → serviceType 标签）

```sql
CREATE TABLE service (
    id           VARCHAR(200) PRIMARY KEY  COMMENT 'serviceType.id，如 IoDpAccLimitApsSvtp',
    longname     VARCHAR(500)              COMMENT 'serviceType.longname 中文名',
    package_path VARCHAR(500)              COMMENT 'serviceType.package，如 com.spdb.ccbs.dept.aps.servicetype.acclimit',
    kind         VARCHAR(200)              COMMENT 'serviceType.kind',
    out_bound    VARCHAR(200)              COMMENT 'serviceType.outBound',
    service_type VARCHAR(20)               COMMENT '服务类型：pcs / pbs',
    from_jar     VARCHAR(1000)             COMMENT '来源文件路径'
)
```

#### 2.2 service_detail 明细表（serviceType/service/interface 下每个 field 一行）

```sql
CREATE TABLE service_detail (
    id                              BIGINT AUTO_INCREMENT PRIMARY KEY,
    service_type_id                 VARCHAR(200)  COMMENT '所属 serviceType.id（FK → service.id）',
    service_id                      VARCHAR(200)  COMMENT 'service.id，如 prcAccLimitRgst',
    service_name                    VARCHAR(200)  COMMENT 'service.name 英文名',
    service_longname                VARCHAR(500)  COMMENT 'service.longname 中文名',
    interface_input_field_id        VARCHAR(200)  COMMENT 'input field.id',
    interface_input_field_longname  VARCHAR(500)  COMMENT 'input field.longname',
    interface_input_field_type      VARCHAR(200)  COMMENT 'input field.type（数据类型引用）',
    interface_input_field_required  VARCHAR(20)   COMMENT 'input field.required',
    interface_input_field_multi     VARCHAR(20)   COMMENT 'input field.multi（是否数组）',
    interface_output_field_id       VARCHAR(200)  COMMENT 'output field.id',
    interface_output_field_longname VARCHAR(500)  COMMENT 'output field.longname',
    interface_output_field_type     VARCHAR(200)  COMMENT 'output field.type',
    interface_output_field_required VARCHAR(20)   COMMENT 'output field.required',
    interface_output_field_multi    VARCHAR(20)   COMMENT 'output field.multi',
    INDEX idx_service_type_id (service_type_id),
    INDEX idx_service_id (service_id)
)
```

#### 2.3 component 主表（.pbcb/.pbcp/.pbcc/.pbct.xml → serviceType 标签）

```sql
CREATE TABLE component (
    id             VARCHAR(200) PRIMARY KEY  COMMENT 'serviceType.id，如 AgPbccSvtp',
    longname       VARCHAR(500)              COMMENT 'serviceType.longname 中文名',
    package_path   VARCHAR(500)              COMMENT 'serviceType.package，如 com.spdb.ccbs.dept.bcs.servicetype.xxx',
    kind           VARCHAR(200)              COMMENT 'serviceType.kind',
    component_type VARCHAR(20)               COMMENT '构件类型：pbcb / pbcp / pbcc / pbct',
    from_jar       VARCHAR(1000)             COMMENT '来源文件路径'
)
```

#### 2.4 component_detail 明细表（与 service_detail 结构对称）

```sql
CREATE TABLE component_detail (
    id                               BIGINT AUTO_INCREMENT PRIMARY KEY,
    component_id                     VARCHAR(200)  COMMENT '所属 serviceType.id（FK → component.id）',
    service_id                       VARCHAR(200)  COMMENT 'service.id（构件内的方法 id）',
    service_name                     VARCHAR(200)  COMMENT 'service.name 英文名',
    service_longname                 VARCHAR(500)  COMMENT 'service.longname 中文名',
    interface_input_field_id         VARCHAR(200),
    interface_input_field_longname   VARCHAR(500),
    interface_input_field_type       VARCHAR(200)  COMMENT 'input field.type（数据类型引用）',
    interface_input_field_required   VARCHAR(20),
    interface_input_field_multi      VARCHAR(20)   COMMENT 'input field.multi（是否数组）',
    interface_output_field_id        VARCHAR(200),
    interface_output_field_longname  VARCHAR(500),
    interface_output_field_type      VARCHAR(200)  COMMENT 'output field.type',
    interface_output_field_required  VARCHAR(20),
    interface_output_field_multi     VARCHAR(20),
    INDEX idx_component_id (component_id),
    INDEX idx_service_id (service_id)
)
```

---

### 三、启动缓存（ServiceNodeCache）设计

系统启动时，从激活数据源一次性全量加载 service + service_detail + component + component_detail
到内存 Map，供交易链路展示时 O(1) 查找，**不在请求链路中发起 DB 查询**。

#### 3.1 缓存 Key 设计

| 来源表 | Key 格式 | 示例 |
|--------|---------|------|
| service + service_detail | `{service_type_id}.{service_id}` | `IoDpAccLimitApsSvtp.prcAccLimitRgst` |
| component + component_detail | `{component_id}.{service_id}` | `AgPbccSvtp.calcInterest` |

`flow_step.node_name` 与缓存 key 一一对应，直接用于 Map 查找。

#### 3.2 缓存 Value 对象（NodeCacheEntry）

```
NodeCacheEntry {
    // 来自明细表
    serviceName               : String   // service.name（英文，如 prcAccLimitRgst）
    serviceLongname           : String   // service.longname（中文，如「限额登记」）
    interfaceInputFieldType   : String   // input field.type 列表（逗号分隔）
    interfaceInputFieldMulti  : String   // input field.multi 列表
    interfaceOutputFieldType  : String   // output field.type 列表
    interfaceOutputFieldMulti : String   // output field.multi 列表

    // 来自主表
    nodeKind      : String   // service_type（pcs/pbs）或 component_type（pbcb/pbcp/pbcc/pbct）
    packagePath   : String   // serviceType.package，用于推断所属领域
    domainKey     : String   // 从 packagePath 推断：com.spdb.ccbs.{domain}.xxx → domain
}
```

**注意**：同一个 `service_type_id.service_id` 对应多行 field 明细，缓存时需**按 service_id 聚合**，
将同一 service 下所有 field 的 type/multi 拼接为列表存储到单个 NodeCacheEntry。

#### 3.3 加载 SQL（两步查询 + 内存 JOIN）

**Step 1**：全量加载明细表（主键驱动，JOIN 主表取 service_type / package_path）

```sql
-- 服务：service_detail JOIN service
SELECT
  sd.service_type_id, sd.service_id,
  sd.service_name, sd.service_longname,
  sd.interface_input_field_type,  sd.interface_input_field_multi,
  sd.interface_output_field_type, sd.interface_output_field_multi,
  s.service_type, s.package_path
FROM service_detail sd
JOIN service s ON s.id = sd.service_type_id;

-- 构件：component_detail JOIN component
SELECT
  cd.component_id, cd.service_id,
  cd.service_name, cd.service_longname,
  cd.interface_input_field_type,  cd.interface_input_field_multi,
  cd.interface_output_field_type, cd.interface_output_field_multi,
  c.component_type, c.package_path
FROM component_detail cd
JOIN component c ON c.id = cd.component_id;
```

**Step 2**：按 `{type_id}.{service_id}` 分组聚合，写入 `Map<String, NodeCacheEntry>`

#### 3.4 domain 推断（与 flowtran 规则一致）

```
packagePath.split('.')[3]  →  domain keyword
dept  → deposit   |  loan → loan  |  sett → settlement  |  comm → public
unvr  → unvr      |  aggr → aggr  |  其他 → public（兜底）
```

#### 3.5 缓存刷新触发

- 系统启动时自动加载（`@PostConstruct`）
- 提供 `POST /api/flowtran/cache/refresh` 接口，手动触发热重载（不重启服务）
- 切换 `flowtran.datasource` 开关并重启后自动重新加载

---

### flowtran 表结构

```sql
CREATE TABLE flowtran (
    id           VARCHAR(100) PRIMARY KEY  COMMENT '交易ID（如 pbf_TD0101_dept）',
    longname     VARCHAR(500)              COMMENT '交易名称（中文）',
    package_path VARCHAR(500)              COMMENT '包路径（含领域信息）',
    txn_mode     VARCHAR(50)               COMMENT '事务模式',
    from_jar     VARCHAR(100)              COMMENT '来源jar包（即所属子工程）',
    create_time  DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
)
```

### flow_step 表结构

```sql
CREATE TABLE flow_step (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    flow_id       VARCHAR(100)   COMMENT '对应 flowtran.id',
    node_name     VARCHAR(500)   COMMENT '节点名称（服务/构件编码）',
    node_type     VARCHAR(50)    COMMENT '节点类型: service/method',
    step          INT            COMMENT '步骤顺序',
    node_longname VARCHAR(500)   COMMENT '节点中文名',
    file_name     VARCHAR(500)   COMMENT '匹配的源文件名',
    file_path     VARCHAR(1000)  COMMENT '匹配的源文件路径',
    file_jar_name VARCHAR(100)   COMMENT '来源jar包',
    incorrect_calls VARCHAR(1000) COMMENT '违规调用列表（逗号分隔）'
)
```

---

## User Scenarios & Testing

### User Story 1 - 通过配置开关切换 flowtran 数据源环境 (Priority: P1)

运维人员或开发者在不重新打包的情况下，通过修改配置文件中的一个开关（`flowtran.datasource`），
将 AxonLink 的 flowtran 数据读取切换到「外网本地」或「内网」两套环境之一。

**Why this priority**: 双数据源切换是本次功能的基础能力，其他故事均依赖于此。

**Independent Test**: 将 `flowtran.datasource=local` 改为 `flowtran.datasource=intranet` 并重启，
交易列表来源从阿里云 RDS 切换为内网 benchmarkdb，条目数发生变化。

**Acceptance Scenarios**:

1. **Given** 配置 `flowtran.datasource=local`，**When** 系统启动，**Then** flowtran 数据读自外网本地 `mall_admin`。
2. **Given** 配置 `flowtran.datasource=intranet`，**When** 系统启动，**Then** flowtran 数据读自内网 `benchmarkdb`。
3. **Given** 开关配置缺失或值非法，**When** 系统启动，**Then** 默认使用 `local`，并在日志中输出警告。
4. **Given** 所选数据源连接失败，**When** 系统启动，**Then** 系统仍正常启动，交易列表展示提示「数据源不可用」。

---

### User Story 2 - 自动从 flowtran 同步交易到领域列表 (Priority: P2)

分析人员打开 AxonLink，侧边栏各领域下自动展示从当前激活数据源的 `flowtran` 表中读取的真实交易，
不再依赖手工维护的静态 SQL。

**Why this priority**: 这是 flowtran 数据驱动展示的核心链路。

**Independent Test**: 在当前激活数据源的 `flowtran` 中新增一条记录，刷新页面后可在对应领域下看到该交易。

**Acceptance Scenarios**:

1. **Given** `flowtran` 中有 `dept` 领域交易，**When** 用户点击「存款领域」，**Then** 交易列表展示并与表中数量一致。
2. **Given** `flowtran.package_path` 含 `loan`，**When** 查看「贷款领域」，**Then** 该交易出现在贷款领域下。
3. **Given** `flowtran` 表为空，**When** 打开任意领域，**Then** 展示「暂无交易」提示。

---

### User Story 3 - 领域归属自动推断 (Priority: P3)

系统根据 `flowtran.package_path` 或 `flowtran.from_jar` 自动推断交易归属于哪个业务领域，
无需手工配置映射关系。

**Why this priority**: 领域归属决定侧边栏分组，是展示准确性的关键。

**Independent Test**: 向 `flowtran` 插入不同 `package_path` 的记录，验证各领域分组计数正确。

**Acceptance Scenarios**:

1. **Given** `package_path` 包含 `dept`，**When** 系统同步，**Then** 归入「存款领域」。
2. **Given** `package_path` 包含 `loan`，**When** 系统同步，**Then** 归入「贷款领域」。
3. **Given** `package_path` 无法匹配，**When** 系统同步，**Then** 归入「公共领域」并标记「未分类」。

---

### User Story 4 - 交易链路展示与 flow_step 关联 (Priority: P4)

点击某交易后，系统根据 `flow_step` 表数据自动展示服务层/构件层调用链路，替代手工维护的链路图。

**Why this priority**: 链路展示是 AxonLink 核心价值，但依赖前序故事数据就绪。

**Independent Test**: 选择一条 `flow_step` 数据完整的交易，验证链路视图节点顺序与 `step` 字段一致。

**Acceptance Scenarios**:

1. **Given** `flow_step` 有该交易的节点，**When** 展开交易，**Then** 服务/构件按 `step` 升序排列。
2. **Given** `node_type=service`，**When** 显示节点，**Then** 归入服务层；`method` 归入构件层。
3. **Given** `incorrect_calls` 非空，**When** 展示链路，**Then** 对应节点显示「违规」标记。

---

### Edge Cases

- 切换数据源后，旧数据源的连接池需正确释放，不出现连接泄漏。
- `flowtran.id` 格式不规范时，领域推断失败的降级处理（归入「未分类」）。
- `flow_step` 中存在孤儿记录（`flow_id` 在 `flowtran` 不存在）时跳过不报错。
- `flowtran` 数据量 >10,000 条时，分页查询响应时间 < 1 秒。
- 两套数据源同时不可用时，系统降级展示本地静态数据。
- ServiceNodeCache 首次加载期间（启动后数秒内），链路接口返回原始 flow_step 节点 + `cacheStatus: "loading"`，不阻塞用户操作；缓存就绪后自动恢复全量富化响应。

---

## Requirements

### Functional Requirements

- **FR-001**: 系统 MUST 支持通过配置项 `flowtran.datasource`（值域：`local` / `intranet`）在启动时选择激活哪套 flowtran 数据源。
- **FR-002**: 系统 MUST 为两套 flowtran 数据源各维护独立连接配置（URL、用户名、密码），互不干扰。
- **FR-003**: 系统 MUST 在 `flowtran.datasource` 值非法时，回退到 `local` 并在启动日志中输出警告。
- **FR-004**: 系统 MUST 在激活数据源连接失败时，不阻塞启动，交易列表展示「数据源不可用」提示。
- **FR-005**: 系统 MUST 根据 `flowtran.package_path` / `flowtran.from_jar` 自动推断领域归属。
- **FR-006**: 系统 MUST 在领域侧边栏展示来自激活数据源的交易数量（实时查询）；`/api/flowtran/domains` 和 `/api/flowtran/domains/{domainKey}/transactions` **完全替换**现有 `/api/domains` 和 `/api/domains/{id}/transactions` 接口，前端统一使用 flowtran 系列 API，原接口可保留但不再由前端调用。
- **FR-007**: 系统 MUST 支持按交易名称（`longname`）和交易 ID 关键词搜索，分页返回结果。
- **FR-008**: 系统 MUST 读取 `flow_step` 中对应交易的步骤，**按 `step` 字段升序**构建服务/构件链路。
- **FR-009**: 系统 MUST 在页面顶部或设置面板展示当前激活的数据源环境名称（「外网本地」/「内网」）。
- **FR-010**: 系统 MUST 在启动时全量加载 `service` / `service_detail` / `component` / `component_detail` 四张表到内存 Map 缓存（ServiceNodeCache），**不允许在请求链路中发起 DB 查询**。
- **FR-011**: 缓存 key 规则 MUST 为：服务节点 = `{service_type_id}.{service_id}`；构件节点 = `{component_id}.{service_id}`；与 `flow_step.node_name` 一一对应。
- **FR-012**: 缓存 value（NodeCacheEntry）MUST 包含：`serviceName`、`serviceLongname`、`interfaceInputFieldType`（多 field 聚合为列表）、`interfaceInputFieldMulti`（聚合）、`interfaceOutputFieldType`（聚合）、`interfaceOutputFieldMulti`（聚合）、`nodeKind`（service_type 或 component_type：pcs/pbs/pbcb/pbcp/pbcc/pbct）、`packagePath`、`domainKey`（从 packagePath 第4段推断）。
- **FR-013**: `flow_step.node_type = method` 的节点 MUST **直接归属本交易所在领域**，不查缓存，不跨域。
- **FR-014**: `flow_step.node_type = service` 的节点 MUST 通过 `node_name` 命中 ServiceNodeCache，取 `nodeKind` 推断节点属于 pbs/pcs/pbcb/pbcp/pbcc/pbct，并由 `domainKey` 判断是否跨域。
- **FR-015**: 系统 MUST 提供 `POST /api/flowtran/cache/refresh` 接口，支持不重启服务的缓存热重载。
- **FR-016**: 缓存中 `node_name` 未命中（即数据库中无对应 service/component 定义）时，MUST 降级展示 `node_name` + `node_longname` 原始值，不报错。
- **FR-017**: ServiceNodeCache **尚未完成首次加载**时，链路接口 `GET /api/flowtran/transactions/{txId}/chain` MUST 降级返回 `flow_step` 原始节点（`node_name`、`node_longname` 照常返回，`nodeKind`/`domainKey`/接口字段均为 `null`），响应体中附加 `"cacheStatus": "loading"` 字段，不阻塞请求，不返回 503。

### 双数据源配置规范

两套数据源的配置键名约定如下，均可通过 `application-local.yml` 覆盖：

| 配置项 | 外网本地（local） | 内网（intranet） |
|--------|-----------------|----------------|
| URL | `flowtran.local.url` | `flowtran.intranet.url` |
| 用户名 | `flowtran.local.username` | `flowtran.intranet.username` |
| 密码 | `flowtran.local.password` | `flowtran.intranet.password` |
| 激活开关 | `flowtran.datasource=local` | `flowtran.datasource=intranet` |

### Key Entities

- **flowtran**：交易权威数据源，`id`=交易码，`longname`=中文名，`package_path` 携带领域信息。
- **flow_step**：交易调用链路步骤，`node_type`/`node_name`/`node_longname` 描述节点，`step` 决定顺序。
- **service**：服务主表（pbs/pcs），`id`=serviceType.id，`service_type` 区分服务类型，`package_path` 含领域。
- **service_detail**：服务明细，`service_type_id` + `service_id` 唯一定位一个方法，含 input/output field 信息。
- **component**：构件主表（pbcb/pbcp/pbcc/pbct），`component_type` 区分构件类型，`package_path` 含领域。
- **component_detail**：构件明细，`component_id` + `service_id` 唯一定位，含 input/output field 信息。
- **ServiceNodeCache**：内存 Map，启动时加载四表，key=`type_id.service_id`，供链路展示 O(1) 查找。
- **NodeCacheEntry**：缓存值对象，含名称、接口字段类型/多值标识、节点分类（pbs/pcs/pbcc 等）、所属领域。
- **FlowtranDatasource（配置）**：激活的数据源标识（`local` / `intranet`），启动时读取，运行期不变。

---

## Success Criteria

### Measurable Outcomes

- **SC-001**: 修改 `flowtran.datasource` 配置并重启后，10 秒内交易列表切换到对应数据源数据。
- **SC-002**: 两套数据源切换操作无需重新打包，仅修改配置文件即可完成。
- **SC-003**: 用户打开任意领域后，交易列表在 2 秒内展示来自激活数据源的数据。
- **SC-004**: 领域分组准确率 ≥ 95%（`package_path` 含明确领域关键字的交易均正确归属）。
- **SC-005**: 10,000 条 `flowtran` 数据下，分页查询响应时间 < 1 秒。
- **SC-006**: 任意一套数据源不可用时，系统不崩溃，页面给出明确状态提示。

---

## Assumptions

- 两套数据库的 `flowtran` / `flow_step` 表结构完全一致。
- 外网本地（`local`）为开发/测试默认环境，内网（`intranet`）为生产/联调环境。
- `flowtran` 数据由 sunline-benchmark 工程的 Jar 扫描流程写入，AxonLink 只读不写。
- `flowtran.id` 为**纯交易码**格式（如 `TC0076`、`TC0033`），**不含领域信息**；领域归属**仅从 `package_path` 第4段推断**，不从 `id` 提取（早期文档中 `pbf_TD0101_dept` 格式为笔误，已废弃）。
- AxonLink 已有的主数据源（`mall_admin` / `benchmarkdb` 的 AxonLink 业务表）与 flowtran 数据源分开管理，不互相干扰。
- 移动端支持不在本期范围内。

### 两套环境连接信息（明文，仅本地配置文件使用，不提交 Git）

| 项目 | 外网本地（local） | 内网（intranet） |
|------|-----------------|----------------|
| Host | `rm-uf65l2bqe8kpu0e219o.mysql.rds.aliyuncs.com:3306` | `21.64.203.16:3306` |
| 库名 | `mall_admin` | `benchmarkdb` |
| 用户名 | `mall` | `benchmark` |
| 密码 | `Liang@201314` | `benchmark123` |

---

## Clarifications

### Session 2026-03-26

- Q: `flowtran.id` 的格式是纯交易码（如 `TC0076`）还是含领域后缀（`pbf_TD0101_dept`）？ → A: **纯交易码**（Option A）；领域归属仅从 `package_path` 第4段推断，`id` 不含领域信息，Assumptions 中旧格式描述为笔误已废弃。
- Q: flowtran 领域/交易接口与现有 `/api/domains` 的关系是替换、共存还是后端合并路由？ → A: **完全替换**（Option A）；`/api/flowtran/domains` 系列接口替代现有 `/api/domains` 系列，前端统一切换到 flowtran API，无需双套逻辑。
- Q: ServiceNodeCache 尚未加载完成时，链路接口的处理策略是什么？ → A: **降级返回原始数据**（Option A）；返回 flow_step 原始节点，富化字段为 null，响应附加 `cacheStatus: "loading"`，不阻塞请求，不返回 503。
