# 交易链路领域全量导出口令保护 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 领域全量链路导出只有在共享口令校验通过后才生成并下载 Excel。

**Architecture:** 后端 `FlowtranController` 在调用 `FlowtranChainExportService` 前校验 `DaoIndexAnalysisProperties.batchTrigger.token` 与请求头 `X-DII-Trigger-Token`；配置为空沿用现有开发放行语义。前端点击下载先打开密码弹窗，确认后把口令传给下载 API；401 保留弹窗、清空输入并提示，成功或取消时清理口令。

**Tech Stack:** Java 17、Spring Boot 3、MockMvc、Vue 3、Vitest、Vite。

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/交易链路按领域全量导出-系统设计.md`

## Global Constraints

- 复用 `dao-index-analysis.batch-trigger.token` / `DII_BATCH_TRIGGER_TOKEN`，不新增配置项。
- 请求头固定为 `X-DII-Trigger-Token`。
- 缺失或错误口令返回 HTTP 401、正文 `口令错误`，且不得调用导出服务。
- 配置为空时沿用既有开发环境放行语义。
- 前端不得把口令写入 localStorage、sessionStorage、URL 或日志。
- 不新增数据库表或字段。
- 当前工作区包含用户既有改动，除非用户另行要求，不执行 Git commit。

---

### Task 1: 后端导出接口口令门禁

**Files:**
- Modify: `src/test/java/com/axonlink/controller/FlowtranChainExportControllerTest.java`
- Modify: `src/main/java/com/axonlink/controller/FlowtranController.java`

**Interfaces:**
- Consumes: `DaoIndexAnalysisProperties#getBatchTrigger().getToken()` 和请求头 `X-DII-Trigger-Token`。
- Produces: `exportDomainChains(String domainKey, String token, HttpServletRequest request)`；校验通过时保持原 Excel 响应，失败时返回 HTTP 401 文本 `口令错误`。

- [x] **Step 1: 写缺失、错误、正确和空配置四类控制器测试**

在 `FlowtranChainExportControllerTest` 的 `setUp()` 中构造 `DaoIndexAnalysisProperties`，默认设置共享口令 `secret` 并传入 `FlowtranController`。增加测试：

```java
@Test
void rejectsMissingExportTokenWithoutGeneratingWorkbook() throws Exception {
    mvc.perform(get("/api/flowtran/domains/public/chains/export"))
            .andExpect(status().isUnauthorized())
            .andExpect(content().string("口令错误"));
    verifyNoInteractions(exportService);
}

@Test
void rejectsWrongExportTokenWithoutGeneratingWorkbook() throws Exception {
    mvc.perform(get("/api/flowtran/domains/public/chains/export")
                    .header("X-DII-Trigger-Token", "wrong"))
            .andExpect(status().isUnauthorized())
            .andExpect(content().string("口令错误"));
    verifyNoInteractions(exportService);
}
```

把既有成功、404、500 测试请求统一加 `.header("X-DII-Trigger-Token", "secret")`；另建一个配置 token 为空的 controller，验证无请求头时仍能导出。

- [x] **Step 2: 运行测试确认 RED**

Run:

```bash
JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home \
mvn -Dtest=FlowtranChainExportControllerTest test
```

Expected: FAIL，表现为缺失/错误口令仍返回 200 或导出服务被调用。

- [x] **Step 3: 实现最小后端校验**

给 `FlowtranController` 构造器增加 `DaoIndexAnalysisProperties`，导出方法增加请求头和请求对象参数；在 `try` 之前校验：

```java
String expected = daoIndexProperties.getBatchTrigger().getToken();
if (expected != null && !expected.trim().isEmpty() && !expected.equals(token)) {
    log.warn("[flowtran-chain-export] token rejected remoteAddr={} hasToken={}",
            request.getRemoteAddr(), token != null);
    return textError(HttpStatus.UNAUTHORIZED, "口令错误");
}
```

日志只记录是否带口令，不记录口令值。

- [x] **Step 4: 运行控制器测试确认 GREEN**

Run: 与 Step 2 相同。

Expected: `Tests run: 6, Failures: 0, Errors: 0`（实际数量以新增测试拆分结果为准）。

---

### Task 2: 前端下载 API 透传口令

**Files:**
- Create: `/Users/java/axon-link-frontend/src/api/index.spec.js`
- Modify: `/Users/java/axon-link-frontend/src/api/index.js`

**Interfaces:**
- Consumes: `exportFlowtranDomainChains(domainKey, token)`。
- Produces: `download(url, fallbackFileName, options = {})` 支持合并 fetch 选项；导出调用设置 `headers: {'X-DII-Trigger-Token': token}`。

- [x] **Step 1: 写 API 请求头失败测试**

在新测试文件中模拟成功 Blob 响应和 `window.URL`，调用：

```javascript
await exportFlowtranDomainChains('public', 'secret')
expect(fetch).toHaveBeenCalledWith(
  '/api/flowtran/domains/public/chains/export',
  { headers: { 'X-DII-Trigger-Token': 'secret' } },
)
```

另测 401 文本 `口令错误` 被抛成 `Error('口令错误')`。

- [x] **Step 2: 运行测试确认 RED**

Run:

```bash
npm test -- src/api/index.spec.js
```

Workdir: `/Users/java/axon-link-frontend`

Expected: FAIL，因为现有函数只接收 `domainKey`，fetch 未带 headers。

