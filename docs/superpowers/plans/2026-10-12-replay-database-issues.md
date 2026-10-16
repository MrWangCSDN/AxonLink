# 回放数据库问题清单实施计划

> 使用 executing-plans 在当前已选定的三个工程工作区顺序实施；保留既有未提交修改。按任务执行测试与验证。

**Goal:** 导入BD表明细/表字段明细，在独立问题清单按批次继承人工内容并跟踪状态。
**Architecture:** POI解析器负责验证和生成issue_id；结果库DAO管理当前/批次/历史，服务负责状态和人工字段；Vue页面与原清单同风格；BD报表末列增加稳定日批次。
**Tech Stack:** Java17、Spring/JdbcTemplate、MySQL/H2、Apache POI、Vue3/Vitest。
**Spec:** `/Users/java/obsidian/01 Engineering/axon-link-server/回放数据库问题清单-系统设计.md`

## 约束与复核重点

- 问题级别只有仅ccbs/仅528/字段级；人工问题类型保留需规问题。
- 不改变核对统计；不合并来源不同的重复issue_id。
- 批次DByyyyMMdd-01，允许旧文件补填；混批次或字段缺失整次拒绝。
- 首次新建/后续状态翻转与原问题清单一致；仅本次报表包含的成功表允许自动修复。
- 保存原始值和前后快照；并发写入串行，编辑版本冲突拒绝。
- 不自动提交或推送工程业务代码；知识库按其既有Git规范同步。

## Task 1: BD工作簿批次列

文件：DailyResultExportService.java、DailyResultWorkbookWriter.java、DailyResultWorkbookWriterTest.java、DailyResultApiTest.java。
- [x] 先测导出报表三Sheet最后一列均为批次号，值DB20261012-01。
- [x] 新增write(data,batchNo)，原单参数接口兼容已有调用，服务从日期生成批次。
- [x] 核对AutoFilter区域、空Sheet表头与字段计数不变，运行BD定向测试。

## Task 2: 双Sheet解析与持久化/状态

新建 replay/dbissue 包（模型、Excel解析器、DAO、服务）及V77建表脚本；抽取统一问题类型目录供两清单复用。
- [x] Excel回归先失败：旧新表头、列顺序、三类issue_id、零差异、数量/批次/重复ID拒绝。
- [x] 实现解析为ParsedWorkbook，包含零差异成功表范围和批次日期。
- [x] H2服务测试先失败：新增、继承、待验证按日期重新打开、消失修复、已修复再新建、同批次重导、部分表范围、过期导入、并发版本和事务回滚。
- [x] DAO建表/current/batch/history/write-lock；保存快照；服务补充元数据并复用状态枚举/审核权限。
- [x] 手工编辑复用类型限制、协同人查找、500字符、历史审核规则。
- [x] 原交易清单测试加新模块测试全部通过。

## Task 3: API与页面

新建 ReplayDatabaseIssueController.java、replayDatabaseIssues.js、ReplayDatabaseIssuePage.vue；修改ChainImpactSidebar.vue及TransactionAnalysis.vue。
- [x] API测试：认证、错误响应、筛选分页、导入参数、PATCH版本、审核、导出、跟踪。
- [x] 页面测试先失败：菜单缩进顺序、表列、导入反馈、级别/类型独立筛选、保存数据、协同人选定、版本错误与跟踪。
- [x] 实现工具栏/统计条/查询区/横向滚动表/分页/编辑弹窗/批次抽屉及导出。
- [x] 用现有mock或临时测试服务器进行浏览器布局和导入编辑跟踪验证。

## Task 4: 验证与交付

- [x] 前端相关测试、后端全套测试、BD工作簿与API测试；核对最终代码差异。
- [x] 前端真实后端模式构建到后端static。
- [x] 交付V77初始化脚本路径、BD批次规则及验证结果；不执行真实数据库导入。

## 验证结果（2026-10-12）

- 后端全套：902项，900通过、2跳过、0失败；新模块16项加MVC 2项通过。
- 前端全套：27文件、484项通过；真实后端模式构建至static通过。
- BD定向：工作簿8项、接口17项通过，实际写出器生成的工作簿通过Axon解析契约。
- 浏览器：1440×900列表、390×844编辑和批次抽屉；页面无横向溢出，表格可独立横向滚动。使用临时测试响应验证布局，真实请求契约由MVC测试验证。
- 独立代码审查发现的明显字段截断误修复和补充审核原因改修复日期均已增加回归并修复。
- 结果库部署需手工执行V77建表脚本；未修改真实数据库。

## Task 5: 展示关联的科技负责人

**Goal:** 在责任人（修订人）后展示当前字段登记的科技负责人，Excel保持相同顺序。
**Spec:** 现有Obsidian系统设计“列布局与出现批次”、API接口“列表与导出筛选”。

- [x] Vue新增technologyOwnerDisplay列（180px），复用当前人员读投影与缺失/歧义提示。
- [x] 后端导出改用service.list(criteria, null)读取每页，插入“科技负责人”，继续按原过滤条件导出全部数据。
- [x] Mock导出复用withOccurrences人员投影；维护现有列布局及导出断言，验证换负责人后刷新反映当前登记。
- [x] 运行前端页面/Mock及后端数据库问题专项测试，浏览器核对列顺序和显示，构建前端到后端static，两仓diff --check。

边界：不新增人员持久化字段/迁移，不增加独立筛选，不改审核授权和人工字段。

验证：前端2文件52项、后端数据库问题专项40项通过；Mock浏览器确认列顺序与姓名(工号)，前端真实后端模式构建通过。只读复查无阻断发现。Wiki设计提交cdf6478已推送，业务代码未提交。

## Task 6: 差异笔数排序

