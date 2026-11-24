# 重开权限无工号账号兼容 Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans and superpowers:test-driven-development; run an independent review before delivery.

**Goal:** 让无工号的有效账号可通过既有重开管理员和次数调配白名单获得对应权限。

**Architecture:** 两个名单共用一个身份匹配辅助方法，已启用用户有工号时只匹配工号，工号为空时才匹配登录账号；读权限与每次写入复核继续使用同一服务。

**Tech Stack:** Java 17、Spring JDBC、Spring MVC、H2/JUnit。

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/重开权限无工号账号兼容-系统设计.md`

## Global Constraints

- `admin-emp-nos`、`count-transfer-emp-nos` 名称及独立职责保持，只有工号缺失才回退数据库中的 username。
- 身份及配置去前后空白，空/null配置忽略，精确大小写匹配；已有工号不能通过username额外获权。
- 继续以有效数据库账号为准，未登录、未映射、停用、伪造姓名不授权；不修改用户数据或前端/API契约。
- 保留未知本地修改，按既有授权推送本次代码到GitHub main。

## Review Focus

- null、空串和全空白工号均回退，但不允许“工号或用户名任一命中”。
- 读权限允许后，修改白名单、工号或停用账号仍在写入时重新校验。
- 无工号LDAP账号经过真实Controller/Service/DAO可完成转移，数量及审计对账。
- 用户名精确匹配，不采用显示名、请求中自报身份或无映射principal。

## Task 1: 兼容与回归

- [x] 在权限单测补充无工号、空白规范化、工号优先、两名单隔离、停用和未映射回归；在次数转移Controller测试验证无工号权限响应、实际事务成功和后续身份变化拒绝。
- [x] 运行定向测试，确认新案例因现有工号硬限制失败。
- [x] 最小修改 `ReplayReopenPermissionService` 的共享名单匹配，更新Properties及部署说明的身份语义。
- [x] 定向回归和完整Maven测试通过，独立审查，无数据库迁移及前端改动。

## Task 2: 验证交付

- [x] Java17打包、git diff --check，确认本次差异不包含未知文件。
- [x] 精确提交并推送main，提供更新后的后端源码ZIP（含已有前端构建），校验版本与CRC。

## 验证结果

- RED：权限/Controller共12项中4项按预期失败（无工号用户名未命中以及首尾空白未规范化），0执行错误。
- GREEN：5个重开权限及转移Controller/Service测试类共50项全部通过。模拟LDAP型启用账号的emp_no为空，用户名白名单令权限响应canTransferCounts=true；真实Controller/Service/DAO转移成功且生成审计，后续补上未授权工号时，查询权限与同UUID重试均拒绝。
- Java17完整`mvn -q package`通过；175份测试报告、1611项，0失败、0错误、6跳过。前端源码未变，e401985重新构建到backend/static，随后端包集成。
- 独立审查无可操作问题，git diff --check通过。权限服务继续通过数据库有效账号校验身份，并保留写入前/事务内的权限复核。
- 无数据库迁移、不改用户数据、不改前端或接口字段；仅兼容两个既有显式白名单的身份选择。部署说明已同步，更新后端后重启实例并重新打开台账。
- Obsidian本次设计三件套及关联文档已安全推送main：5af3028b8cd33561cc16f30213b399d34325d58c；原未知知识库修改均保留。源码交付包采用已提交源码及最新static，排除本地配置、V81副本和旧归档，并校验清单/字节/CRC。
