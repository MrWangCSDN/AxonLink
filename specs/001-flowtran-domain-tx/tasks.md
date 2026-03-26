# Tasks: flowtran 数据驱动业务领域交易（双数据源 + ServiceNodeCache）

**Branch**: `001-flowtran-domain-tx`  
**Input**: [spec.md](spec.md) · [plan.md](plan.md) · [data-model.md](data-model.md) · [contracts/api-spec.md](contracts/api-spec.md)  
**Date**: 2026-03-26

---

## Phase 1: Setup（共享基础设施）

**Purpose**: 创建新增文件所需的包目录结构，确认现有项目基线

- [X] T001 在 `src/main/java/com/axonlink/` 下确认 `dto/` 和 `service/impl/` 目录存在，不存在则创建（放置一个 `.gitkeep` 占位）
- [X] T002 确认 `src/main/resources/db/migration/V4__flowtran.sql` 和 `V4_1__flowtran_testdata.sql` 已执行，`flowtran` 表有 21 条测试数据（执行 `SELECT COUNT(*) FROM flowtran`）

**Checkpoint**: 目录就绪，测试数据可查 → 可进入 Phase 2

---

## Phase 2: 基础设施（阻塞所有用户故事）

**Purpose**: DomainKeyResolver、FlowtranConfig、配置文件更新 —— 所有用户故事的共同前提

**⚠️ CRITICAL**: Phase 2 全部完成后，Phase 3-6 才可开始

- [X] T003 [P] 新增 `src/main/java/com/axonlink/common/DomainKeyResolver.java`
  - 静态 Map：`dept→deposit`，`loan→loan`，`sett→settlement`，`comm→public`，`unvr→unvr`，`aggr→aggr`，`inbu→inbu`，`medu→medu`，`stmt→stmt`，其余→`public`
  - 方法签名：`public static String resolve(String packagePath)` — `packagePath.split("\\.")[3]` 取第4段，查 Map，未命中返回 `"public"`
  - Javadoc 说明参数含义和兜底规则

- [X] T004 [P] 新增 `src/main/java/com/axonlink/config/FlowtranConfig.java`
  - `@ConfigurationProperties(prefix = "flowtran")`，`@Configuration`
  - 字段：`String datasource`（默认 `"local"`）、`boolean cacheEnabled`（默认 `true`）
  - Javadoc 说明两个配置项的用途

- [X] T005 更新 `src/main/resources/application.yml`（依赖 T004）
  - 在 `callgraph` 配置节后追加：
    ```yaml
    flowtran:
      datasource: ${FLOWTRAN_DATASOURCE:local}
      cache:
        enabled: ${FLOWTRAN_CACHE_ENABLED:true}
    ```

- [X] T006 更新 `src/main/resources/application-local.yml`（依赖 T005）
  - 追加：
    ```yaml
    flowtran:
      datasource: local    # local=mall_admin（外网本地） | intranet=benchmarkdb（内网）
    ```

**Checkpoint**: 编译通过（`mvn compile`），`FlowtranConfig` Bean 可注入 → Phase 3-6 可以开始

---

## Phase 3: 用户故事 1 — 配置开关 + 领域列表 (P1) 🎯 MVP

**Goal**: 用户打开 AxonLink，侧边栏显示来自 `flowtran` 表的真实领域列表和交易数量

**Independent Test**: `GET /api/flowtran/domains` 返回 dept 领域 txCount=21，其他领域 txCount=0

### 实现：FlowtranDomain DTO

- [X] T007 [P] 新增 `src/main/java/com/axonlink/dto/FlowtranDomain.java`
  - 字段：`String domainKey`，`String domainName`，`long txCount`，`String icon`
  - 无注解（纯 POJO），Lombok `@Data`

### 实现：FlowtranService 接口（领域列表部分）

- [X] T008 新增 `src/main/java/com/axonlink/service/FlowtranService.java`（依赖 T007）
  - 方法：`List<FlowtranDomain> listDomains()`
  - 接口级 Javadoc

### 实现：FlowtranServiceImpl（领域列表部分）

- [X] T009 新增 `src/main/java/com/axonlink/service/impl/FlowtranServiceImpl.java`（依赖 T003、T008）
  - `@Service`, `@RequiredArgsConstructor`
  - 注入 `JdbcTemplate`
  - `listDomains()`：
    ```sql
    SELECT domain_key, COUNT(*) AS tx_count FROM flowtran GROUP BY domain_key ORDER BY domain_key
    ```
  - 将结果通过 `DomainKeyResolver` 转换为 `FlowtranDomain`（补充 `domainName` 和 `icon` 静态映射）
  - 对 flowtran 为空的情况返回空列表（不抛异常）