**Goal:** 将“差异数”展示标签改为“差异笔数”，以原问题清单的三态排序按钮替代该列筛选。
**Spec:** 现有Obsidian系统设计“差异笔数排序”与API接口differenceCountOrder契约。

- [x] Vue默认→ASC→DESC→默认循环；排序回第一页且保留其他筛选，重置清除排序；导出传递相同differenceCountOrder。
- [x] DAO白名单解析differenceCountOrder（空、ASC、DESC，trim/大小写规范化），非法400；SQL按difference_count数值及id DESC排序后分页，导出同路径。
- [x] 列表/跟踪/导出标签改名，移除页面该列筛选，保留legacy differenceCounts API兼容及BD原导入表头。
- [x] 验证排序前分页、数字顺序、同值稳定次序、筛选与导出一致、非法值；浏览器验证三态按钮；真实后端模式build和两仓diff --check。

无需新增数据库迁移；不改变负责人关联和审核权限。

验证：新增排序测试确认修改前失败；前端3文件57项、后端数据库问题专项42项通过。浏览器检查实际笔数升序与降序，并恢复默认；前端构建至后端static通过。只读复查无阻断发现。Wiki设计提交a79164b已推送，业务代码未提交。

## Task 7: 缺陷修复日期筛选

**Goal:** 缺陷修复日期列复用表头多选筛选，可选择空白与日期，列表、候选及导出覆盖完整查询结果。
**Spec:** 现有Obsidian系统设计及API的defectRepairDates、数据模型V79投影契约。

- [x] Vue/Mock增加defectRepairDate→defectRepairDates映射，保持同列OR、其他列AND与排序、导出兼容。
- [x] V79新增defect_repair_date字符串SQL投影，valid JSON历史回填；DAO全部insert/update同步，读投影覆盖及标准HeaderFilter加入；不改业务日期逻辑。
- [x] 验证空白/日期组合、其他列叠加、候选排除自身/搜索/全量计数、分页与导出、历史回填、日期状态变化同步。
- [x] 浏览器确认日期筛选箭头与弹窗，构建前端到后端static，定向前后端测试与diff --check。

部署须在结果库已有V77、V78基础上人工执行V79一次；不修改旧迁移或生产数据库。

验证：新增筛选测试修改前失败；前端3文件59项、后端日期/审核/清单专项44项通过。浏览器候选包括空白与三种日期、总计16条，选择2026-10-11得到2条；真实后端模式构建成功。只读窄审无阻断发现。Wiki940a484已推送，业务代码未提交。

## Task 8: 需协同人筛选

**Goal:** 需协同人列复用表头多选筛选，支持完整姓名(账号)、空白及姓名/账号候选搜索，列表与导出条件一致。
**Spec:** 既有Obsidian系统设计、API的cooperationPersons及数据模型V80显示投影契约。

- [x] Vue/Mock增加cooperationPerson→cooperationPersons映射，保留同列OR、跨列AND及已有排序，候选排除本列条件。
- [x] V80新增cooperation_person_display投影并从合法JSON回填，DAO insert/update及列表读取同步，快照/历史及选人身份逻辑保持。
- [x] 验证完整显示值与空白、姓名/账号搜索、全量计数、跨列组合、分页/排序/导出及更换/清空同步。
- [x] 浏览器验证需协同人筛选，构建至后端static，完成定向测试、窄审及两仓diff --check。

部署在结果库已有V77/V78/V79基础上手工执行V80一次；本轮不操作真实数据库，不提交业务代码。

验证：新增筛选回归确认修改前失败；前端3文件61项、后端清单/审核/投影专项47项通过。浏览器全量候选4种，账号SUNHY1搜索命中3条，组合选中孙海英与空白匹配10条；前端真实后端模式构建成功。只读窄审无阻断发现。Wiki146bb79已推送，业务代码未提交。

## Task 9: 领域与人员统一关联字段登记

**Goal:** 领域、责任人（修订人）、科技负责人统一来自英文表名唯一匹配的当前有效登记，忽略Excel领域与负责人。
**Spec:** 既有Obsidian三件套的登记关联契约；报表不含schema，因此不借Excel领域消歧。
**Files:** ReplayDatabaseIssueDao.java、ReplayDatabaseIssueService.java、ReplayDatabaseIssueExcelParser.java、ReplayDatabaseIssueTest.java、ReplayDatabaseIssueControllerTest.java；Mock及其测试；Vue导入说明。

- [x] 设计先落盘，明确动态SQL关联/同源筛选、无登记与同名歧义、导入忽略值、历史不重写和审核身份规则。
- [x] 后端先测Excel来源冲突/缺列无影响、登记变化即时刷新/全量筛选导出、删除与歧义不回退不倍增、科技审核仍只授权同一唯一登记负责人，再实现。
- [x] Mock以独立登记来源动态映射三列，筛选/候选/options/导出同源，验证登记变化、缺失/歧义及授权；前端补充导入说明。
- [x] 定向前后端测试、浏览器、只读复核及两仓diff --check；真实后端模式构建至static。

Ruling: 使用当前有效登记的动态查询关联而非一次性复制，以便既有问题无需重导就能反映登记修改；无匹配或多匹配时domain/owner为空白，科技负责人提示并不授权。本轮无需新增迁移，旧历史快照保留。

验证：前端3文件66项、后端清单及三类投影专项51项通过（0错误/失败），新行为与伪表头/自动修复快照边界均先红后绿。确认三列当前登记映射、筛选候选/导出/单条返回一致、登记变化与权限变动同步、旧历史及版本不因只读变更。浏览器核对三列与导入说明，前端构建至后端static成功，两仓diff --check通过，窄审无剩余阻断。Wiki设计80cd19f已推送；业务代码未提交、数据库未操作。
