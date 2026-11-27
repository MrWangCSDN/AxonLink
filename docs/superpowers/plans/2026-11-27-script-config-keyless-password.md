# 命名方案数据库密码免密钥保存实施计划

**Goal:** 用户授权取消部署密钥依赖，新保存密码以明文持久化；仍不向页面、日志、错误和SQL回显。
**Spec:** Obsidian现行《数据库比对命名脚本配置》三件套2026-11-27章节，设计分支codex/named-script-password-plaintext；不在仓库复制设计。
**Architecture:** 复用V97列，用plain:v1:标记新格式；保留原AES实现只用于旧v1读取。已有服务构造器保持兼容，内部密码存储helper统一负责格式写入/读取。保存前校验与CAS保持原流程。

## Constraints and review focus

- V95/V97继续，不新增SQL；password_ciphertext是历史列名，新内容可为实际明文。
- 未配置或非法旧密钥不影响新密码、新格式读取和复用。
- 缺/错旧密钥、坏旧密文及未知格式：422 SCRIPT_CONFIG_PASSWORD_REENTRY_REQUIRED，不写状态；显式新密码可恢复。
- 保留原始Unicode/前后空格，只剥离一次plain:v1:；预览不迁移，CAS成功保存才转换。
- BASE、认证目标匹配、当前修订下载、gzip/SHA、redaction均保持；回退旧AES程序须核对数据兼容。

## Tasks

- [x] 已向用户说明实际明文行为与旧记录兼容；先更新Obsidian现行设计。
- [x] RED：服务/controller回归，覆盖无密钥保存/预览/复用及旧记录恢复、保密投影和失败不变。
- [x] 实现ReplayScriptConfigPasswordStorage.store/read及服务接入；旧配置项注明仅兼容用途。
- [x] GREEN及独立审查，随后完整server verify；现UI兼容原message/422和重试，不改业务前端。
- [ ] 更新repo部署说明与v3交付包、SQL核对/清单；拉取main、提交推送main并核对远端。
- [ ] 文档commit/push/PR集成，源码ZIP解压构建与233前端资源核验，内网由用户部署。

## Verification

- Initial RED41:5 failures3 expected key errors; GREEN44.
- Review RED37:TRACE leakage and NULL saved credential failed as expected; fixed direct PreparedStatement binding and422 routing.
- Targeted GREEN51 (service37/controller11/DAO3),0 failures/errors/skips.
- Full mvn verify with REPORT_MYSQL_REGRESSION=true:1747 tests,1742 passed,5 existing opt-in/AD skips,0 failures/errors.
- UI business code unchanged; existing422 message/retry path and frontend19e1ce0 retained.