### 实现：FlowtranController（领域接口）

- [X] T010 新增 `src/main/java/com/axonlink/controller/FlowtranController.java`（依赖 T009）
  - 类级 Javadoc 列出全部接口
  - `GET /api/flowtran/domains` → `R<List<FlowtranDomain>>`
  - 数据源不可用时捕获异常，返回 `R.fail("flowtran 数据源不可用")` + code 503

### 实现：前端 API（领域列表）

- [X] T011 更新 `frontend/src/api/index.js`（依赖 T010）
  - 新增：`export function getFlowtranDomains() { return request('/flowtran/domains') }`

**Checkpoint**: 启动服务，`curl http://localhost:8123/api/flowtran/domains` 返回 dept 领域、txCount=21 → US1/US2 P1 完成

---

## Phase 4: 用户故事 2 — 交易分页列表 (P2)

**Goal**: 点击某领域，分页展示该领域下来自 `flowtran` 的真实交易列表，支持关键词搜索

**Independent Test**: `GET /api/flowtran/domains/dept/transactions?page=1&size=10` 返回 10 条记录，total=21

### 实现：FlowtranTransaction DTO

- [X] T012 [P] 新增 `src/main/java/com/axonlink/dto/FlowtranTransaction.java`（依赖 T003）
  - 字段：`String id`，`String longname`，`String domainKey`，`String txnMode`，`String fromJar`
  - Lombok `@Data`

### 实现：FlowtranService 接口（交易分页部分）

- [X] T013 更新 `src/main/java/com/axonlink/service/FlowtranService.java`（依赖 T012）
  - 新增方法：`Map<String, Object> listTransactions(String domainKey, int page, int size, String keyword)`

### 实现：FlowtranServiceImpl（交易分页部分）

- [X] T014 更新 `src/main/java/com/axonlink/service/impl/FlowtranServiceImpl.java`（依赖 T013）
  - `listTransactions()` 实现：
    - 基础条件：`WHERE domain_key = ?`
    - 关键词条件：`AND (id LIKE ? OR longname LIKE ?)`
    - 分页：`LIMIT ? OFFSET ?`
    - 总数：`SELECT COUNT(*)` 同条件查询
    - 返回：`Map.of("list", List<FlowtranTransaction>, "total", long, "page", page, "size", size)`

### 实现：FlowtranController（交易分页接口）

- [X] T015 更新 `src/main/java/com/axonlink/controller/FlowtranController.java`（依赖 T014）
  - 新增 `GET /api/flowtran/domains/{domainKey}/transactions`
  - 参数：`@RequestParam(defaultValue="1") int page`，`size`，`keyword`

### 实现：前端 API（交易分页）

- [X] T016 更新 `frontend/src/api/index.js`（依赖 T015）
  - 新增：
    ```js
    export function getFlowtranTransactions(domainKey, page=1, size=20, keyword='') {
      const kw = keyword ? `&keyword=${encodeURIComponent(keyword)}` : ''
      return request(`/flowtran/domains/${domainKey}/transactions?page=${page}&size=${size}${kw}`)
    }
    ```

**Checkpoint**: `curl "http://localhost:8123/api/flowtran/domains/dept/transactions?page=1&size=5&keyword=开户"` 返回开户相关交易 → US2 P2 完成

---

## Phase 5: 用户故事 3 — ServiceNodeCache（缓存基础设施）

**Goal**: 启动时全量加载 service/component 四张表到内存 Map，供链路展示使用（US4 前提）

**Independent Test**: `GET /api/flowtran/cache/stats` 返回 `loaded=true`，`totalCount > 0`（需 benchmarkdb 或 mall_admin 有 service/component 数据）

### 实现：NodeCacheEntry DTO

- [X] T017 [P] 新增 `src/main/java/com/axonlink/dto/NodeCacheEntry.java`
  - 字段（见 data-model.md 2.3 节）：`serviceName`，`serviceLongname`，`interfaceInputFieldTypes`，`interfaceInputFieldMultis`，`interfaceOutputFieldTypes`，`interfaceOutputFieldMultis`，`nodeKind`，`packagePath`，`domainKey`
  - Lombok `@Data @Builder @AllArgsConstructor @NoArgsConstructor`

### 实现：ServiceNodeCache

