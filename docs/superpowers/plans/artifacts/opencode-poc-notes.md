# opencode PoC 校准结论（2026-07-14）

- 实测版本：**opencode 1.14.40**（npm 全局安装，`opencode serve --port 4096`）
- 方法：本地 mock OpenAI 兼容端点（`scratchpad/mock_openai.py`，8901 端口）驱动真实对话与工具调用；curl 采集 `/event` SSE 流
- 事件样例：见同目录 `opencode-events-sample.jsonl`（11 条真实事件，覆盖连接/用户回显/工具三态/文本增量/终止）

## 校准点 1：端点与请求体 ✅（与计划假设基本一致）

| 端点 | 实测 | 说明 |
|------|------|------|
| `POST /session` | 200 | 响应 `{"id":"ses_...","slug",...,"directory",...}`，**id 字段确认** |
| `POST /session/{id}/prompt_async` | **204** | 异步发消息，结果经事件流回来——**Gateway 选用这个** |
| `POST /session/{id}/prompt` | 200 | 同步存在 |
| `POST /session/{id}/message` | 200 | 同步等完整回复，响应 `{info:{role:"assistant",agent,cost,tokens...},parts:[...]}`（将来「详细设计文档生成」可用） |
| `GET /event` | 200 SSE | `data: {json}` 行 |
| `GET /config` | 200 | 健康检查可用 |
| `GET /doc` | 200 | ⚠️ **1.14.40 的 /doc 只暴露 2 个 paths（/auth、/log），不是完整 spec**——校准以行为测试为准，别依赖 /doc |

请求体（实测被接受）：

```json
{"agent":"axon-deep","model":{"providerID":"poc","modelID":"mock-model"},"parts":[{"type":"text","text":"..."}]}
```

`agent` 字段生效的证据：传 `agent=axon-deep` 时模型收到的工具列表为只读集合（见校准点 4）；不传时响应显示 `"agent":"build"`（默认）。

## 校准点 2：事件结构 ✅（有一处重要差异）

外层统一 `{"id":"evt_...","type":"...","properties":{...}}`。关键事件：

| 事件 type | properties 结构 | 用途 |
|-----------|----------------|------|
| `message.part.delta` | `{sessionID, messageID, partID, field:"text", delta:"增量片段"}` | **文本增量（新发现，Java 侧主消费对象）** |
| `message.part.updated`（part.type=tool） | `part: {type:"tool", tool:"read", callID, state:{status:"pending"→"running"→"completed", input:{...}, output:"..."}}` | 工具时间线 |
| `message.part.updated`（part.type=text） | `part.text` 为**全量快照**；注意**用户消息也会回显**一条 text part.updated | 不用于增量渲染 |
| `session.idle` | `{sessionID}` | **终止信号**（与假设一致） |
| `session.error` | 未实测（mock 无错误场景）；解析需容错 | 错误终止 |
| 其他 | `session.status` / `session.updated` / `session.diff` / `message.updated` / `server.heartbeat` / `server.connected` | 忽略 |

## 校准点 3：text 语义 ❗与调研假设相反

**text 是纯增量**：`message.part.delta` 的 `properties.delta` 直接携带增量片段（实测三条依次为「这是」「一个」「测试响应」）。

**对计划的影响**：
- `TextDeltaTracker`（累积转增量）**删除**，不需要
- Java 协议层只消费 `message.part.delta`（`field=="text"`）作为 delta 事件；`message.part.updated` 仅用于 tool 状态
- 只消费 delta 也天然规避了「用户消息回显」问题（用户输入不产生 delta）

## 校准点 4：权限锁定 ✅（机制比预期更强）

agent 配置 `permission: {bash/edit/write: deny}` 后，**发给模型的 tools 列表在源头就剔除了 deny 的工具**——实测模型收到的完整工具列表：

```
['read', 'glob', 'grep', 'task', 'webfetch', 'todowrite', 'skill']
```

bash/edit/write 完全不可见，模型无法发起调用（比「调用被拒」更强的保证）。

⚠️ **附带发现**：默认工具集还包含 `task`（子代理）、`webfetch`（网络抓取）——生产 `axon-deep` agent 配置建议一并 deny（内网无外网访问；防子代理绕过权限边界），`opencode.json` 的 permission 里显式加 `"task":"deny","webfetch":"deny"`。

## 其他确认

- `opencode.json` 的 `agent.<name>.permission` 键名写法（`"read":"allow"` 等）被 1.14.40 接受，serve 正常启动加载
- mock provider（`@ai-sdk/openai-compatible` + 本地 baseURL）运行正常，provider npm 包安装走了代理
- opencode 首次调用会有一次 `tools(0)` 的内部调用（标题生成类），协议层忽略即可
