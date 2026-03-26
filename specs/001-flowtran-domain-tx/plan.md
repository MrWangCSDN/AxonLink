# Implementation Plan: flowtran 数据驱动业务领域交易

**Branch**: `001-flowtran-domain-tx` | **Date**: 2026-03-26  
**Spec**: [spec.md](spec.md) | **Research**: [research.md](research.md)

---

## Summary

以 `flowtran` 表为交易数据权威来源，替换 AxonLink 现有的静态 SQL 数据。核心包含三块：
1. **FlowtranService**：从 flowtran/flow_step 表查询领域、交易列表和链路步骤（JdbcTemplate）
2. **ServiceNodeCache**：启动时全量加载 service/component 四张表到内存 Map，供链路节点富化
3. **FlowtranController**：提供 `/api/flowtran/*` 系列接口，支持双数据源环境切换（配置文件级）

---

## Technical Context

**Language/Version**: Java 17  
**Primary Dependencies**: Spring Boot 3.1.1, MyBatis-Plus 3.5.4, Druid, JdbcTemplate  
**Storage**: MySQL 8.x（mall_admin = 外网本地；benchmarkdb = 内网，通过 application-local.yml 切换）  
**Testing**: Spring Boot Test  
**Target Platform**: Linux 服务器（内网）/ macOS（开发）  
**Project Type**: Web Service（Spring Boot 前后端一体）  
**Performance Goals**: 领域列表 < 500ms；交易分页 < 1s（10k 条）；缓存加载 < 500ms  
**Constraints**: 不引入新第三方库；ServiceNodeCache 不允许请求链路触发 DB 查询  
**Scale/Scope**: flowtran 预估 1k-10k 条；service/component_detail 预估 10k-100k 行

---

## Constitution Check

| 原则 | 状态 | 说明 |
|------|------|------|
| I. 分层架构 | ✅ 通过 | Controller → FlowtranService 接口 → ServiceImpl → JdbcTemplate，无跨层 |
| II. 领域隔离 | ✅ 通过 | method 节点强制本域；service 节点通过 packagePath 推断跨域标记 |
| III. 接口实现分离 | ✅ 通过 | `FlowtranService` 接口 + `FlowtranServiceImpl` 实现 |
| IV. Webhook 自动化 | ✅ 通过 | `/api/flowtran/cache/refresh` 可由 Webhook 触发 |
| VI. 代码质量 | ✅ 通过 | `R<T>` 响应包装，`/api/flowtran/*` 路径，Javadoc |
| VII. 简洁优先 | ✅ 通过 | 单 DataSource，JdbcTemplate 直查，无多余依赖 |

---

## Project Structure

### Documentation (this feature)

```text
specs/001-flowtran-domain-tx/
├── plan.md              ← 本文件
├── research.md          ← 技术调研决策
├── data-model.md        ← 数据模型 + 缓存结构
├── quickstart.md        ← 开发验证指南
├── contracts/
│   └── api-spec.md      ← REST API 契约
├── checklists/
│   └── requirements.md  ← 规格质量检查清单
└── tasks.md             ← /speckit.tasks 输出（下一步）
```

### Source Code

```text
src/main/java/com/axonlink/
├── common/
│   ├── R.java                          （已有）
│   └── DomainKeyResolver.java          ← NEW：packagePath → domainKey 静态工具类
├── config/
│   ├── WebMvcConfig.java               （已有）
│   ├── MybatisPlusConfig.java          （已有）
│   └── FlowtranConfig.java             ← NEW：@ConfigurationProperties("flowtran")
├── service/
│   ├── FlowtranService.java            ← NEW：接口
│   ├── ServiceNodeCache.java           ← NEW：内存缓存，@PostConstruct 加载
│   ├── ProjectIndexer.java             （已有）
│   ├── ChainService.java               （已有）
│   ├── CallGraphScanner.java           （已有）
│   └── CallGraphService.java           （已有）
├── service/impl/
│   └── FlowtranServiceImpl.java        ← NEW：JdbcTemplate 查询实现
├── controller/
│   ├── FlowtranController.java         ← NEW：/api/flowtran/* 接口
│   ├── ApiController.java              （已有）
│   ├── SourceController.java           （已有）
│   └── CallGraphController.java        （已有）
└── dto/
    ├── FlowtranDomain.java             ← NEW
    ├── FlowtranTransaction.java        ← NEW
    ├── FlowtranChain.java              ← NEW
    ├── FlowChainNode.java              ← NEW
    └── NodeCacheEntry.java             ← NEW

src/main/resources/
├── application.yml                     ← 新增 flowtran.* 配置节
├── application-local.yml               （已有，需补充 flowtran.datasource）
└── db/migration/
    ├── V4__flowtran.sql                （已有）
    └── V4_1__flowtran_testdata.sql     （已有，21 条测试数据）

frontend/src/
├── api/
│   └── index.js                        ← 新增 flowtran API 方法
└── components/
    └── TransactionCard.vue             ← 无需修改（复用现有链路展示结构）
```