- [x] **Step 3: 实现下载选项和口令请求头**

```javascript
export async function download(url, fallbackFileName, options = {}) {
  const res = await fetch(BASE + url, options)
  // 保持既有响应与文件名解析逻辑
}

export function exportFlowtranDomainChains(domainKey, token) {
  return download(
    `/flowtran/domains/${encodeURIComponent(domainKey)}/chains/export`,
    `${domainKey}-全量交易链路.xlsx`,
    { headers: { 'X-DII-Trigger-Token': token || '' } },
  )
}
```

- [x] **Step 4: 运行 API 测试确认 GREEN**

Run: 与 Step 2 相同。

Expected: 新增 API 测试全部通过。

---

### Task 3: 前端口令弹窗与状态交互

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/views/TransactionAnalysis.spec.js`
- Modify: `/Users/java/axon-link-frontend/src/views/TransactionAnalysis.vue`

**Interfaces:**
- Consumes: `exportFlowtranDomainChains(domainKey, token)`。
- Produces: `chainExportTokenDialogOpen`、`chainExportToken`、`chainExportDialogError` 状态；`openChainExportDialog()`、`closeChainExportDialog()`、`confirmDomainChainExport()` 行为。

- [x] **Step 1: 写弹窗交互失败测试**

替换既有“点击即导出”测试，覆盖：

```javascript
await wrapper.get('[data-testid="export-domain-chains"]').trigger('click')
expect(wrapper.get('[data-testid="chain-export-token-dialog"]').exists()).toBe(true)
expect(exportFlowtranDomainChains).not.toHaveBeenCalled()

await wrapper.get('[data-testid="chain-export-token"]').setValue('secret')
await wrapper.get('[data-testid="confirm-chain-export"]').trigger('click')
expect(exportFlowtranDomainChains).toHaveBeenCalledWith('public', 'secret')
```

另测取消不请求且清空、空口令确认禁用、请求中按钮禁用、成功关闭弹窗、`Error('口令错误')` 时弹窗保留且输入清空、其他错误中文显示。

- [x] **Step 2: 运行测试确认 RED**

Run:

```bash
npm test -- src/views/TransactionAnalysis.spec.js
```

Workdir: `/Users/java/axon-link-frontend`

Expected: FAIL，页面尚无口令弹窗和对应测试标识。

- [x] **Step 3: 实现最小弹窗和交互**

在工具栏按钮点击时调用 `openChainExportDialog`；在页面根节点内增加遮罩和对话框：

```vue
<div v-if="chainExportTokenDialogOpen" class="chain-export-dialog-mask">
  <section data-testid="chain-export-token-dialog" class="chain-export-dialog" role="dialog" aria-modal="true">
    <h3>导出全量交易链路</h3>
    <p>请输入操作口令，验证通过后开始导出当前领域。</p>
    <input v-model="chainExportToken" data-testid="chain-export-token"
           type="password" autocomplete="off" @keyup.enter="confirmDomainChainExport" />
    <p v-if="chainExportDialogError" role="alert">{{ chainExportDialogError }}</p>
    <button type="button" :disabled="chainExporting" @click="closeChainExportDialog">取消</button>
    <button data-testid="confirm-chain-export" type="button"
            :disabled="!chainExportToken.trim() || chainExporting"
            @click="confirmDomainChainExport">{{ chainExporting ? '导出中…' : '确认导出' }}</button>
  </section>
</div>
```

错误判断使用 `error?.message === '口令错误'`：该场景保持弹窗、清空输入；成功和取消清空全部敏感状态。

- [x] **Step 4: 运行页面测试确认 GREEN**

Run: 与 Step 2 相同。

Expected: `TransactionAnalysis.spec.js` 全部通过。

---

### Task 4: 集成验证与交付构建

**Files:**
- Modify: `docs/superpowers/plans/2026-08-24-flowtran-domain-chain-export-token.md`（勾选实际完成项）
- Generated: `src/main/resources/static/**`
- Generated: `target/axon-link-server-1.0.0.jar`

**Interfaces:**
- Consumes: Tasks 1–3 的后端接口和前端交互。
- Produces: 包含口令弹窗及后端校验类的可运行 Spring Boot JAR。

- [x] **Step 1: 运行后端本功能定向测试**

```bash
JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home \
mvn -Dtest=FlowtranChainExportControllerTest,FlowtranChainExportServiceImplTest,FlowtranControllerErrorCodeTest test
```

Expected: 0 failures、0 errors。

- [x] **Step 2: 运行前端全量测试**

```bash
npm test -- --run
```

Workdir: `/Users/java/axon-link-frontend`

Expected: 0 failures。

- [x] **Step 3: 以真实后端模式构建前端**

```bash
VITE_USE_MOCK=0 npm run build
```

Workdir: `/Users/java/axon-link-frontend`

Expected: 构建成功，产物写入 `/Users/java/axon-link-server/src/main/resources/static`。

- [x] **Step 4: 构建后端 JAR**

```bash
JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home \
mvn -DskipTests package
```

Expected: `BUILD SUCCESS`。

- [x] **Step 5: 核验交付内容**

```bash
jar tf target/axon-link-server-1.0.0.jar | rg 'BOOT-INF/classes/static/index.html|FlowtranController.class|FlowtranChainExportServiceImpl.class'
shasum -a 256 target/axon-link-server-1.0.0.jar
```

Expected: 三个关键条目均存在并输出最终 SHA-256。
