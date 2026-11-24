# 数据库比对命名脚本配置 Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans / test-driven-development, delegate independent UI and bd importer tasks, and review the integrated change before delivery.

**Goal:** 命名表SQL方案、tables Excel导入、全表/单表忽略、主键保护、最大hash上限及bd导入兼容。

**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/数据库比对命名脚本配置-系统设计.md` 及配套模型/API。

**Architecture:** 独立配置表和稳定id/revision，不绑定不可变登记版本。保存时批量BASE元数据校验并冻结GZIP SQL与SHA；下载直接读取已保存内容。新方案只输出NEW14列，内置LEGACY/NEW原格式保持；bd区分显式max与旧格式缺失值。

**Tech Stack:** Java17/Spring JDBC/POI/JUnit/MySQL/H2，Vue3/Vitest；bd Java服务现有本地测试profile。

## Global Constraints

- 全局忽略在一套方案内应用所有表，命中主键自动保留（用户已确认）；单表显式忽略主键或未知字段422。
- 必须存在完整真实主键，不能回退唯一索引；忽略后SELECT与字段配置同步，位置连续、主键顺序完整。
- BCOMP_MAX_PARTITION_NUM默认64、整数1..256，不限定2幂、不复用PARTITION_NUM。
- 新表V95；命名方案维护沿用SQL生成的有效登录身份，不写登记分区属性、不自动执行脚本。
- 用户已确认必须通过bd的Axon导入器消费；新显式cap生效，旧格式继续恢复旧cap。
- 保留未知本地改动；设计仅在Obsidian；按既有授权推送主分支并交付源码包。

## Review Focus

- Excel缺列/空行/重复表/非法数字及元数据失败均整批拒绝，错误可定位。
- 全局规则与单表覆盖、复合主键、多表顺序、PK被全局忽略时的保护。
- CAS冲突、重复名称、冻结下载、损坏脚本校验及不同方案/旧内置目标隔离。
- 前端无历史版本仍能维护方案；过期响应、保存忙锁、profile选择不得静默走LEGACY。
- bd新14列显式上限与混合旧格式、回滚、旧cap恢复不互相覆盖。

## Tasks

- [x] 后端独立DTO/DAO/迁移及CRUD/CAS/冻结下载/身份检查回归。
- [x] Excel解析、BASE全列和真实PK校验、忽略规则与新14列生成器回归；旧生成器输出保持。
- [x] 前端命名方案管理/导入预览/字段忽略/下拉与下载路由，组件和API回归。
- [x] bd导入parser/事务恢复策略兼容，旧格式及新显式cap真实测试。
- [x] Axon与bd脚本交叉验证，完整测试、前端构建至后端、Java17打包及独立审查。
- [ ] 精确提交推送相关主分支，源码ZIP校验；保留未知本地改动。

## Final Verification

- 前端当前源码：39个文件、787项测试通过；生产构建已写入后端static，共233个文件。真实浏览器覆盖导入、全局/单表忽略、主键保护、保存、下拉下载、文件重选和移动布局。
- Axon Java17完整package通过：179份报告、1640项测试，0失败/0错误，6项跳过。打包校验清理target中旧前端残留后重新装包，JAR与源码static精确清单及逐字节一致。
- bd完整服务：214项，0失败/0错误，1项既有外部Gauss探针跳过；配置API50/50。Spark3.5.3测试85/85。服务使用local-servlet-tests测试profile，不代表生产BES打包验证。
- Axon真实生成SQL通过bd Controller→Service→Repository在H2和隔离原生MySQL导入，最大分区100/256覆盖旧值，中文/引号/反斜杠/换行和主键标志/两侧SQL一致，坏cap整批回滚。隔离MySQL已清理，未连接生产库或真实Gauss。
- 交叉脚本SHA256：`9e93cc43754073ae92df9ff3b7ca5518d1758b3dc4c52f41195a09493b95ad3d`。
- 独立复审已确认：原生文件输入重置、Excel原始数值校验、NUL拒绝与UTF8/JDBC文本保真、下载revision必填正整数和过期冲突；无遗留发现。
- 正式设计已推送Obsidian main：`4a2ab48b94355f15876c620359e0e7d3f323cb63`。
- bd兼容补丁HEAD：`2b554d919a20f5c7a81a168fbccf24019951aa25`；包含最大HASH上限及日期模式保存依赖，排除无关Spark连接改动。
