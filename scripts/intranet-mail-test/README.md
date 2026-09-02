# 协同人邮件发送功能 · 内网测试指南

> 适用：将后端源码 zip 解压到内网 Linux 后，联调验证「回放问题协同人邮件发送」功能。

---

## 一、交付物

| 文件 | 说明 |
|---|---|
| `axon-link-server-intranet-test-*.zip` | 后端源码包（含已打包的前端页面 + V46 建表 SQL） |
| `scripts/intranet-mail-test/01-setup-mail-table.sql` | 建表脚本 |
| `scripts/intranet-mail-test/02-start-intranet.sh` | 内网启动脚本（含 SMTP/数据源环境变量） |
| 本文档 | 测试步骤 |

前端已用 `VITE_USE_MOCK=0` 打包进 `src/main/resources/static/`，zip 解压后直接构建即可得到含页面的 jar。

---

## 二、内网环境前置要求

1. **JDK 17**（`java -version` 确认）
2. **Maven 3.6+**（`mvn -version` 确认）
3. **可访问**：
   - 内网 MySQL（结果库 benchmarkdb，默认 `21.64.203.16:3306`）
   - 内网 SMTP 服务器
   - Maven 依赖仓库（首次构建拉依赖；若内网无外网需配置内网 Nexus 镜像）

---

## 三、部署步骤

### 步骤 1：解压源码包

```bash
cd /home/cbs   # 示例部署目录
unzip axon-link-server-intranet-test-20260817-1514.zip -d axon-link-server
cd axon-link-server
```

### 步骤 2：建表（结果库 benchmarkdb）

```bash
mysql -h 21.64.203.16 -u benchmark -pbenchmark123 benchmarkdb \
  < scripts/intranet-mail-test/01-setup-mail-table.sql
```

验证建表成功：

```sql
SHOW TABLES LIKE 'dii_replay_issue_mail';
```

### 步骤 3：预置测试用户邮箱（可选但建议）

邮件收件人邮箱取 `ccbs_ai_sys_user.email`，缺省回退 `<username>@spdbdev.com`。
若要发到真实邮箱，更新用户表：

```sql
-- 协同人账号（前端"需协同人"填写的 username）
UPDATE ccbs_ai_sys_user SET email = '你的真实邮箱@spdb.com' WHERE username = '<协同人账号>';
-- 开发负责人 / 科技负责人（来自 dii_replay_transaction_person 的人员清单）
UPDATE ccbs_ai_sys_user SET email = '开发负责人邮箱@spdb.com' WHERE username = '<开发负责人账号>';
```

### 步骤 4：配置并启动

编辑 `scripts/intranet-mail-test/02-start-intranet.sh` 顶部的 SMTP 段，改成内网真实 SMTP：

```bash
export MAIL_HOST="smtp.spdb.com"      # 内网 SMTP 服务器
export MAIL_PORT="25"                  # 25 / 465 / 587
export MAIL_USERNAME=""                # 无需认证可留空
export MAIL_PASSWORD=""                # 无需认证可留空
export MAIL_FROM="noreply@spdb.com"    # 发件人
```

然后执行：

```bash
bash scripts/intranet-mail-test/02-start-intranet.sh
```

> 首次执行会自动 `mvn clean package -DskipTests` 构建 jar（耗时较长）。
> 也可先手动 `mvn clean package -DskipTests`，再运行启动脚本。

### 步骤 5：访问页面

浏览器打开 `http://<内网服务器IP>:8123/`

---

## 四、功能测试步骤

1. 进入「回放问题」页面，找到一条已有问题（或导入/新建一条）。
2. 编辑该问题，在「需协同人」字段填写协同人账号（对应 `ccbs_ai_sys_user.username`）。
3. 保存后点击「发送协同邮件」按钮。
4. 观察页面反馈：
   - 成功 → 收件人状态显示「已发送 SENT」，且邮箱收到邮件。
   - 失败 → 显示具体失败原因（如 SMTP 不可达、发件人未配置）。
5. 验证去重：对同一问题再次发送（内容未变）→ 应提示已发送，不重复发。

### 关键数据流

```
前端点击发送
  → POST /api/ai/replay/issues/{id}/mail/request-send
  → ReplayIssueMailService.requestSend()
  → resolveRecipients(): 协同人 + 开发负责人 + 科技负责人（去重）
  → MailService.sendTextSync() → JavaMailSender → SMTP
  → ReplayIssueMailDao 写入 dii_replay_issue_mail（SENT/FAILED）
```

---

## 五、排错清单

| 现象 | 原因 | 解决 |
|---|---|---|
| 发件人状态「系统发件邮箱未配置」 | `spring.mail.username` 和 `axon-link.mail.from` 均为空 | 配置 `MAIL_FROM` 或 `MAIL_USERNAME` |
| 邮件发送失败：Connection refused | SMTP host/port 不通 | 检查 `MAIL_HOST/MAIL_PORT`，内网防火墙 |
| SSL 握手失败 | 25 端口走了 SSL 或 465 没走 SSL | 按端口对照调整 ssl/starttls（脚本已处理 25） |
| 收不到协同人邮件 | `ccbs_ai_sys_user` 无该 username 或 email 空 | 检查步骤 3，或确认回退邮箱 `<username>@spdbdev.com` 是否有效 |
| 无开发/科技负责人收件人 | `dii_replay_transaction_person` 无该交易码 | 确认人员清单已导入（V40 数据） |
| 启动报 datasource 错误 | 结果库地址/账号错 | 检查 `DII_RESULT_URL/USERNAME/PASSWORD` |
| 页面白屏 | 前端未打进 jar | 确认用 zip 内 static 构建（已含前端） |

### 日志查看

邮件发送日志前缀 `[mail]`，成功/失败都会打日志：

```bash
tail -f logs/axon-link-server.log | grep -E '\[mail\]|ReplayIssueMail'
```

---

## 六、SMTP 端口对照

| 端口 | 协议 | ssl.enable | starttls.enable |
|---|---|---|---|
| 465 | SMTPS（隐式 SSL） | true | false |
| 587 | SMTP + STARTTLS | false | true |
| 25 | SMTP（内网中继常用） | false | true |

`02-start-intranet.sh` 对 `MAIL_PORT=25` 已自动追加关 SSL 开 STARTTLS 的 JVM 参数。
