# Quickstart: flowtran 功能开发与验证

## 环境准备

### 外网本地（开发默认）

`application-local.yml` 已配置好，flowtran 表已在 `mall_admin` 建好（V4），测试数据已写入 21 条。

```bash
# 启动服务（flowtran 表在本地 mall_admin）
./build.sh
java -jar target/axon-link-server-1.0.0.jar
```

### 内网切换

修改 `application-local.yml`：
```yaml
spring:
  datasource:
    url: jdbc:mysql://21.64.203.16:3306/benchmarkdb?...
    username: benchmark
    password: benchmark123
flowtran:
  datasource: intranet
```
重启后服务自动执行 V4 在 `benchmarkdb` 建表。

---

## 验证步骤

### 1. 验证领域列表
```bash
curl http://localhost:8123/api/flowtran/domains | python3 -m json.tool
# 期望：dept 领域 txCount=21，其他领域 txCount=0
```

### 2. 验证存款领域交易列表
```bash
curl "http://localhost:8123/api/flowtran/domains/dept/transactions?page=1&size=10"
# 期望：返回 TC0022~TC0099 等 21 条记录
```

### 3. 验证交易链路（需先有 flow_step 数据）
```bash
curl "http://localhost:8123/api/flowtran/transactions/TC0033/chain"
# 如果 flow_step 无数据，返回空 steps 列表
```

### 4. 验证 ServiceNodeCache（需 service/component 表有数据）
```bash
curl http://localhost:8123/api/flowtran/cache/stats
# 期望：loaded=true，显示加载条数
```

### 5. 触发缓存热重载
```bash
curl -X POST http://localhost:8123/api/flowtran/cache/refresh
```

---

## 测试数据补充（flow_step）

如需测试链路展示，在 `mall_admin` 执行：
```sql
INSERT INTO flow_step (flow_id, node_name, node_type, step, node_longname) VALUES
('TC0033', 'IoDpOpenDeptAcctApsSvtp.openDeptAcct', 'service', 1, '对公活期开户服务'),
('TC0033', 'validateAccountInfo', 'method', 2, '账户信息校验'),
('TC0033', 'AgPbccSvtp.checkBlacklist', 'service', 3, '黑名单检查');
```

---

## 新增源码位置

| 文件 | 说明 |
|------|------|
| `config/FlowtranConfig.java` | `flowtran.*` 配置属性绑定 |
| `service/FlowtranService.java` | 接口定义 |
| `service/impl/FlowtranServiceImpl.java` | 业务实现，JdbcTemplate 查询 |
| `service/ServiceNodeCache.java` | 内存缓存，@PostConstruct 加载 |
| `controller/FlowtranController.java` | REST 接口 `/api/flowtran/*` |
| `common/DomainKeyResolver.java` | packagePath → domainKey 静态工具 |
