# 问题所属领域转组高级权限 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为六个问题所属领域增加高级转组名单，使高级人员自动获得当前领域编辑权并可突破普通人员最多三次真实转组限制。

**Architecture:** 在现有 `ReplayIssueDomainProperties` 中增加与普通 `editors` 平行的 `advancedEditors`。权限接口把普通与高级名单并集投影为 `editableDomains`，把高级名单单独投影为 `transferLimitBypassDomains`；后端以修改前当前 `issue_domain` 为最终鉴权口径，前端只用相同投影决定三次后是否保持下拉可用。

**Tech Stack:** Java 17、Spring Boot ConfigurationProperties、Spring JDBC、JUnit 5、Vue 3、Vitest。

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md`、`并行回放问题清单-数据模型.md`、`并行回放问题清单-API接口.md`

## Global Constraints

- 权限键固定为修改前规范化后的当前 `issue_domain`，不是导入 `group_name` 或目标领域。
- 高级名单自动授予对应领域编辑权限；同一身份同时命中普通和高级名单时按高级权限。
- 普通人员真实转组达到 3 次后拒绝；高级人员第 4 次及后续不设新上限，继续正常计数和审计。
- `defect_repair_date` 非空锁定优先于普通、高级和次数豁免权限。
- `from_domain == to_domain` 继续幂等返回，不计数、不写专用历史和通用历史。
- 身份匹配保持现有规则：启用用户有 `emp_no` 时优先工号，无工号时回退 `username`。
- 不修改数据库结构，不治理历史数据，不修改 Excel 导出内容。
- 当前主分支存在用户既有未提交修改；只编辑本计划列出的文件，不提交、不推送、不清理其他改动。

---

### Task 1: 后端高级配置、权限投影与第四次转组

**Files:**
- Modify: `src/test/java/com/axonlink/ai/replay/service/ReplayIssueDomainServiceTest.java`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueDomainProperties.java`
- Modify: `src/main/java/com/axonlink/ai/replay/dto/ReplayIssueDomainPermissions.java`
- Modify: `src/main/java/com/axonlink/ai/replay/service/ReplayIssueDomainService.java`

**Interfaces:**
- Consumes: 当前问题的规范化 `issueDomain`、启用用户身份、普通 `editors`、新增 `advancedEditors`。
- Produces: `ReplayIssueDomainPermissions(editableDomains, transferLimitBypassDomains)`；高级人员第四次及后续转组成功结果。

- [x] **Step 1: 写服务层失败测试**

在 `ReplayIssueDomainServiceTest` 增加高级用户 `advanced-editor/500001`，仅把该用户配置到“迁移组”的 `advancedEditors`。新增用例断言：

```java
ReplayIssueDomainPermissions permissions = service.permissions(advancedOperator);
assertEquals(List.of("迁移组"), permissions.editableDomains());
assertEquals(List.of("迁移组"), permissions.transferLimitBypassDomains());

// 前三次由各当前领域普通人员完成，当前领域最终为迁移组。
ReplayIssueDomainUpdateResult fourth = service.update(issueId, "贷款组", advancedOperator);
assertEquals(4, fourth.transferCount());
assertEquals(4, service.transfers(issueId).items().size());
```

保留并强化现有普通“迁移组”人员第四次被 `TRANSFER_LIMIT_MESSAGE` 拒绝的测试；高级人员遇到非空 `defect_repair_date` 仍被 `REPAIRED_LOCK_MESSAGE` 拒绝；同领域高级保存仍不增加次数。

- [x] **Step 2: 运行服务测试确认 RED**

Run: `mvn -Dtest=ReplayIssueDomainServiceTest test`

Expected: 编译失败或断言失败，因为 `advancedEditors` 与 `transferLimitBypassDomains` 尚不存在，第四次仍被拒绝。

- [x] **Step 3: 实现最小后端能力**

在 `ReplayIssueDomainProperties` 增加：