---

## Implementation Phases

### Phase A：基础服务层（P1 功能）

**目标**：领域列表 + 交易分页 + 环境切换配置可工作

| 编号 | 任务 | 文件 |
|------|------|------|
| A1 | 新增 `DomainKeyResolver`（packagePath 第4段 → domainKey 静态 Map） | `common/DomainKeyResolver.java` |
| A2 | 新增 `FlowtranConfig`（`@ConfigurationProperties`，flowtran.datasource 等） | `config/FlowtranConfig.java` |
| A3 | 更新 `application.yml`，新增 `flowtran.*` 配置节 | `application.yml` |
| A4 | 定义 `FlowtranService` 接口（listDomains、listTransactions） | `service/FlowtranService.java` |
| A5 | 实现 `FlowtranServiceImpl`（JdbcTemplate 查 flowtran 表） | `service/impl/FlowtranServiceImpl.java` |
| A6 | 新增 DTO 类：FlowtranDomain、FlowtranTransaction | `dto/*.java` |
| A7 | 新增 `FlowtranController`（GET /api/flowtran/domains、/transactions） | `controller/FlowtranController.java` |
| A8 | 前端：api/index.js 新增 `getFlowtranDomains`、`getFlowtranTransactions` | `frontend/src/api/index.js` |

### Phase B：ServiceNodeCache（P1 前提）

**目标**：启动时加载四表，缓存可热重载

| 编号 | 任务 | 文件 |
|------|------|------|
| B1 | 定义 `NodeCacheEntry` DTO | `dto/NodeCacheEntry.java` |
| B2 | 实现 `ServiceNodeCache`：@PostConstruct 加载，JOIN SQL 查询，多行聚合逻辑 | `service/ServiceNodeCache.java` |
| B3 | 在 `FlowtranController` 新增 `POST /api/flowtran/cache/refresh` 和 `GET /api/flowtran/cache/stats` | `controller/FlowtranController.java` |

### Phase C：交易链路展示（P3/P4 功能）

**目标**：flow_step + ServiceNodeCache 富化返回完整链路

| 编号 | 任务 | 文件 |
|------|------|------|
| C1 | 定义 `FlowChainNode`、`FlowtranChain` DTO | `dto/*.java` |
| C2 | `FlowtranService` 新增 `getChain(txId)` 方法 | `service/FlowtranService.java` |
| C3 | `FlowtranServiceImpl` 实现：查 flow_step（按 step 升序），查 ServiceNodeCache 富化，method 类型归本域 | `service/impl/FlowtranServiceImpl.java` |
| C4 | `FlowtranController` 新增 `GET /api/flowtran/transactions/{txId}/chain` | `controller/FlowtranController.java` |

### Phase D：集成验证

| 编号 | 任务 |
|------|------|
| D1 | 本地启动，验证 A8 前端 API 调用返回正确数据 |
| D2 | 验证 ServiceNodeCache 加载统计（/api/flowtran/cache/stats） |
| D3 | 插入 flow_step 测试数据，验证链路 JSON 结构正确 |
| D4 | 修改 application-local.yml 切换到 intranet，验证服务正常启动 |

---

## Key Design Notes

1. **method 节点归本域**：`FlowtranServiceImpl.getChain()` 中，当 `node_type=method` 时，`domainKey` 直接取 `flowtran.domain_key`，`crossDomain=false`，不查缓存
2. **缓存未命中降级**：ServiceNodeCache 查不到 key 时，返回 `FlowChainNode` 中 `nodeKind=null`，前端展示原始 `node_name` + `node_longname`
3. **并发加载**：ServiceNodeCache 加载在 daemon 线程异步进行（同 ProjectIndexer），不阻塞 Spring 启动；`loaded=false` 时访问 cache/stats 返回 `{"loaded": false}`
4. **单 DataSource**：flowtran 表与 AxonLink 业务表在同一 DB，无需双 DataSource Bean

---

## Complexity Tracking

无 Constitution 违规，无需 Complexity 说明。
