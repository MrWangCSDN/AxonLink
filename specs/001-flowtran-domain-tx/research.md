# Research: flowtran 数据驱动 + ServiceNodeCache

**Feature**: 001-flowtran-domain-tx  
**Phase**: Phase 0 Output  
**Date**: 2026-03-26

---

## Decision 1：双数据源策略

**问题**：spec 要求支持 `flowtran.datasource=local`（mall_admin）和 `intranet`（benchmarkdb）两套环境。是否需要两个独立的 DataSource Bean？

**Decision**: 单 DataSource，通过 `application-local.yml` 切换 URL + 账号，无需双 Bean。

**Rationale**:
- 两套环境中，AxonLink 自身业务表（`t_domain`、`t_transaction`、`cg_*`）和 flowtran 表均在**同一个 DB**（local = mall_admin，intranet = benchmarkdb）
- 切换环境只需在 `application-local.yml` 改 `spring.datasource.url` 等连接参数，服务重启即生效
- V4 建表脚本（`V4__flowtran.sql`）已随服务启动自动建表，本地 mall_admin 已存在 flowtran/flow_step 表
- 无需新增 `@Primary`/`@Qualifier` 多 DataSource 配置，符合**简洁优先（Constitution VII）**

**Alternatives considered**:
- ~~两个独立 DataSource Bean（FlowtranDataSource + PrimaryDataSource）~~：过度设计，flowtran 表与 AxonLink 表共享同一 DB，无需分离连接池

---

## Decision 2：flowtran 查询方式（JdbcTemplate vs MyBatis-Plus）

**问题**：flowtran/flow_step 是外部框架产生的表，不含 `deleted` 逻辑删除字段，直接用 MyBatis-Plus 会触发 `deleted=0` 拦截。

**Decision**: flowtran / flow_step 查询使用 **JdbcTemplate**（不走 MyBatis-Plus 全局拦截器）；service/component/service_detail/component_detail 缓存加载同样使用 JdbcTemplate。

**Rationale**:
- 避免 MyBatis-Plus GlobalConfig `logic-delete-field=deleted` 影响没有该字段的表
- JdbcTemplate 轻量、SQL 可读，适合聚合查询（GROUP BY domain、JOIN 明细等）
- AxonLink 自身表（t_domain、t_transaction）仍走 MyBatis-Plus Mapper，互不干扰

**Alternatives considered**:
- ~~MyBatis-Plus 注解 `@TableLogic` 关闭 + 新建 Mapper~~：侵入性更大，且需为所有 flowtran 表创建 Entity/Mapper

---

## Decision 3：ServiceNodeCache 实现策略

**问题**：service/component 四张表数据量未知，启动全量加载是否安全？

**Decision**: 启动时全量加载 → 内存 Map（`ConcurrentHashMap`），提供热重载 API。

**Rationale**:
- 根据 sunline-benchmark 工程分析，service_detail + component_detail 量级约 10k-100k 行（单 serviceType 含多行 field），全量加载 < 200ms
- 内存占用估算：100k 行 × 约 500 bytes/entry ≈ 50MB，JVM Xmx 1G 场景下可接受
- 使用 `@PostConstruct` + daemon 线程加载，不阻塞 Spring 启动（与 ProjectIndexer 同模式）
- 热重载 API 供 benchmarkdb 数据更新后无需重启服务

**Alternatives considered**:
- ~~按需懒加载（LRU Cache）~~：每次请求首次 miss 时触发 DB 查询，违反 FR-010「不允许在请求链路中发起 DB 查询」
- ~~定时轮询刷新~~：非必要复杂度，手动 refresh API 已足够（Constitution VII YAGNI）

---

## Decision 4：领域推断算法

**问题**：`package_path = "com.spdb.ccbs.dept.pbf.trans.qryMnt"`，如何提取领域？

**Decision**: `packagePath.split("\\.")[3]` 取第4段，静态 Map 映射到 AxonLink domain_key。

```java
static final Map<String, String> DOMAIN_MAP = Map.of(
    "dept", "deposit",
    "loan", "loan",
    "sett", "settlement",
    "comm", "public",
    "unvr", "unvr",
    "aggr", "aggr",
    "inbu", "inbu",
    "medu", "medu",
    "stmt", "stmt",
    "ap",   "public"
);
// 未命中 → "public"（兜底）
```

**Rationale**:
- package_path 格式稳定（XML 元数据生成），第4段始终是领域标识
- 静态 Map 比正则快，且已有全量领域枚举

---

## Decision 5：flow_step 链路组装策略

**问题**：`node_type=service` 时通过 `node_name` 查 ServiceNodeCache；`node_type=method` 时直归本域。如何组装返回给前端的链路结构？

**Decision**: 复用现有 `ChainService` 的链路 VO 结构，新增 `flowtranChain` 方法，返回与前端已适配的 JSON 格式（orchestration/service/component/data 四层）。

**Rationale**:
- 前端已有 `TransactionCard.vue` 渲染逻辑，复用响应结构减少前端改动
- flow_step 的 `node_type` 枚举（service/method）通过 ServiceNodeCache 的 `nodeKind` 进一步细分（pbs/pcs/pbcb/pbcp 等）
- `method` 类型归入「流程编排→本域」层；`service` 类型按 `nodeKind` 归入「流程编排/编码调用」子层

---

## Decision 6：API 路径设计

**Constitution Principle VI**：`/api/{module}/...`

| 路径 | 方法 | 说明 |
|------|------|------|
| `/api/flowtran/domains` | GET | 领域列表（来自 flowtran GROUP BY domain_key） |
| `/api/flowtran/domains/{domainKey}/transactions` | GET | 分页交易列表 |
| `/api/flowtran/transactions/{txId}/chain` | GET | 交易完整链路 |
| `/api/flowtran/cache/refresh` | POST | 热重载 ServiceNodeCache |
| `/api/flowtran/cache/stats` | GET | 缓存统计（条数、加载时间） |

---

## Constitution Compliance Check

| 原则 | 符合情况 |
|------|---------|
| I. 分层架构 | ✅ Controller → Service（接口） → ServiceImpl → JdbcTemplate；无跨层 |
| II. 领域隔离 | ✅ domain_key 推断 + flow_step node_type=method 强制本域 |
| III. 接口实现分离 | ✅ `FlowtranService` 接口 + `FlowtranServiceImpl` 实现 |
| IV. Webhook 自动化 | ✅ `/api/flowtran/cache/refresh` 可由 Webhook 触发热重载 |
| VI. 代码质量 | ✅ Controller Javadoc，`R<T>` 统一响应，`/api/flowtran/*` 路径规范 |
| VII. 简洁优先 | ✅ 单 DataSource，JdbcTemplate 直查，无额外第三方库 |
