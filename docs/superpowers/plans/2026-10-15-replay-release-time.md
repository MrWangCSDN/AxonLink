# 回放导入发版时间保护 Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans inline, preserving existing uncommitted changes.

**Goal:** 两类问题清单导入可填写秒级发版时间，保护晚于发版时间保存的修复待验证问题。
**Architecture:** 共享时间解析策略与前端批次默认值工具；保留原导入重载，默认值后端兜底。
**Tech Stack:** Java17/Spring JDBC/Vue3/Vitest/POI。
**Spec:** /Users/java/obsidian/01 Engineering/axon-link-server/回放导入发版时间保护-系统设计.md

## Global Constraints

- 最近人工保存时间继续作为判断依据；自动已修复逻辑不调整。
- 默认值为批次日期前一天12:00:00，不使用系统当天。
- 不覆盖工作区已有改动，不向源码仓库另存设计。

## Review Focus

- 秒级相等可验证，晚一秒受保护。
- 手填覆盖不被异步默认值覆盖；切换文件不串数据。
- 请求省略兼容，空白/非法日期返回400且不写入。
- 月初/年初/闰日取前一天正确。
- 旧版回放消失自动已修复仍保持现状。

## Tasks

- [x] 1. 后端时间策略与合并：先增加边界回归测试并确认失败；实现ReplayReleaseTime及两类导入service/controller参数贯通；运行时间策略、两类merge/import/controller测试。
- [x] 2. 前端默认值与表单：先增加批次识别、手改、multipart测试并确认失败；实现共享Excel批次日期推导、两页datetime-local字段与校验；运行两页与API测试。
- [x] 3. 验证交付：检查改动、构建前端至后端static、运行后端相关回归；独立核对业务规则；设计文档只提交推送本次Wiki分支，不混入既有修改。

## Verification record

- 后端7类193项通过：/tmp/replay-release-backend-green.log。
- 前端5文件197项通过：/tmp/replay-release-frontend-green.log；排除历史.worktrees中的旧测试副本。
- 前端构建成功并写入后端static：/tmp/replay-release-build.log。
- 独立只读审查未发现重要缺陷；原始复现先失败再通过，保留人工保存时间和自动已修复规则。
- Wiki设计及现行模块补充已推送到codex/replay-release-time-20261015，提交9366cda。
- 源码保留在当前工作区，本次未提交或推送前后端既有混合改动。
