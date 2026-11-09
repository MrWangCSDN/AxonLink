# 日报周报新旧比率算法兼容实施计划

**Goal:** 历史数据缺少新增字段或回算证据时仍输出成功率、比对通过率；完整新数据保留字段级审核接受及分母扣量逻辑。

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/并行回放问题清单-系统设计.md`。

**Architecture:** 共享 SideLoader 保留各端原始汇总、接口明细和问题统计可用性，并按领域标记 legacyRateDomains。计算器选择原算法或完整拆分算法，在内部保存每域实际分母；Excel、JSON、邮件共享同一计算结果。

**Constraints:** 不借用其他批次影响笔数；不补造历史审核；缺少A时使用本批原始 D-K 基数而J仍未知；比例合计先汇总精确分子分母再舍入；只捕获历史快照专用缺失异常；无新SQL。

### 1. 共享数据与口径选择

- [x] 新建 ReplayReportCalculationDataLoader 与真实数据库回归，接口 loadHistorical / loadLatest 返回 SideData。
- [x] RED：旧split/null、零split、缺历史/原量/DETAIL、SQL异常；实现后GREEN。

### 2. 计算和展示兼容

- [x] Side新增可序列化legacyRateDomains，保留旧4参构造及旧JSON默认值。
- [x] RED：旧infer round/clamp、已知A仍扣、未知A使用D-K且J空率有值、旧新混合总率不平均领域率。
- [x] 计算器9参重载接context并保存每域实际分母；factory/writer只为有可靠旧率的域解除L/M遮罩。

### 3. 两种报表接入

- [x] 日报保留真实上一批的原始两表，不再因历史问题快照缺失隐藏旧比率；只有无上一批时使用统计起点。
- [x] 周报保留起止批次和缓存/重新生成行为，切换共享loader并使用两端独立口径。
- [x] 更新前端说明及相关回归，明确有新数据走新算法、历史数据沿用原算法。

### 4. 验证与交付

- [x] 相关后端与前端回归、独立审查，记录已确认的无关基线失败。
- [x] SHA保护同步原工程，构建前端至后端、生成新源码ZIP并逐文件验证。

执行证据：设计f6a7797已推送至Obsidian PR #3；新API回归先RED，首轮55项仅两个旧展示定位断言失败并已修正；最终26类304项后端及158项前端回归通过，独立审查无未解决发现。