- [X] T018 新增 `src/main/java/com/axonlink/service/ServiceNodeCache.java`（依赖 T003、T017）
  - `@Component @RequiredArgsConstructor`
  - 字段：`ConcurrentHashMap<String, NodeCacheEntry> nodeMap`，`volatile boolean loaded`，`volatile long loadedAt`，`volatile int serviceCount`，`volatile int componentCount`
  - `@PostConstruct` 异步加载（daemon 线程，不阻塞启动，同 ProjectIndexer 模式）
  - `public void load()` 方法：
    1. 执行服务查询 SQL（service_detail JOIN service）
    2. 执行构件查询 SQL（component_detail JOIN component）
    3. 按 `{type_id}.{service_id}` 分组聚合 field types/multis（`String.join(",",...)`）
    4. 写入 `nodeMap`，更新统计字段
  - `public Optional<NodeCacheEntry> get(String nodeKey)` — `nodeMap.get(nodeKey)`
  - `public Map<String, Object> getStats()` — 返回 `loaded、totalCount、loadedAt、serviceCount、componentCount`
  - 加载失败时 `loaded=false`，只 log warn，不抛异常

  **service 加载 SQL**（注入 `JdbcTemplate` 执行）：
  ```sql
  SELECT sd.service_type_id, sd.service_id, sd.service_name, sd.service_longname,
         sd.interface_input_field_type, sd.interface_input_field_multi,
         sd.interface_output_field_type, sd.interface_output_field_multi,
         s.service_type AS node_kind, s.package_path
  FROM service_detail sd JOIN service s ON s.id = sd.service_type_id
  ```

  **component 加载 SQL**：
  ```sql
  SELECT cd.component_id, cd.service_id, cd.service_name, cd.service_longname,
         cd.interface_input_field_type, cd.interface_input_field_multi,
         cd.interface_output_field_type, cd.interface_output_field_multi,
         c.component_type AS node_kind, c.package_path
  FROM component_detail cd JOIN component c ON c.id = cd.component_id
  ```

### 实现：缓存管理接口

- [X] T019 更新 `src/main/java/com/axonlink/controller/FlowtranController.java`（依赖 T018）
  - `POST /api/flowtran/cache/refresh` → 调用 `serviceNodeCache.load()`，返回 `getStats()`
  - `GET /api/flowtran/cache/stats` → 返回 `serviceNodeCache.getStats()`
  - 注入 `ServiceNodeCache`（`@Autowired`）

**Checkpoint**: 启动服务，`curl http://localhost:8123/api/flowtran/cache/stats` 返回 `{"loaded":true/false, ...}` → Phase 5 完成（数据为空也算通过）

---

## Phase 6: 用户故事 4 — 交易链路展示 (P4)

**Goal**: 点击某交易，根据 `flow_step` + `ServiceNodeCache` 展示完整调用链路，节点按 `step` 升序排列

**Independent Test**: 插入 flow_step 测试数据后，`GET /api/flowtran/transactions/TC0033/chain` 返回正确 steps 列表

### 实现：链路 DTO

- [X] T020 [P] 新增 `src/main/java/com/axonlink/dto/FlowChainNode.java`（依赖 T017）
  - 字段（见 data-model.md 2.4 节）：`int step`，`String nodeType`，`String nodeName`，`String nodeLongname`，`String nodeKind`，`String domainKey`，`boolean crossDomain`，`String inputFieldTypes`，`String inputFieldMultis`，`String outputFieldTypes`，`String outputFieldMultis`

- [X] T021 [P] 新增 `src/main/java/com/axonlink/dto/FlowtranChain.java`（依赖 T020）
  - 字段：`String txId`，`String txLongname`，`String domainKey`，`List<FlowChainNode> steps`，`Map<String, Object> meta`

### 实现：FlowtranService 接口（链路方法）

- [X] T022 更新 `src/main/java/com/axonlink/service/FlowtranService.java`（依赖 T021）
  - 新增：`FlowtranChain getChain(String txId)`

### 实现：FlowtranServiceImpl（链路组装）

- [X] T023 更新 `src/main/java/com/axonlink/service/impl/FlowtranServiceImpl.java`（依赖 T018、T022）
  - 注入 `ServiceNodeCache`
  - `getChain(txId)` 实现：
    1. 查 `flowtran` 取 `longname`、`domain_key`（不存在返回 null）
    2. 查 `flow_step` 按 `step ASC`：`SELECT * FROM flow_step WHERE flow_id = ? ORDER BY step ASC`
    3. 遍历 steps，对每个节点：
       - `node_type = "method"` → `crossDomain=false`，`domainKey=交易领域`，不查缓存
       - `node_type = "service"` → `serviceNodeCache.get(node_name)`，命中则填充 nodeKind/domainKey/fields；未命中则所有缓存字段为 null（降级）
       - `crossDomain = nodeEntry.domainKey ≠ flowtran.domainKey`
    4. 组装 `FlowtranChain` 返回