```java
private Map<String, EditorGroup> advancedEditors = new LinkedHashMap<>();
```

并提供与 `editors` 相同空值保护的 getter/setter。将权限 DTO 改为：

```java
public record ReplayIssueDomainPermissions(
        List<String> editableDomains,
        List<String> transferLimitBypassDomains) { ... }
```

`permissions(...)` 按 `ALLOWED_DOMAINS` 固定顺序生成：普通或高级任一命中的 `editableDomains`，以及仅高级命中的 `transferLimitBypassDomains`。`update(...)` 在同一事务锁定问题后复用一次当前用户身份计算：普通或高级任一命中才可编辑；只有当前领域高级命中时跳过 `transferCount >= 3` 拒绝分支。缺陷日期锁定、目标规范化和同值幂等的执行顺序保持不变。

- [x] **Step 4: 运行服务测试确认 GREEN**

Run: `mvn -Dtest=ReplayIssueDomainServiceTest test`

Expected: PASS，普通第四次失败、高级第四次成功且返回计数 4。

---

### Task 2: HTTP 契约与六组 YAML 配置

**Files:**
- Modify: `src/test/java/com/axonlink/ai/replay/controller/ReplayIssueControllerTest.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/resources/application-local.yml.example`

**Interfaces:**
- Consumes: Task 1 的双集合权限 DTO。
- Produces: `GET /issue-domain-permissions` JSON 字段 `transferLimitBypassDomains`；六个高级名单环境变量。

- [x] **Step 1: 写权限接口契约测试**

在控制器夹具中把公共组测试用户同时配置为普通和高级人员，并断言：

```java
.andExpect(jsonPath("$.data.editableDomains[0]").value("公共组"))
.andExpect(jsonPath("$.data.transferLimitBypassDomains[0]").value("公共组"));
```

该断言同时验证普通与高级重复配置不会产生重复领域，且高级投影仍然存在。

- [x] **Step 2: 运行控制器精确测试**

Run: `mvn '-Dtest=ReplayIssueControllerTest#issueDomainPermissionsPatchAndHistoryAreExposed' test`

Expected: 在生产 DTO 尚未支持新字段时 RED；Task 1 完成后权限字段断言通过。

- [x] **Step 3: 增加六组高级 YAML 示例**

在 `dii.replay.issue-domain` 下保持普通配置不变，新增：

```yaml
advanced-editors:
  公共组:
    emp-nos: ${DII_REPLAY_ISSUE_DOMAIN_ADVANCED_EDITORS_PUBLIC:}
  存款组:
    emp-nos: ${DII_REPLAY_ISSUE_DOMAIN_ADVANCED_EDITORS_DEPOSIT:}
  贷款组:
    emp-nos: ${DII_REPLAY_ISSUE_DOMAIN_ADVANCED_EDITORS_LOAN:}
  结算组:
    emp-nos: ${DII_REPLAY_ISSUE_DOMAIN_ADVANCED_EDITORS_SETTLEMENT:}
  迁移组:
    emp-nos: ${DII_REPLAY_ISSUE_DOMAIN_ADVANCED_EDITORS_MIGRATION:}
  平台组:
    emp-nos: ${DII_REPLAY_ISSUE_DOMAIN_ADVANCED_EDITORS_PLATFORM:}
```

同步更新 `application.yml` 和 `application-local.yml.example`，注释明确高级名单自动授予编辑权并突破三次限制。

- [x] **Step 4: 回跑 HTTP 契约测试**

Run: `mvn '-Dtest=ReplayIssueControllerTest#issueDomainPermissionsPatchAndHistoryAreExposed' test`

Expected: PASS。

---

