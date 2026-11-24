# 登记最大hash分区数升级

## 升级步骤

1. 在Axon结果库人工执行 `src/main/resources/db/daoindex/V96__replay_db_compare_max_partition_num.sql`，给登记表和版本表各增加 `max_partition_num`。已经执行过该迁移时不要重复执行ALTER。历史登记和历史快照默认64。
2. 更新前后端。源码交付包的后端 `src/main/resources/static/` 已包含本次前端构建。
3. bd需使用支持14列Axon导入的版本（`2b554d9`或后续兼容版本）。如果元库还没有 `TSS_BCOMP_CONF_NEW.BCOMP_MAX_PARTITION_NUM`，按元库类型执行bd的 `sql/add_bcomp_max_partition_num_new.sql` 或 `sql/add_bcomp_max_partition_num_gdb.sql`；已完成的ALTER不重复执行。

应用不会自动执行这些SQL，本次验证未访问生产库。

## 使用

普通表登记与自定义SQL登记均可维护“最大hash分区数”，默认64，支持1～256的整数，包括100。它是bd自动计算HASH分区数的上限，与读取分区数和样本数分别保存。登记维护人员可以编辑该字段，样本数和读取分区数继续使用原权限。

新建未传或传null使用64；编辑、重新登记和自定义SQL更新未传或传null时保留原值。初始化Excel格式没有新增必填列：新登记64，已有登记合并时保留原值。

登记列表、详情和版本明细展示该值，列表支持表头精确多选筛选。版本“导出明细”在查询条件后增加最大hash分区数列，并按每个比对项的字段行合并单元格。

编辑登记后需要生成新的登记版本，再选择新版本下载配置脚本。历史版本使用自己的不可变快照；历史快照此次迁移补64，不会用当前登记反向覆盖旧版本。

“SQL配置版本”默认选择“新版本（_new表）”，旧版本（原表）选项已移除，命名配置继续可用。内置新版本与命名方案的CONF都输出14列，末尾为 `bcomp_max_partition_num`，通过bd“Axon配置脚本导入”上传即可。命名配置有自己的上限和修订，与登记快照相互独立。

## 验证范围

覆盖普通/SQL新增、编辑、重新登记、严格整数校验、精确筛选及候选自排除、初始化合并和元数据同步保留、审计/CAS、配置指纹、快照与实时登记隔离、新版14列、版本Excel合并与长SQL链接。

真实生成的内置NEW脚本在H2和隔离MySQL经bd Controller→Service→Repository导入，三项上限100/256/64覆盖旧值；字段位置、主键标志、中文、引号、反斜杠、换行及两侧自定义SQL逐字核验；非法上限返回400并完整回滚。未验证生产BES打包或真实GaussDB。
