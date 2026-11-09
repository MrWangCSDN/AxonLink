# 日报历史边界与统计起点 Implementation Plan

> **For agentic workers:** Root owns backend; the frontend agent owns only ReplayIssuePage.vue/spec.js in the existing isolated frontend. Do not run Maven concurrently. Preserve original workspace with recorded SHA guards.

**Goal:** 当前批次完整时，上一批不存在或历史快照不完整也能生成仅本批日报，停止逐日补旧历史。

**Architecture:** 日报先严格读取当前批次，仅对上一批的专用历史不完整异常降为统计起点。空previous输入由展示层明确标为未知：上区提示、不写零合计，下区14–20及summary JSON为null。首批可选可生成，周报保持两端严格。

**Spec:** Obsidian并行回放问题清单三件套中的日报统计起点规则；用户已选择仅算当前批次建立起点。PR3提交fed62a1，canonical已安全同步。

## Constraints / Review Focus

- 当前批次自身不完整仍报错；SQL/一般运行错误不能被当作缺历史吞掉。
- 不调用更早批次、不先生成上一份日报、不拿旧报表或current问题伪造历史。
- 仅DAILY空previousRows且previousTotal.batchNo为空时，将上一批七项统计设null；合计也为空，Writer不能numberOrZero。
- 基线Excel上区明确“历史统计未建立”，当前分子/分母/J列保持真实值；下一批直接用本批V88快照恢复比较。
- 首批入口解除previousNo门槛，UI说明并标记“统计起点”；无新接口、数据库字段或迁移。
- 缓存报告下载保持，重新生成才应用新版；WEEKLY仍要求选定两端完整。

## Tasks

- [x] 代码调查确认非递归，而是旧历史强校验造成操作追溯；用户确认起点方案，设计先行。
- [x] RED/GREEN：缺上一批或无同族上批可以生成；当前缺口与非历史错误仍失败。
- [x] 验证Excel上区无零合计，下区14–20和JSON/合计未知为空，下一批不依赖更早或上一份日报文件。
- [x] 首批列表可生成，前端说明/起点标签及页面回归。
- [x] 相关日报/周报/展示/HTTP/邮件回归和独立复核，采用受保护同步与现有构建打包脚本交付。

## Evidence

- 基线为393cc31；前一轮202项相关回归已通过，完整检查既有数据库问题清单40项失败已确认不由本次引入，不重复修复该模块。
- 后端6项RED均失败且无运行错误：首批409、列表禁用、prev缺历史阻塞、current缺口被无prev错误遮蔽、未知JSON误写0。GREEN八类155项全部通过，失败/错误/跳过均为0。
- 前端2项预期RED后，整ReplayIssuePage.spec.js共158项通过。
- 独立复核发现null写成STRING空串会使LegacySummaryExtractor回读失败；现局部写真正BLANK并以生成XLSX实际回读验证，JSON/Excel/邮件兼容路径均保持未知为空。其余review无可行动缺陷，diff检查通过。
