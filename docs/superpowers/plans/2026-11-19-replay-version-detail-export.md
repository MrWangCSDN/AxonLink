# 数据库比对版本明细 Excel 导出实施计划

**目标：** 版本历史新增“导出明细”，下载当前选中版本、已应用筛选下的全部表和比对字段，每字段一行，同表信息合并。

**设计来源：** `/Users/java/obsidian/01 Engineering/axon-link-server/回放数据库比对版本明细导出-系统设计.md`。

**约束：** 复用现有快照查询和前端 `xlsx`；六列为领域、表英文名 / 中文名、比对字段、选择索引、查询条件、科技负责人；不新增后端接口、依赖或数据库迁移。

## 任务

1. 在前端 `ReplayDatabaseComparisonVersionHistory.spec.js` 增加按钮、已应用筛选、加载、错误和切换版本测试，先确认新增行为失败。提取现有五个展示函数至 `versionSnapshotFormatters.js`，供界面和 Excel 共同使用。按钮调用 `exportVersionDetails(versionNo, criteria)`，点击时固定版本与筛选。
2. 在 `versionDetailWorkbook.spec.js` 使用真实 XLSX 编解码验证六列表头、字段顺序、合并范围、跨页数据、旧快照、自定义 SQL 和字符串单元格。使用网络及文件下载替身，验证空结果或中途失败不下载部分文件。先验证失败，再实现 `versionDetailWorkbook.js`：每页 200 条读取所选版本全部匹配表，忽略输入页码；按比对顺序展开字段，只在同表范围合并 A/B/D/E/F，导出 `replay-db-compare-details-{versionNo}.xlsx`。
3. 运行新增测试与现有版本历史测试，独立审查变更；运行前端完整测试，执行 `VITE_USE_MOCK=0 npm run build`，确认资源更新到后端 `src/main/resources/static`，检查 diff。

## 审查重点

- 筛选必须是已应用筛选，不能读入未提交的输入或只导出当前页。
- 导出期间切换版本或修改数组筛选，已开始的请求与文件名保持原版本及筛选。
- 字段顺序、联合键排序与查询条数必须保留；自定义 SQL 的外层条数另行显示。
- 同领域不同表不得合并到一起，单字段表不创建单格合并。
- 所有网络请求成功后才生成文件；空结果和不完整分页明确报错。
- 合法自定义 SQL 可能超过 Excel 单元格 32767 字符上限：仅超限查询增加“查询条件明细”工作表，分段保留全文，主表保留前缀、分段行号和内部链接；验证分段不拆 Unicode 代理对。

## 进度

- [x] 界面与共享展示函数
- [x] Excel 生成与跨页导出
- [x] 审查、验证、真实 API 构建

验证：`npm test -- --exclude '**/.worktrees/**' --reporter=dot` 在当前前端通过 38 个文件、710 项测试；`VITE_USE_MOCK=0 npm run build` 成功并更新后端静态资源。独立审查发现的超长 SQL 问题已有失败重现、修复及真实 XLSX 编解码回归，复审无剩余问题。Playwright 使用真实组件及模拟快照接口在 1280×900 检查按钮，实际下载并重读 XLSX，确认六列、逐字段行及五列合并；控制台无错误或警告。线上真实接口未连接，本次未部署。