### 实现：FlowtranController（链路接口）

- [X] T024 更新 `src/main/java/com/axonlink/controller/FlowtranController.java`（依赖 T023）
  - 新增 `GET /api/flowtran/transactions/{txId}/chain` → `R<FlowtranChain>`
  - txId 不存在时返回 404

### 实现：前端 API（链路接口）

- [X] T025 更新 `frontend/src/api/index.js`（依赖 T024）
  - 新增：`export function getFlowtranChain(txId) { return request('/flowtran/transactions/' + txId + '/chain') }`

**Checkpoint**: 插入 flow_step 测试数据后调用链路接口，steps 按 step 升序，method 节点 crossDomain=false → US4 完成

---

## Phase 7: 集成验证与收尾

**Purpose**: 端到端验证所有用户故事，确认双数据源切换正常

- [ ] T026 [US1] 验证数据源开关：将 `application-local.yml` 的 `flowtran.datasource` 改为 `intranet`，修改 `spring.datasource.url` 为 `benchmarkdb`，重启服务，确认启动日志无报错，`/api/flowtran/domains` 正常响应（数据可为空）
- [ ] T027 [US2] 全量 E2E 验证：`/api/flowtran/domains` → 取 `dept` → `/api/flowtran/domains/dept/transactions?page=1&size=10` → 总数=21，第1页10条
- [ ] T028 [US3] 领域推断验证：检查 `DomainKeyResolver.resolve("com.spdb.ccbs.loan.pbf.trans.xxx")` 返回 `"loan"`，`DomainKeyResolver.resolve("com.spdb.ccbs.unknown.x.y.z")` 返回 `"public"`
- [ ] T029 [US4] 链路验证：执行 quickstart.md 中的 flow_step 测试数据 SQL，调用 `/api/flowtran/transactions/TC0033/chain`，验证 steps 列表顺序正确
- [ ] T030 缓存热重载验证：`POST /api/flowtran/cache/refresh`，返回 `elapsedMs` 合理（< 1000ms）
- [ ] T031 [P] 代码质量检查：所有新增 Controller 有类级 Javadoc；Service 接口方法有 Javadoc；无 `@Value` 注入嵌套配置（使用 `FlowtranConfig`）
- [ ] T032 [P] 更新 `src/main/resources/application-local.yml.example`，补充 flowtran 配置示例注释

---

## 依赖与执行顺序

```
Phase 1 (T001-T002)
    ↓
Phase 2 (T003-T006)  ← 阻塞所有后续
    ↓
┌───────────────────────────────────┐
│ Phase 3 (T007-T011) [US1/P1 MVP]  │
│ Phase 4 (T012-T016) [US2/P2]      │← 可并行（US1 完成后即可开始）
│ Phase 5 (T017-T019) [缓存基础]    │
└───────────────────────────────────┘
    ↓（Phase 5 完成后）
Phase 6 (T020-T025) [US4/P4]
    ↓
Phase 7 (T026-T032) [集成验证]
```

### 并行机会

| 可并行组 | 任务 | 前提 |
|---------|------|------|
| Phase 2 内部 | T003, T004 | 无相互依赖 |
| Phase 3 启动 | T007, T008（等 T007）后 T009 | Phase 2 完成 |
| Phase 4 启动 | T012（与 T007 同时）| Phase 2 完成 |
| Phase 5 启动 | T017（与 T012 同时）| Phase 2 完成 |
| Phase 6 内部 | T020, T021 | 同时进行 |
| Phase 7 内部 | T026-T032 大部分可并行 | Phase 6 完成 |

---

## 实现策略

### MVP 优先（仅 Phase 3）

1. 完成 Phase 1 + Phase 2（基础设施）
2. 完成 Phase 3（T007-T011）
3. **停下来验证**：`/api/flowtran/domains` 返回真实数据
4. 服务可演示，价值已交付

### 完整交付顺序

Phase 1 → Phase 2 → Phase 3（MVP）→ Phase 4 → Phase 5 → Phase 6 → Phase 7

---

## 注意事项

- `[P]` = 与其他任务无文件冲突，可并行执行
- ServiceNodeCache 加载时，service/component 表若为空，`loaded=true` 但 `totalCount=0`，属正常情况
- `flowtran.domain_key` 在 V4 建表时已加入，测试数据已正确填写 `"dept"`
- 所有 JdbcTemplate 查询使用 `try-catch`，异常 log 后返回空结果，不向上抛出（连接失败降级）