### Task 3: 前端三次后高级人员保持可编辑

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.spec.js`
- Modify: `/Users/java/axon-link-frontend/src/components/replay/ReplayIssuePage.vue`

**Interfaces:**
- Consumes: `getReplayIssueDomainPermissions()` 返回的 `editableDomains` 和 `transferLimitBypassDomains`。
- Produces: 高级人员在当前领域达到或超过 3 次后仍可选择并保存新领域；普通人员继续置灰。

- [x] **Step 1: 写前端失败测试**

保留现有“普通三次、缺陷已修复、无权限均锁定”用例；新增高级场景：

```js
arrangeApi({ items: [{ ...fixtureRow, issue_domain: '贷款组', issue_domain_transfer_count: 3 }] })
getReplayIssueDomainPermissions.mockResolvedValue({
  editableDomains: ['贷款组'],
  transferLimitBypassDomains: ['贷款组'],
})

expect(select.attributes('disabled')).toBeUndefined()
await select.setValue('公共组')
await select.trigger('blur')
expect(updateReplayIssueDomain).toHaveBeenCalledWith(1, '公共组')
```

再增加高级人员但 `defect_repair_date` 非空时仍置灰的断言。

- [x] **Step 2: 运行页面聚焦测试确认 RED**

Run: `npm test -- --run src/components/replay/ReplayIssuePage.spec.js`

Expected: 高级三次行仍被现有 `issueDomainTransferCount(row) >= 3` 分支置灰。

- [x] **Step 3: 实现前端最小分支**

权限状态和加载失败默认值增加 `transferLimitBypassDomains: []`。新增当前领域判断：

```js
function canBypassIssueDomainTransferLimit(row) {
  const currentDomain = String(row?.issue_domain || row?.group_name || '').trim()
  return (issueDomainPermissions.transferLimitBypassDomains || []).includes(currentDomain)
}
```

`canEditIssueDomain(row)` 仅在“次数达到 3 且不能豁免”时返回 false，并继续要求当前领域存在于 `editableDomains`。`issueDomainEditTitle(row)` 使用同一豁免函数：普通人员显示原上限文案，高级人员显示现有自动保存提示；缺陷修复日期提示仍最优先。

- [x] **Step 4: 运行页面聚焦测试确认 GREEN**

Run: `npm test -- --run src/components/replay/ReplayIssuePage.spec.js`

Expected: PASS，普通三次行保持锁定，高级三次行可保存，缺陷日期高级行仍锁定。

---

### Task 4: 回归、构建与记录

**Files:**
- Modify after verification: `/Users/java/obsidian/log.md`
- Verify only: backend and frontend worktrees.

**Interfaces:**
- Consumes: Tasks 1–3 的后端事实源、HTTP 权限投影与前端预判。
- Produces: 已验证的可发布静态资源和实施记录。

- [x] **Step 1: 运行本功能后端聚焦回归**

Run: `mvn -Dtest=ReplayIssueDomainServiceTest test`

Run: `mvn '-Dtest=ReplayIssueControllerTest#issueDomainPermissionsPatchAndHistoryAreExposed' test`

Expected: 两条命令均 PASS。控制器整类及后端全量现有历史失败只记录，不在本任务扩改。

- [x] **Step 2: 运行前端全量测试**

Run: `npm test -- --run`

Expected: 全部 PASS。

- [x] **Step 3: 构建前端到后端并验证后端打包**

Run: `npm run build`

Expected: Vite 构建成功，资源写入 `/Users/java/axon-link-server/src/main/resources/static`。

Run: `JAVA_HOME=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home PATH=/Users/wangshanhe/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home/bin:$PATH mvn -DskipTests package`

Expected: 生成 `target/axon-link-server-1.0.0.jar`。

- [x] **Step 4: 检查差异质量**

Run: `git diff --check`

Run: `git -C /Users/java/axon-link-frontend diff --check`

Expected: 两条命令 exit 0；不删除或覆盖用户既有修改。

- [x] **Step 5: 追加实施日志**

使用 `apply_patch` 向 `/Users/java/obsidian/log.md` 追加一条 `[IMPL]`，记录六组高级权限、第四次转组、锁定边界及实际测试结果。不得修改或删除历史日志。
