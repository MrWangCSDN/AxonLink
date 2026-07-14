# opencode 深度分析接入 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** axon-link-server 新增「深度分析」路径——通过现有 opencode serve（loopback:4096）让模型以只读工具多轮探索代码仓库，前端流式展示分析过程与结果；原单轮路径零改动、可自动降级。

**Architecture:** 前端（TransactionCard 深度分析面板）→ DeepAnalysisController（NDJSON 流，照 `/code-explain/stream` 既有模式）→ DeepAnalysisService（图谱上下文注入 + 降级编排）→ OpencodeGateway（JDK HttpClient 调 opencode REST + SSE 事件流）→ opencode serve → spdb-new-api 网关。协议字段不确定性集中在 OpencodeProtocol 一处，由 Task 0 PoC 校准。

**Tech Stack:** Java 17 / Spring Boot（spring-boot-starter-web, StreamingResponseBody）、JDK `java.net.http.HttpClient`（项目既有模式）、JUnit 5 + Mockito、Vue 3 + markdown-it（前端仓库 `/Users/java/axon-link-frontend`）。

**设计文档（单一来源）:** `/Users/java/obsidian/01 Engineering/axon-link-server/opencode深度分析接入-设计.md`

**前置说明:**
- 阶段 2「现有内网部署核查」（opencode 版本是否支持 agent/permission/format、注册 axon-deep agent）是**人工运维动作**，不在本计划任务内；上线前必须完成。
- 流式响应沿用项目既有 **NDJSON + StreamingResponseBody** 模式（`AnalysisController.streamCodeExplain` 先例），不引入 SseEmitter。
- 本期 `OpencodeGateway` 只做流式方法（YAGNI）；「详细设计文档生成」功能落地时再补同步阻塞方法。

---

### Task 0: opencode 本机 PoC 与协议样例采集

**目的：** 校准三个协议不确定点，产出真实事件样例供 Task 2/3 测试使用。不写产品代码。

**Files:**
- Create: `docs/superpowers/plans/artifacts/opencode-poc-notes.md`（校准结论）
- Create: `docs/superpowers/plans/artifacts/opencode-events-sample.jsonl`（真实事件样例）

**三个校准点：**
1. 发消息端点与请求体：`POST /session/{id}/prompt` 的准确字段名（`agent` / `model.providerID/modelID` / `parts` / `format`），以及异步变体（`prompt_async`）是否存在
2. `GET /event` 事件结构：事件 `type` 取值、`properties` 内字段名、会话终止事件（如 `session.idle`）
3. text 部分语义：`message.part.updated` 的 `text` 是**全量累积**还是**增量片段**（决定 Task 2 的 TextDeltaTracker 保留还是删除）

- [ ] **Step 1: 安装并启动 opencode serve**

```bash
npm i -g opencode-ai@latest
opencode --version
mkdir -p ~/poc-opencode && cd ~/poc-opencode
```

创建 `~/poc-opencode/opencode.json`（`baseURL`/`apiKey` 换成本机可用的 OpenAI 兼容端点；无可用端点时跳过 Step 4-6 的模型相关验证，只做 API 形状确认）：

```json
{
  "$schema": "https://opencode.ai/config.json",
  "model": "poc/gpt-4o-mini",
  "provider": {
    "poc": {
      "npm": "@ai-sdk/openai-compatible",
      "name": "PoC Provider",
      "options": { "baseURL": "https://你的兼容端点/v1", "apiKey": "{env:POC_API_KEY}" },
      "models": { "gpt-4o-mini": { "name": "PoC Model" } }
    }
  },
  "agent": {
    "axon-deep": {
      "mode": "primary",
      "prompt": "你是代码分析助手，只允许使用只读工具探索代码。",
      "permission": {
        "read": "allow", "grep": "allow", "glob": "allow",
        "bash": "deny", "edit": "deny", "write": "deny"
      }
    }
  }
}
```

```bash
cd ~/poc-opencode && POC_API_KEY=xxx opencode serve --port 4096
```

- [ ] **Step 2: 确认端点形状（校准点 1，无需模型）**

```bash
curl -s http://127.0.0.1:4096/doc | python3 -c "import sys,json; d=json.load(sys.stdin); print('\n'.join(sorted(d['paths'].keys())))"
```

Expected: 看到 `/session`、`/session/{id}/prompt`（或近似路径）、`/event`、`/config`。把发消息端点的完整 requestBody schema 摘录进 `opencode-poc-notes.md`：

```bash
curl -s http://127.0.0.1:4096/doc | python3 -m json.tool > /tmp/opencode-openapi.json
# 打开 /tmp/opencode-openapi.json 找到发消息端点的 requestBody，记录 agent/model/parts/format 字段准确写法
```

- [ ] **Step 3: 建会话 + 健康检查（无需模型）**

```bash
curl -s -X POST http://127.0.0.1:4096/session -H 'Content-Type: application/json' -d '{}'
# Expected: {"id":"ses_..."} —— 记录 id 字段名
curl -s http://127.0.0.1:4096/config -o /dev/null -w '%{http_code}\n'
# Expected: 200
```

- [ ] **Step 4: 采集事件流样例（校准点 2/3，需模型）**

终端 A 挂事件流并落盘：

```bash
curl -sN http://127.0.0.1:4096/event | tee /tmp/opencode-events.raw
```

终端 B 发一条会触发工具调用的消息（`SESSION_ID`、字段名按 Step 2 校准结果调整）：

```bash
curl -s -X POST http://127.0.0.1:4096/session/$SESSION_ID/prompt \
  -H 'Content-Type: application/json' \
  -d '{"agent":"axon-deep","model":{"providerID":"poc","modelID":"gpt-4o-mini"},"parts":[{"type":"text","text":"读取当前目录的 opencode.json 并总结内容"}]}'
```

把 `/tmp/opencode-events.raw` 中的代表性事件（text 更新 ×3 连续、tool 事件、终止事件）整理进 `docs/superpowers/plans/artifacts/opencode-events-sample.jsonl`。**对比连续 3 条 text 更新事件判定累积/增量语义**，写入 notes。

- [ ] **Step 5: 权限锁定验证（需模型）**

```bash
curl -s -X POST http://127.0.0.1:4096/session/$SESSION_ID/prompt \
  -H 'Content-Type: application/json' \
  -d '{"agent":"axon-deep","model":{"providerID":"poc","modelID":"gpt-4o-mini"},"parts":[{"type":"text","text":"执行 bash 命令 ls -la 并告诉我结果"}]}'
```

Expected: 事件流中 bash 工具被拒绝（或模型声明无权执行），**不出现**实际命令输出。结论写入 notes。

- [ ] **Step 6: 结构化输出验证（需模型，字段名按 Step 2 校准）**

发同一端点、附 `format` JSON schema（`{"summary": string}` 之类最小 schema），确认响应含校验后 JSON。结论写入 notes。

- [ ] **Step 7: 提交 PoC 产物**

```bash
cd /Users/java/axon-link-server
git add docs/superpowers/plans/artifacts/
git commit -m "docs(opencode): PoC 校准结论与事件样例采集"
```

---

### Task 1: OpencodeProperties 配置类 + 配置段

**Files:**
- Create: `src/main/java/com/axonlink/ai/opencode/OpencodeProperties.java`
- Modify: `src/main/resources/application.yml`（`ai:` 段内、与 `analysis:` 平级新增 `opencode:` 段）

纯 getter/setter 配置类，跟随项目惯例（`AiAnalysisConfig` 同款 `@ConfigurationProperties`，无单测）。

- [ ] **Step 1: 写配置类**

```java
package com.axonlink.ai.opencode;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * opencode serve 接入配置（深度分析路径）。
 *
 * <p>opencode 与本服务同机部署、仅监听 loopback（复用「详细设计文档生成」设计中的现有部署），
 * 详见 Obsidian《opencode深度分析接入-设计》。
 */
@Configuration
@ConfigurationProperties(prefix = "ai.opencode")
public class OpencodeProperties {

    /** 总开关：false 时深度分析入口直接走降级路径（现有单轮）。 */
    private boolean enabled = false;

    /** opencode serve 地址，默认同机 loopback。 */
    private String baseUrl = "http://127.0.0.1:4096";

    /** HTTP Basic 用户名（opencode 默认 "opencode"）。 */
    private String username = "opencode";

    /** HTTP Basic 密码（对应 OPENCODE_SERVER_PASSWORD；为空表示服务端未启鉴权）。 */
    private String password = "";

    /** 深度分析用的自定义 agent 名（需已在 opencode.json 注册，只读工具）。 */
    private String agent = "axon-deep";

    /** 模型 provider id（跟随现有 opencode 部署的 provider 配置）。 */
    private String providerId = "spdb-new-api";

    /** 模型 id。 */
    private String modelId = "glm-47";

    /** 单次深度分析整体超时（秒）——多轮探索耗时远超单轮，默认 5 分钟。 */
    private int timeoutSeconds = 300;

    /** 建连超时（秒）。 */
    private int connectTimeoutSeconds = 5;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getAgent() { return agent; }
    public void setAgent(String agent) { this.agent = agent; }
    public String getProviderId() { return providerId; }
    public void setProviderId(String providerId) { this.providerId = providerId; }
    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public int getConnectTimeoutSeconds() { return connectTimeoutSeconds; }
    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) { this.connectTimeoutSeconds = connectTimeoutSeconds; }
}
```

- [ ] **Step 2: application.yml 增配置段**

在 `ai:` 下、`analysis:` 平级处新增（保持既有 `${ENV:default}` 风格）：

```yaml
  opencode:
    enabled: ${AI_OPENCODE_ENABLED:false}
    base-url: ${AI_OPENCODE_BASE_URL:http://127.0.0.1:4096}
    username: ${AI_OPENCODE_USERNAME:opencode}
    password: ${AI_OPENCODE_PASSWORD:}
    agent: ${AI_OPENCODE_AGENT:axon-deep}
    provider-id: ${AI_OPENCODE_PROVIDER_ID:spdb-new-api}
    model-id: ${AI_OPENCODE_MODEL_ID:glm-47}
    # 深度分析是多轮 agent 探索，整体超时明显长于单轮的 request-timeout-seconds
    timeout-seconds: ${AI_OPENCODE_TIMEOUT_SECONDS:300}
    connect-timeout-seconds: ${AI_OPENCODE_CONNECT_TIMEOUT_SECONDS:5}
```

- [ ] **Step 3: 编译验证**

Run: `cd /Users/java/axon-link-server && mvn -q compile`
Expected: BUILD SUCCESS（无编译错误）

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/axonlink/ai/opencode/OpencodeProperties.java src/main/resources/application.yml
git commit -m "feat(opencode): 深度分析配置骨架 ai.opencode.*"
```

---

### Task 2: OpencodeEvent + OpencodeProtocol（协议解析与请求组装）

**Files:**
- Create: `src/main/java/com/axonlink/ai/opencode/OpencodeEvent.java`
- Create: `src/main/java/com/axonlink/ai/opencode/OpencodeProtocol.java`
- Test: `src/test/java/com/axonlink/ai/opencode/OpencodeProtocolTest.java`

**协议不确定性集中在本类**：下面的事件样例是调研版假设；执行本任务前先看 `docs/superpowers/plans/artifacts/opencode-events-sample.jsonl`（Task 0 产物），**以真实样例替换测试数据**，解析逻辑相应对齐。若 Task 0 判定 text 为增量语义，删除 `TextDeltaTracker`（见 Step 3 说明）。

> **⚠️ Task 0 校准已定稿（2026-07-14，opencode 1.14.40，详见 artifacts/opencode-poc-notes.md）——本任务按以下结论执行，上面调研版样例作废：**
> 1. **text 是纯增量**：消费 `message.part.delta`（`properties: {sessionID, messageID, partID, field:"text", delta:"片段"}`）作为 TEXT 事件；**删除 TextDeltaTracker 及其测试**
> 2. `message.part.updated` 只用于 tool 事件：`properties.part: {type:"tool", tool, callID, state:{status: pending/running/completed, input, output}}`；text 类型的 part.updated 忽略（含用户消息回显）
> 3. 终止事件 `session.idle`（`properties.sessionID`）与假设一致；`session.error` 保留容错解析
> 4. 请求体字段 `{"agent","model":{"providerID","modelID"},"parts":[{"type":"text","text"}]}` 实测被接受，`buildPromptBody` 不变；发送用 `POST /session/{id}/prompt_async`（204）

- [ ] **Step 1: 写失败测试**

```java
package com.axonlink.ai.opencode;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 协议解析/组装单测。
 * 事件样例来自 docs/superpowers/plans/artifacts/opencode-events-sample.jsonl（PoC 实采），
 * 若与下方字符串不一致，以实采为准更新。
 */
class OpencodeProtocolTest {

    private final OpencodeProtocol protocol = new OpencodeProtocol(new ObjectMapper());

    @Test
    void parse_textPartUpdated_asTextEvent() {
        String json = "{\"type\":\"message.part.updated\",\"properties\":{\"part\":"
                + "{\"sessionID\":\"ses_1\",\"type\":\"text\",\"text\":\"你好\"}}}";
        OpencodeEvent e = protocol.parseEvent(json);
        assertEquals(OpencodeEvent.Kind.TEXT, e.getKind());
        assertEquals("ses_1", e.getSessionId());
        assertEquals("你好", e.getText());
        assertFalse(e.isTerminal());
    }

    @Test
    void parse_toolPartUpdated_asToolEvent() {
        String json = "{\"type\":\"message.part.updated\",\"properties\":{\"part\":"
                + "{\"sessionID\":\"ses_1\",\"type\":\"tool\",\"tool\":\"read\","
                + "\"state\":{\"status\":\"running\"}}}}";
        OpencodeEvent e = protocol.parseEvent(json);
        assertEquals(OpencodeEvent.Kind.TOOL, e.getKind());
        assertEquals("read", e.getToolName());
        assertEquals("running", e.getToolStatus());
    }

    @Test
    void parse_sessionIdle_asTerminal() {
        String json = "{\"type\":\"session.idle\",\"properties\":{\"sessionID\":\"ses_1\"}}";
        OpencodeEvent e = protocol.parseEvent(json);
        assertEquals(OpencodeEvent.Kind.DONE, e.getKind());
        assertEquals("ses_1", e.getSessionId());
        assertTrue(e.isTerminal());
    }

    @Test
    void parse_sessionError_asErrorTerminal() {
        String json = "{\"type\":\"session.error\",\"properties\":{\"sessionID\":\"ses_1\","
                + "\"error\":{\"message\":\"boom\"}}}";
        OpencodeEvent e = protocol.parseEvent(json);
        assertEquals(OpencodeEvent.Kind.ERROR, e.getKind());
        assertTrue(e.isTerminal());
    }

    @Test
    void parse_unknownType_asOther() {
        OpencodeEvent e = protocol.parseEvent("{\"type\":\"server.heartbeat\"}");
        assertEquals(OpencodeEvent.Kind.OTHER, e.getKind());
        assertFalse(e.isTerminal());
    }

    @Test
    void parse_malformedJson_asOther() {
        OpencodeEvent e = protocol.parseEvent("not-json{");
        assertEquals(OpencodeEvent.Kind.OTHER, e.getKind());
    }

    @Test
    void buildPromptBody_containsAgentModelAndText() throws Exception {
        String body = protocol.buildPromptBody("axon-deep", "spdb-new-api", "glm-47", "分析这支交易");
        var node = new ObjectMapper().readTree(body);
        assertEquals("axon-deep", node.get("agent").asText());
        assertEquals("spdb-new-api", node.get("model").get("providerID").asText());
        assertEquals("glm-47", node.get("model").get("modelID").asText());
        assertEquals("text", node.get("parts").get(0).get("type").asText());
        assertEquals("分析这支交易", node.get("parts").get(0).get("text").asText());
    }

    @Test
    void textDeltaTracker_cumulativeToIncremental() {
        // opencode 的 part.text 为全量累积（PoC 校准点 3；若实测为增量则删除本测试与 Tracker）
        OpencodeProtocol.TextDeltaTracker tracker = new OpencodeProtocol.TextDeltaTracker();
        assertEquals("你好", tracker.delta("你好"));
        assertEquals("，世界", tracker.delta("你好，世界"));
        assertEquals("", tracker.delta("你好，世界"));
        // 文本回退（重新生成）时全量重发
        assertEquals("重来", tracker.delta("重来"));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -Dtest=OpencodeProtocolTest test`
Expected: 编译失败（OpencodeEvent / OpencodeProtocol 不存在）

- [ ] **Step 3: 写实现**

`OpencodeEvent.java`：

```java
package com.axonlink.ai.opencode;

/**
 * opencode /event SSE 事件的归一化表示。
 * 原始事件种类很多，网关层只关心四类：文本更新、工具动作、会话结束、错误；其余归为 OTHER。
 */
public class OpencodeEvent {

    public enum Kind { TEXT, TOOL, DONE, ERROR, OTHER }

    private final Kind kind;
    private final String sessionId;
    private final String text;
    private final String toolName;
    private final String toolStatus;
    private final String errorMessage;

    private OpencodeEvent(Kind kind, String sessionId, String text,
                          String toolName, String toolStatus, String errorMessage) {
        this.kind = kind;
        this.sessionId = sessionId;
        this.text = text;
        this.toolName = toolName;
        this.toolStatus = toolStatus;
        this.errorMessage = errorMessage;
    }

    public static OpencodeEvent text(String sessionId, String text) {
        return new OpencodeEvent(Kind.TEXT, sessionId, text, null, null, null);
    }

    public static OpencodeEvent tool(String sessionId, String toolName, String toolStatus) {
        return new OpencodeEvent(Kind.TOOL, sessionId, null, toolName, toolStatus, null);
    }

    public static OpencodeEvent done(String sessionId) {
        return new OpencodeEvent(Kind.DONE, sessionId, null, null, null, null);
    }

    public static OpencodeEvent error(String sessionId, String message) {
        return new OpencodeEvent(Kind.ERROR, sessionId, null, null, null, message);
    }

    public static OpencodeEvent other() {
        return new OpencodeEvent(Kind.OTHER, null, null, null, null, null);
    }

    public Kind getKind() { return kind; }
    public String getSessionId() { return sessionId; }
    public String getText() { return text; }
    public String getToolName() { return toolName; }
    public String getToolStatus() { return toolStatus; }
    public String getErrorMessage() { return errorMessage; }

    /** 终止事件：收到后本次深度分析的事件消费循环应退出。 */
    public boolean isTerminal() { return kind == Kind.DONE || kind == Kind.ERROR; }
}
```

`OpencodeProtocol.java`：

```java
package com.axonlink.ai.opencode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * opencode HTTP 协议的组装与解析。
 *
 * <p>⚠️ 字段名以运行实例 GET /doc 的 OpenAPI spec 为准（PoC 校准），
 * 与 opencode 版本升级相关的协议变化只应影响本类及其测试。
 */
@Component
public class OpencodeProtocol {

    private final ObjectMapper objectMapper;

    public OpencodeProtocol(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 组装 POST /session/{id}/prompt 请求体。 */
    public String buildPromptBody(String agent, String providerId, String modelId, String text) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("agent", agent);
        ObjectNode model = root.putObject("model");
        model.put("providerID", providerId);
        model.put("modelID", modelId);
        ArrayNode parts = root.putArray("parts");
        ObjectNode part = parts.addObject();
        part.put("type", "text");
        part.put("text", text);
        return root.toString();
    }

    /** 解析 SSE data 行（一条 JSON）→ 归一化事件。解析失败一律归为 OTHER，不让脏数据中断流。 */
    public OpencodeEvent parseEvent(String json) {
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            return OpencodeEvent.other();
        }
        String type = root.path("type").asText("");
        JsonNode props = root.path("properties");
        switch (type) {
            case "message.part.updated": {
                JsonNode part = props.path("part");
                String sessionId = part.path("sessionID").asText(null);
                String partType = part.path("type").asText("");
                if ("text".equals(partType)) {
                    return OpencodeEvent.text(sessionId, part.path("text").asText(""));
                }
                if ("tool".equals(partType)) {
                    return OpencodeEvent.tool(sessionId,
                            part.path("tool").asText(""),
                            part.path("state").path("status").asText(""));
                }
                return OpencodeEvent.other();
            }
            case "session.idle":
                return OpencodeEvent.done(props.path("sessionID").asText(null));
            case "session.error":
                return OpencodeEvent.error(props.path("sessionID").asText(null),
                        props.path("error").path("message").asText(""));
            default:
                return OpencodeEvent.other();
        }
    }

    /**
     * 全量累积文本 → 增量 delta 转换器。
     * opencode 的 text part 每次更新携带该 part 的全量文本（PoC 校准点 3），
     * 前端只需要新增部分；文本变短或前缀不匹配（重新生成）时全量重发。
     */
    public static class TextDeltaTracker {
        private String previous = "";

        public String delta(String full) {
            if (full == null) {
                return "";
            }
            String d;
            if (full.startsWith(previous)) {
                d = full.substring(previous.length());
            } else {
                d = full;
            }
            previous = full;
            return d;
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -Dtest=OpencodeProtocolTest test`
Expected: Tests run: 8, Failures: 0

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/axonlink/ai/opencode/ src/test/java/com/axonlink/ai/opencode/
git commit -m "feat(opencode): 协议层——事件归一化解析 + prompt 请求组装 + 累积转增量"
```

---

### Task 3: OpencodeGateway（REST + SSE 瘦客户端）

**Files:**
- Create: `src/main/java/com/axonlink/ai/opencode/OpencodeGateway.java`
- Test: `src/test/java/com/axonlink/ai/opencode/OpencodeGatewayTest.java`

JDK `java.net.http.HttpClient`（项目既有模式，参考 `OpenAiCompatibleClient`）。测试用 `com.sun.net.httpserver.HttpServer` 起 stub，零新依赖。

**线程模型（streamPrompt）：** 调用方线程内完成——先以 `ofInputStream` 建立 `GET /event` 长连接，再发 `POST /session/{id}/prompt`（异步线程发，避免阻塞事件读取），主循环逐行读事件直到终止事件或整体超时；超时/结束时关闭 InputStream 释放连接。

- [ ] **Step 1: 写失败测试**

```java
package com.axonlink.ai.opencode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** Gateway 单测：本地 HttpServer stub 模拟 opencode serve。 */
class OpencodeGatewayTest {

    private HttpServer server;
    private OpencodeProperties props;
    private OpencodeGateway gateway;
    /** stub 收到的请求记录：METHOD PATH [Authorization] */
    private final List<String> received = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);

        server.createContext("/session", exchange -> {
            received.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath()
                    + " " + String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            byte[] body;
            if ("POST".equals(exchange.getRequestMethod())
                    && "/session".equals(exchange.getRequestURI().getPath())) {
                body = "{\"id\":\"ses_test\"}".getBytes(StandardCharsets.UTF_8);
            } else {
                body = "{}".getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
        });

        server.createContext("/config", exchange -> {
            exchange.sendResponseHeaders(200, 2);
            try (OutputStream os = exchange.getResponseBody()) { os.write("{}".getBytes()); }
        });

        // SSE：推 1 条 text、1 条 tool、1 条其他 session 的 text（应被过滤）、1 条 idle
        server.createContext("/event", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(sse("{\"type\":\"message.part.updated\",\"properties\":{\"part\":{\"sessionID\":\"ses_test\",\"type\":\"text\",\"text\":\"hello\"}}}"));
                os.write(sse("{\"type\":\"message.part.updated\",\"properties\":{\"part\":{\"sessionID\":\"ses_test\",\"type\":\"tool\",\"tool\":\"read\",\"state\":{\"status\":\"running\"}}}}"));
                os.write(sse("{\"type\":\"message.part.updated\",\"properties\":{\"part\":{\"sessionID\":\"ses_other\",\"type\":\"text\",\"text\":\"noise\"}}}"));
                os.write(sse("{\"type\":\"session.idle\",\"properties\":{\"sessionID\":\"ses_test\"}}"));
                os.flush();
            } catch (IOException ignored) {
                // 客户端提前断开属正常路径
            }
        });

        server.start();
        props = new OpencodeProperties();
        props.setEnabled(true);
        props.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        props.setPassword("secret");
        props.setTimeoutSeconds(10);
        gateway = new OpencodeGateway(props, new OpencodeProtocol(new ObjectMapper()), new ObjectMapper());
    }

    private static byte[] sse(String json) {
        return ("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void createSession_returnsIdAndSendsBasicAuth() throws Exception {
        String id = gateway.createSession();
        assertEquals("ses_test", id);
        // opencode:secret 的 Basic 头
        String expected = "Basic " + java.util.Base64.getEncoder()
                .encodeToString("opencode:secret".getBytes(StandardCharsets.UTF_8));
        assertTrue(received.stream().anyMatch(r -> r.startsWith("POST /session") && r.contains(expected)));
    }

    @Test
    void isHealthy_trueOn200() {
        assertTrue(gateway.isHealthy());
    }

    @Test
    void isHealthy_falseWhenServerDown() {
        server.stop(0);
        assertFalse(gateway.isHealthy());
    }

    @Test
    void streamPrompt_forwardsOnlyThisSessionEventsUntilTerminal() throws Exception {
        List<OpencodeEvent> events = new ArrayList<>();
        gateway.streamPrompt("ses_test", "{\"parts\":[]}", events::add, Duration.ofSeconds(10));

        // 收到本 session 的 text/tool/done，过滤掉 ses_other 的事件
        assertEquals(3, events.size());
        assertEquals(OpencodeEvent.Kind.TEXT, events.get(0).getKind());
        assertEquals("hello", events.get(0).getText());
        assertEquals(OpencodeEvent.Kind.TOOL, events.get(1).getKind());
        assertEquals(OpencodeEvent.Kind.DONE, events.get(2).getKind());
        // prompt 请求已发出
        assertTrue(received.stream().anyMatch(r -> r.startsWith("POST /session/ses_test/prompt")));
    }

    @Test
    void deleteSession_swallowsErrors() {
        server.stop(0);
        assertDoesNotThrow(() -> gateway.deleteSession("ses_test"));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -Dtest=OpencodeGatewayTest test`
Expected: 编译失败（OpencodeGateway 不存在）

- [ ] **Step 3: 写实现**

```java
package com.axonlink.ai.opencode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * opencode serve REST/SSE 瘦客户端（深度分析路径）。
 *
 * <p>只封装用到的端点：POST /session、POST /session/{id}/prompt、GET /event、
 * GET /config（健康检查）、DELETE /session/{id}。
 * 「详细设计文档生成」功能落地时在此补同步阻塞方法，两功能共用本类。
 */
@Component
public class OpencodeGateway {

    private static final Logger log = LoggerFactory.getLogger(OpencodeGateway.class);

    private final OpencodeProperties props;
    private final OpencodeProtocol protocol;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OpencodeGateway(OpencodeProperties props, OpencodeProtocol protocol,
                           com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.props = props;
        this.protocol = protocol;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, props.getConnectTimeoutSeconds())))
                .build();
    }

    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(props.getBaseUrl() + path))
                .header("Content-Type", "application/json");
        if (props.getPassword() != null && !props.getPassword().isBlank()) {
            String token = Base64.getEncoder().encodeToString(
                    (props.getUsername() + ":" + props.getPassword()).getBytes(StandardCharsets.UTF_8));
            b.header("Authorization", "Basic " + token);
        }
        return b;
    }

    /** 创建会话，返回 sessionId。 */
    public String createSession() throws IOException, InterruptedException {
        HttpResponse<String> resp = httpClient.send(
                request("/session").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("opencode 创建 session 失败: HTTP " + resp.statusCode());
        }
        String id = objectMapper.readTree(resp.body()).path("id").asText(null);
        if (id == null || id.isBlank()) {
            throw new IOException("opencode 创建 session 响应缺少 id: " + resp.body());
        }
        return id;
    }

    /** 删除会话；失败仅记日志（会话泄漏由 opencode 侧定期清理兜底）。 */
    public void deleteSession(String sessionId) {
        try {
            httpClient.send(request("/session/" + sessionId).DELETE().build(),
                    HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            log.warn("[opencode] 删除 session {} 失败（忽略）：{}", sessionId, e.getMessage());
        }
    }

    /** 健康检查：GET /config 返回 2xx 即认为可用。 */
    public boolean isHealthy() {
        try {
            HttpResponse<Void> resp = httpClient.send(
                    request("/config").GET().timeout(Duration.ofSeconds(3)).build(),
                    HttpResponse.BodyHandlers.discarding());
            return resp.statusCode() / 100 == 2;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 发送 prompt 并消费本 session 的事件流，直到终止事件或整体超时。
     *
     * <p>顺序：先建立 /event 长连接（避免漏掉早期事件），再异步发 prompt，
     * 当前线程逐行读事件；结束/超时后关闭流释放连接。
     *
     * @param sink 事件回调（仅本 session 的 TEXT/TOOL/DONE/ERROR；OTHER 与他人 session 已过滤）
     */
    public void streamPrompt(String sessionId, String promptJson,
                             Consumer<OpencodeEvent> sink, Duration overallTimeout) throws IOException {
        long deadline = System.nanoTime() + overallTimeout.toNanos();
        HttpResponse<InputStream> eventResp;
        try {
            eventResp = httpClient.send(request("/event").GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("订阅 opencode 事件流被中断", e);
        }
        if (eventResp.statusCode() / 100 != 2) {
            throw new IOException("订阅 opencode 事件流失败: HTTP " + eventResp.statusCode());
        }

        // 异步发 prompt：事件流已就绪后再发，响应体不在此消费（结果经事件流回来）
        CompletableFuture<HttpResponse<String>> promptFuture = httpClient.sendAsync(
                request("/session/" + sessionId + "/prompt")
                        .POST(HttpRequest.BodyPublishers.ofString(promptJson)).build(),
                HttpResponse.BodyHandlers.ofString());
        promptFuture.whenComplete((r, t) -> {
            if (t != null) {
                log.warn("[opencode] prompt 请求异常：{}", t.getMessage());
            } else if (r.statusCode() / 100 != 2) {
                log.warn("[opencode] prompt 返回 HTTP {}: {}", r.statusCode(), r.body());
            }
        });

        try (InputStream in = eventResp.body();
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (System.nanoTime() > deadline) {
                    throw new IOException("深度分析超时（" + overallTimeout.toSeconds() + "s）");
                }
                if (!line.startsWith("data:")) {
                    continue;
                }
                OpencodeEvent event = protocol.parseEvent(line.substring(5).trim());
                if (event.getKind() == OpencodeEvent.Kind.OTHER
                        || !sessionId.equals(event.getSessionId())) {
                    continue;
                }
                sink.accept(event);
                if (event.isTerminal()) {
                    return;
                }
            }
            throw new IOException("opencode 事件流提前结束（未收到终止事件）");
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -Dtest=OpencodeGatewayTest test`
Expected: Tests run: 5, Failures: 0

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/axonlink/ai/opencode/OpencodeGateway.java src/test/java/com/axonlink/ai/opencode/OpencodeGatewayTest.java
git commit -m "feat(opencode): Gateway——session 管理 + prompt + SSE 事件流消费 + 健康检查"
```

**已知边界（记录，不在本任务处理）：** 超时判定依赖事件行到达触发；事件完全静默时 readLine 会阻塞到连接层面超时。整体超时的兜底由 Task 4 的 Service 层聚合异常统一处理，PoC/联调阶段若发现静默挂死，在 Gateway 加 watchdog 线程关闭 InputStream（此变更只影响本类）。

---

### Task 4: DeepAnalysisService（编排 + 降级 + NDJSON 输出）

**Files:**
- Create: `src/main/java/com/axonlink/ai/opencode/DeepAnalysisRequest.java`
- Create: `src/main/java/com/axonlink/ai/opencode/DeepAnalysisService.java`
- Test: `src/test/java/com/axonlink/ai/opencode/DeepAnalysisServiceTest.java`

NDJSON 事件协议（与 `/code-explain/stream` 同构，前端好复用）：
`{"type":"start",...}` → `{"type":"delta","content":"..."}` / `{"type":"tool","tool":"read","status":"running"}` → `{"type":"done","content":"全文"}` | `{"type":"error","message":"..."}`；降级时在 done 前多一条 `{"type":"fallback","reason":"..."}`。

- [ ] **Step 1: 写失败测试**

```java
package com.axonlink.ai.opencode;

import com.axonlink.ai.context.AnalysisContextService;
import com.axonlink.ai.dto.AnalysisContext;
import com.axonlink.ai.dto.AnalysisRequest;
import com.axonlink.ai.dto.AnalysisResponse;
import com.axonlink.ai.orchestrator.AnalysisOrchestrator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 深度分析编排单测：mock Gateway/ContextService/Orchestrator。 */
class DeepAnalysisServiceTest {

    private OpencodeProperties props;
    private OpencodeGateway gateway;
    private AnalysisContextService contextService;
    private AnalysisOrchestrator orchestrator;
    private DeepAnalysisService service;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        props = new OpencodeProperties();
        props.setEnabled(true);
        props.setTimeoutSeconds(10);
        gateway = mock(OpencodeGateway.class);
        contextService = mock(AnalysisContextService.class);
        orchestrator = mock(AnalysisOrchestrator.class);
        service = new DeepAnalysisService(props, gateway, new OpencodeProtocol(mapper),
                contextService, orchestrator, mapper);
    }

    private List<JsonNode> runAndParse(String txId, DeepAnalysisRequest req) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.streamDeepAnalyze(txId, req, out);
        List<JsonNode> lines = new ArrayList<>();
        for (String line : out.toString(StandardCharsets.UTF_8).split("\n")) {
            if (!line.isBlank()) {
                lines.add(mapper.readTree(line));
            }
        }
        return lines;
    }

    @Test
    void disabled_fallsBackToOrchestrator() throws Exception {
        props.setEnabled(false);
        AnalysisResponse resp = new AnalysisResponse();
        resp.setSummary("单轮总览");
        resp.setBusinessSummary("业务");
        resp.setTechnicalSummary("技术");
        when(orchestrator.analyzeTransaction(eq("TX1"), any())).thenReturn(resp);

        List<JsonNode> events = runAndParse("TX1", new DeepAnalysisRequest());

        assertEquals("start", events.get(0).get("type").asText());
        assertEquals("fallback", events.get(1).get("type").asText());
        assertEquals("done", events.get(2).get("type").asText());
        assertTrue(events.get(2).get("content").asText().contains("单轮总览"));
        verify(gateway, never()).createSession();
    }

    @Test
    void unhealthy_fallsBackToOrchestrator() throws Exception {
        when(gateway.isHealthy()).thenReturn(false);
        when(orchestrator.analyzeTransaction(eq("TX1"), any())).thenReturn(new AnalysisResponse());

        List<JsonNode> events = runAndParse("TX1", new DeepAnalysisRequest());

        assertEquals("fallback", events.get(1).get("type").asText());
        verify(gateway, never()).createSession();
    }

    @Test
    void normalFlow_forwardsTextAndToolEvents_thenDone_andDeletesSession() throws Exception {
        when(gateway.isHealthy()).thenReturn(true);
        when(gateway.createSession()).thenReturn("ses_1");
        AnalysisContext ctx = new AnalysisContext();
        when(contextService.buildTransactionContext(eq("TX1"), anyString(), any(AnalysisRequest.class)))
                .thenReturn(ctx);
        // 模拟事件流：累积文本 "你好" -> "你好，世界"，一个 tool 事件，然后 done
        doAnswer(inv -> {
            Consumer<OpencodeEvent> sink = inv.getArgument(2);
            sink.accept(OpencodeEvent.text("ses_1", "你好"));
            sink.accept(OpencodeEvent.tool("ses_1", "read", "running"));
            sink.accept(OpencodeEvent.text("ses_1", "你好，世界"));
            sink.accept(OpencodeEvent.done("ses_1"));
            return null;
        }).when(gateway).streamPrompt(eq("ses_1"), anyString(), any(), any(Duration.class));

        DeepAnalysisRequest req = new DeepAnalysisRequest();
        req.setQuestion("这支交易做什么？");
        List<JsonNode> events = runAndParse("TX1", req);

        assertEquals("start", events.get(0).get("type").asText());
        assertEquals("delta", events.get(1).get("type").asText());
        assertEquals("你好", events.get(1).get("content").asText());
        assertEquals("tool", events.get(2).get("type").asText());
        assertEquals("read", events.get(2).get("tool").asText());
        assertEquals("delta", events.get(3).get("type").asText());
        assertEquals("，世界", events.get(3).get("content").asText());
        assertEquals("done", events.get(4).get("type").asText());
        assertEquals("你好，世界", events.get(4).get("content").asText());
        verify(gateway).deleteSession("ses_1");
    }

    @Test
    void gatewayFailure_writesErrorEvent_andDeletesSession() throws Exception {
        when(gateway.isHealthy()).thenReturn(true);
        when(gateway.createSession()).thenReturn("ses_1");
        when(contextService.buildTransactionContext(anyString(), anyString(), any()))
                .thenReturn(new AnalysisContext());
        doThrow(new java.io.IOException("连接被拒绝"))
                .when(gateway).streamPrompt(anyString(), anyString(), any(), any());

        List<JsonNode> events = runAndParse("TX1", new DeepAnalysisRequest());

        JsonNode last = events.get(events.size() - 1);
        assertEquals("error", last.get("type").asText());
        assertTrue(last.get("message").asText().contains("连接被拒绝"));
        verify(gateway).deleteSession("ses_1");
    }
}
```

（若 `AnalysisContext` 构造不可直接 `new`——如构造器私有或需参数——测试中改用 `mock(AnalysisContext.class)`，并对 `getChain()` 等被 prompt 组装用到的方法打桩返回空集合。）

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -Dtest=DeepAnalysisServiceTest test`
Expected: 编译失败（DeepAnalysisRequest / DeepAnalysisService 不存在）

- [ ] **Step 3: 写实现**

`DeepAnalysisRequest.java`：

```java
package com.axonlink.ai.opencode;

/** 深度分析请求体（前端传入）。 */
public class DeepAnalysisRequest {

    /** 用户问题；为空时用默认分析指令。 */
    private String question = "";

    /** 复用现有分析会话 id（可空，空则新生成）。 */
    private String sessionId;

    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
}
```

`DeepAnalysisService.java`：

```java
package com.axonlink.ai.opencode;

import com.axonlink.ai.context.AnalysisContextService;
import com.axonlink.ai.dto.AnalysisContext;
import com.axonlink.ai.dto.AnalysisRequest;
import com.axonlink.ai.dto.AnalysisResponse;
import com.axonlink.ai.orchestrator.AnalysisOrchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 深度分析编排：图谱上下文注入 + opencode 多轮探索 + NDJSON 流式输出。
 *
 * <p>与路径①（{@link AnalysisOrchestrator} 单轮）的关系：opencode 关闭或不健康时
 * 自动降级到路径①，前端收到 fallback 事件提示「已用快速模式」。
 * NDJSON 事件协议与 CodeExplainService 同构：start / delta / tool / fallback / done / error。
 */
@Service
public class DeepAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(DeepAnalysisService.class);

    private final OpencodeProperties props;
    private final OpencodeGateway gateway;
    private final OpencodeProtocol protocol;
    private final AnalysisContextService contextService;
    private final AnalysisOrchestrator orchestrator;
    private final ObjectMapper objectMapper;

    public DeepAnalysisService(OpencodeProperties props,
                               OpencodeGateway gateway,
                               OpencodeProtocol protocol,
                               AnalysisContextService contextService,
                               AnalysisOrchestrator orchestrator,
                               ObjectMapper objectMapper) {
        this.props = props;
        this.gateway = gateway;
        this.protocol = protocol;
        this.contextService = contextService;
        this.orchestrator = orchestrator;
        this.objectMapper = objectMapper;
    }

    public void streamDeepAnalyze(String txId, DeepAnalysisRequest request, OutputStream out) throws IOException {
        DeepAnalysisRequest req = request == null ? new DeepAnalysisRequest() : request;
        writeEvent(out, "start", Map.of(
                "model", props.getProviderId() + "/" + props.getModelId(),
                "agent", props.getAgent(),
                "depth", "deep",
                "renderAs", "markdown"));

        if (!props.isEnabled() || !gateway.isHealthy()) {
            fallback(out, txId, req, props.isEnabled() ? "opencode 服务不可用" : "深度分析未启用");
            return;
        }

        String ocSession = null;
        try {
            String analysisSessionId = req.getSessionId() == null || req.getSessionId().isBlank()
                    ? UUID.randomUUID().toString() : req.getSessionId();
            AnalysisContext context = contextService.buildTransactionContext(
                    txId, analysisSessionId, toAnalysisRequest(req));
            String promptText = buildDeepPrompt(txId, context, req.getQuestion());

            ocSession = gateway.createSession();
            OpencodeProtocol.TextDeltaTracker tracker = new OpencodeProtocol.TextDeltaTracker();
            StringBuilder fullText = new StringBuilder();
            String promptJson = protocol.buildPromptBody(
                    props.getAgent(), props.getProviderId(), props.getModelId(), promptText);

            gateway.streamPrompt(ocSession, promptJson, event -> {
                try {
                    switch (event.getKind()) {
                        case TEXT -> {
                            String delta = tracker.delta(event.getText());
                            if (!delta.isEmpty()) {
                                fullText.append(delta);
                                writeEvent(out, "delta", Map.of("content", delta));
                            }
                        }
                        case TOOL -> writeEvent(out, "tool", Map.of(
                                "tool", event.getToolName() == null ? "" : event.getToolName(),
                                "status", event.getToolStatus() == null ? "" : event.getToolStatus()));
                        case ERROR -> throw new IllegalStateException(
                                "opencode 会话错误：" + event.getErrorMessage());
                        default -> { /* DONE 由 streamPrompt 终止循环，无需写事件 */ }
                    }
                } catch (IOException e) {
                    throw new IllegalStateException("写入流式响应失败：" + e.getMessage(), e);
                }
            }, Duration.ofSeconds(props.getTimeoutSeconds()));

            writeEvent(out, "done", Map.of("content", fullText.toString(), "renderAs", "markdown"));
        } catch (Exception e) {
            log.warn("[deep-analysis] tx={} 深度分析失败：{}", txId, e.getMessage());
            writeEvent(out, "error", Map.of("message", String.valueOf(e.getMessage())));
        } finally {
            if (ocSession != null) {
                gateway.deleteSession(ocSession);
            }
        }
    }

    /** 降级：调路径①单轮分析，把结构化结果拼成 markdown 走 done 事件。 */
    private void fallback(OutputStream out, String txId, DeepAnalysisRequest req, String reason) throws IOException {
        writeEvent(out, "fallback", Map.of("reason", reason));
        try {
            AnalysisResponse resp = orchestrator.analyzeTransaction(txId, toAnalysisRequest(req));
            StringBuilder md = new StringBuilder();
            if (notBlank(resp.getSummary())) {
                md.append("## 总览\n\n").append(resp.getSummary()).append("\n\n");
            }
            if (notBlank(resp.getBusinessSummary())) {
                md.append("## 业务解读\n\n").append(resp.getBusinessSummary()).append("\n\n");
            }
            if (notBlank(resp.getTechnicalSummary())) {
                md.append("## 技术检查\n\n").append(resp.getTechnicalSummary()).append("\n");
            }
            writeEvent(out, "done", Map.of("content", md.toString(), "renderAs", "markdown"));
        } catch (Exception e) {
            writeEvent(out, "error", Map.of("message", "降级分析也失败了：" + e.getMessage()));
        }
    }

    private AnalysisRequest toAnalysisRequest(DeepAnalysisRequest req) {
        AnalysisRequest r = new AnalysisRequest();
        r.setSessionId(req.getSessionId());
        r.setFocus(req.getQuestion() == null ? "" : req.getQuestion());
        return r;
    }

    /** 组装深度分析 prompt：图谱上下文 + 探索指引 + 用户问题。 */
    private String buildDeepPrompt(String txId, AnalysisContext context, String question) {
        Map<String, Object> graphContext = new LinkedHashMap<>();
        graphContext.put("txId", txId);
        graphContext.put("chain", context.getChain());
        graphContext.put("metadata", context.getMetadata());
        String contextJson;
        try {
            contextJson = objectMapper.writeValueAsString(graphContext);
        } catch (Exception e) {
            contextJson = "{\"txId\":\"" + txId + "\"}";
        }
        String q = (question == null || question.isBlank())
                ? "请解读这支交易的业务目的、各层职责、数据流向与潜在技术风险。" : question;
        return "以下是交易 " + txId + " 的图谱上下文（调用链与元数据，JSON）：\n\n"
                + contextJson
                + "\n\n请基于图谱上下文，用只读工具（read/grep/glob）在当前代码仓库中定位并阅读相关源码，"
                + "多轮探索后回答问题。要求：结论有代码依据（引用文件路径），用中文 markdown 输出。\n\n"
                + "问题：" + q;
    }

    private void writeEvent(OutputStream out, String type, Map<String, Object> payload) throws IOException {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", type);
        event.putAll(payload);
        out.write(objectMapper.writeValueAsString(event).getBytes(StandardCharsets.UTF_8));
        out.write('\n');
        out.flush();
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
```

（`AnalysisContext.getChain()/getMetadata()` 的准确 getter 名以 `com.axonlink.ai.dto.AnalysisContext` 实际代码为准——`AnalysisOrchestrator.buildContextStats` 中已见 `getChain()`/`getMetadata()`/`getCodeSnippets()`，如有出入按实际调整。）

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -Dtest=DeepAnalysisServiceTest test`
Expected: Tests run: 4, Failures: 0

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/axonlink/ai/opencode/ src/test/java/com/axonlink/ai/opencode/
git commit -m "feat(opencode): DeepAnalysisService——上下文注入 + 事件转发 + 单轮降级"
```

---

### Task 5: DeepAnalysisController（NDJSON 流式端点）

**Files:**
- Create: `src/main/java/com/axonlink/ai/opencode/DeepAnalysisController.java`
- Test: `src/test/java/com/axonlink/ai/opencode/DeepAnalysisControllerTest.java`

照 `AnalysisController.streamCodeExplain` 模式（`AnalysisController.java:57-63`）。

- [ ] **Step 1: 写失败测试**

```java
package com.axonlink.ai.opencode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Controller 层：验证路由、content-type 与 service 委托。 */
class DeepAnalysisControllerTest {

    private DeepAnalysisService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(DeepAnalysisService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new DeepAnalysisController(service)).build();
    }

    @Test
    void stream_returnsNdjson_andDelegatesToService() throws Exception {
        doAnswer(inv -> {
            OutputStream out = inv.getArgument(2);
            out.write("{\"type\":\"start\"}\n".getBytes(StandardCharsets.UTF_8));
            return null;
        }).when(service).streamDeepAnalyze(eq("TX1"), any(), any());

        mockMvc.perform(post("/api/ai/transactions/TX1/deep-analysis/stream")
                        .contentType("application/json")
                        .content("{\"question\":\"这支交易做什么\"}"))
                .andExpect(request().asyncStarted())
                .andExpect(status().isOk());

        verify(service).streamDeepAnalyze(eq("TX1"), any(DeepAnalysisRequest.class), any());
    }
}
```

（`StreamingResponseBody` 在 MockMvc 中异步执行；若 `verify` 因异步时序偶发失败，在 `mockMvc.perform(...)` 返回的 `MvcResult` 上调用 `mockMvc.perform(asyncDispatch(result))` 后再 verify。）

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -Dtest=DeepAnalysisControllerTest test`
Expected: 编译失败（DeepAnalysisController 不存在）

- [ ] **Step 3: 写实现**

```java
package com.axonlink.ai.opencode;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * 深度分析入口（路径②）。
 * NDJSON 流式协议与 /api/ai/code-explain/stream 同构：start/delta/tool/fallback/done/error。
 */
@RestController
@RequestMapping("/api/ai")
public class DeepAnalysisController {

    private final DeepAnalysisService deepAnalysisService;

    public DeepAnalysisController(DeepAnalysisService deepAnalysisService) {
        this.deepAnalysisService = deepAnalysisService;
    }

    @PostMapping(value = "/transactions/{txId}/deep-analysis/stream", produces = "application/x-ndjson")
    public ResponseEntity<StreamingResponseBody> streamDeepAnalysis(@PathVariable String txId,
                                                                    @RequestBody(required = false) DeepAnalysisRequest request) {
        StreamingResponseBody body = outputStream ->
                deepAnalysisService.streamDeepAnalyze(txId, request, outputStream);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/x-ndjson;charset=UTF-8"))
                .body(body);
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -Dtest=DeepAnalysisControllerTest test`
Expected: Tests run: 1, Failures: 0

- [ ] **Step 5: 跑全量 ai.opencode 包测试 + Commit**

Run: `mvn -q -Dtest='com.axonlink.ai.opencode.*Test' test`
Expected: 全部通过

```bash
git add src/main/java/com/axonlink/ai/opencode/DeepAnalysisController.java src/test/java/com/axonlink/ai/opencode/DeepAnalysisControllerTest.java
git commit -m "feat(opencode): 深度分析 NDJSON 流式端点 /api/ai/transactions/{txId}/deep-analysis/stream"
```

**安全备注：** 项目已有 LDAP/Spring Security 全局拦截（`/api/**` 需登录），新端点自动纳入；无需额外配置。若 SecurityConfig 存在按路径的显式放行清单，确认新路径**不在**放行清单里即可。

---

### Task 6: 前端 API 层 streamDeepAnalysis

**仓库切换：以下任务在 `/Users/java/axon-link-frontend` 下操作。**

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/api/index.js`（在 `streamCodeExplanation` 函数后追加，约 253-320 行附近）

- [ ] **Step 1: 追加流式函数（照 streamCodeExplanation 模式，新增 onTool/onFallback）**

```javascript
/**
 * 深度分析流式接口（opencode 多轮探索）。
 * NDJSON 事件：start / delta / tool / fallback / done / error。
 * handlers: { onStart, onDelta, onTool, onFallback, onDone, onError, signal }
 */
export async function streamDeepAnalysis(txId, payload = {}, handlers = {}) {
  try {
    const res = await fetch(`${BASE}/ai/transactions/${encodeURIComponent(txId)}/deep-analysis/stream`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Accept': 'application/x-ndjson',
      },
      body: JSON.stringify(payload),
      signal: handlers.signal,
    })

    if (!res.ok) {
      throw new Error(`HTTP ${res.status}: /ai/transactions/${txId}/deep-analysis/stream`)
    }
    if (!res.body) {
      throw new Error('流式响应不可用')
    }

    const reader = res.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''

    const emit = (event) => {
      if (!event || typeof event !== 'object') return
      if (event.type === 'start') handlers.onStart?.(event)
      else if (event.type === 'delta') handlers.onDelta?.(event)
      else if (event.type === 'tool') handlers.onTool?.(event)
      else if (event.type === 'fallback') handlers.onFallback?.(event)
      else if (event.type === 'done') handlers.onDone?.(event)
      else if (event.type === 'error') handlers.onError?.(event)
    }

    const parseLine = (line) => {
      const trimmed = line.trim()
      if (!trimmed) return
      const event = JSON.parse(trimmed)
      emit(event)
    }

    while (true) {
      const { value, done } = await reader.read()
      buffer += decoder.decode(value || new Uint8Array(), { stream: !done })

      let newlineIndex = buffer.indexOf('\n')
      while (newlineIndex >= 0) {
        const line = buffer.slice(0, newlineIndex)
        buffer = buffer.slice(newlineIndex + 1)
        parseLine(line)
        newlineIndex = buffer.indexOf('\n')
      }

      if (done) {
        if (buffer.trim()) {
          parseLine(buffer)
        }
        break
      }
    }
  } catch (err) {
    if (err?.name === 'AbortError') return
    handlers.onError?.({ type: 'error', message: err?.message || String(err) })
  }
}
```

（`BASE` 常量与 `streamCodeExplanation` 共用，已在文件顶部定义；abort 处理跟随现有函数风格——若 `streamCodeExplanation` 的 catch 分支写法不同，以它为准对齐。）

- [ ] **Step 2: 构建验证**

Run: `cd /Users/java/axon-link-frontend && npm run build`
Expected: vite build 成功，无语法错误

- [ ] **Step 3: Commit**

```bash
cd /Users/java/axon-link-frontend
git add src/api/index.js
git commit -m "feat(deep-analysis): 深度分析 NDJSON 流式 API streamDeepAnalysis"
```

---

### Task 7: DeepAnalysisPanel.vue 组件（流式对话 + 工具时间线，双主题）

**Files:**
- Create: `/Users/java/axon-link-frontend/src/components/DeepAnalysisPanel.vue`

独立组件（不塞进已 3895 行的 TransactionCard）。markdown 渲染用项目已有的 `markdown-it`（`MonacoCodeViewer.vue:331` 同款）。**颜色一律走 `src/style.css` 既有 token**（`--bg-card`/`--text-primary`/`--text-muted` 等，dark 由 `[data-theme="dark"]` 全局覆盖，组件内不写裸色值）；markdown 走 `v-html`，样式必须 `:deep()` 穿透。

- [ ] **Step 1: 写组件**

```vue
<template>
  <div class="deep-analysis-panel">
    <div class="dap-header">
      <span class="dap-title">深度分析</span>
      <span v-if="fallbackReason" class="dap-badge dap-badge-warn">已降级：{{ fallbackReason }}</span>
      <span v-else-if="model" class="dap-badge">{{ model }}</span>
      <button class="dap-close" @click="$emit('close')">收起</button>
    </div>

    <div class="dap-ask-row">
      <input
        v-model="question"
        class="dap-input"
        type="text"
        placeholder="想深入了解什么？（留空则整体解读该交易）"
        :disabled="running"
        @keyup.enter="start"
      />
      <button v-if="!running" class="dap-btn" @click="start">开始分析</button>
      <button v-else class="dap-btn dap-btn-stop" @click="stop">停止</button>
    </div>

    <div v-if="toolTimeline.length" class="dap-timeline">
      <div v-for="(t, i) in toolTimeline" :key="i" class="dap-timeline-item">
        <span class="dap-tool-name">{{ t.tool }}</span>
        <span class="dap-tool-status">{{ t.status }}</span>
      </div>
    </div>

    <div v-if="renderedHtml" class="dap-output" v-html="renderedHtml"></div>
    <div v-else-if="running" class="dap-output dap-output-waiting">模型正在探索代码仓库…</div>
    <div v-if="errorMessage" class="dap-error">{{ errorMessage }}</div>
  </div>
</template>

<script>
import MarkdownIt from 'markdown-it'
import { streamDeepAnalysis } from '../api/index.js'

const md = new MarkdownIt({ html: false, linkify: true, breaks: true })

export default {
  name: 'DeepAnalysisPanel',
  props: {
    txId: { type: String, required: true },
  },
  emits: ['close'],
  data() {
    return {
      question: '',
      running: false,
      model: '',
      fallbackReason: '',
      contentBuffer: '',
      renderedHtml: '',
      toolTimeline: [],
      errorMessage: '',
      abortController: null,
    }
  },
  beforeUnmount() {
    this.abortController?.abort()
  },
  methods: {
    reset() {
      this.model = ''
      this.fallbackReason = ''
      this.contentBuffer = ''
      this.renderedHtml = ''
      this.toolTimeline = []
      this.errorMessage = ''
    },
    async start() {
      if (this.running) return
      this.reset()
      this.running = true
      this.abortController = new AbortController()
      await streamDeepAnalysis(this.txId, { question: this.question }, {
        signal: this.abortController.signal,
        onStart: (e) => { this.model = e.model || '' },
        onFallback: (e) => { this.fallbackReason = e.reason || '深度模式不可用' },
        onDelta: (e) => {
          this.contentBuffer += e.content || ''
          this.renderedHtml = md.render(this.contentBuffer)
        },
        onTool: (e) => {
          this.toolTimeline.push({ tool: e.tool || '?', status: e.status || '' })
        },
        onDone: (e) => {
          if (e.content) {
            this.contentBuffer = e.content
            this.renderedHtml = md.render(this.contentBuffer)
          }
          this.running = false
        },
        onError: (e) => {
          this.errorMessage = e.message || '深度分析失败'
          this.running = false
        },
      })
      this.running = false
    },
    stop() {
      this.abortController?.abort()
      this.running = false
    },
  },
}
</script>

<style scoped>
/* token 全部来自 src/style.css，dark 由全局 [data-theme="dark"] 覆盖，此处零裸色值 */
.deep-analysis-panel {
  background: var(--bg-card);
  border: 1px solid var(--border-color, var(--bg-badge));
  border-radius: 8px;
  padding: 12px 14px;
  margin-top: 10px;
}
.dap-header {
  display: flex;
  align-items: center;
  gap: 8px;
}
.dap-title {
  color: var(--text-primary);
  font-weight: 600;
}
.dap-badge {
  color: var(--text-muted);
  background: var(--bg-badge);
  border-radius: 4px;
  padding: 1px 8px;
  font-size: 12px;
}
.dap-badge-warn {
  color: var(--text-primary);
}
.dap-close {
  margin-left: auto;
  background: transparent;
  border: none;
  color: var(--text-muted);
  cursor: pointer;
}
.dap-ask-row {
  display: flex;
  gap: 8px;
  margin-top: 10px;
}
.dap-input {
  flex: 1;
  background: var(--bg-input);
  color: var(--text-primary);
  border: 1px solid var(--border-color, var(--bg-badge));
  border-radius: 6px;
  padding: 6px 10px;
}
.dap-btn {
  background: var(--bg-action-btn);
  color: var(--text-primary);
  border: 1px solid var(--border-color, var(--bg-badge));
  border-radius: 6px;
  padding: 6px 14px;
  cursor: pointer;
}
.dap-btn:hover {
  background: var(--bg-domain-hover);
}
.dap-timeline {
  margin-top: 10px;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.dap-timeline-item {
  display: inline-flex;
  gap: 4px;
  align-items: center;
  background: var(--bg-badge);
  border-radius: 4px;
  padding: 2px 8px;
  font-size: 12px;
}
.dap-tool-name {
  color: var(--text-primary);
  font-weight: 500;
}
.dap-tool-status {
  color: var(--text-muted);
}
.dap-output {
  margin-top: 10px;
  color: var(--text-primary);
  line-height: 1.7;
  font-size: 14px;
  overflow-x: auto;
}
.dap-output-waiting {
  color: var(--text-muted);
}
.dap-error {
  margin-top: 8px;
  color: var(--text-muted);
}
/* v-html 注入的 markdown 内容没有 data-v attr，必须 :deep 穿透（项目已知踩坑点） */
.dap-output :deep(h1),
.dap-output :deep(h2),
.dap-output :deep(h3) {
  color: var(--text-primary);
  margin: 12px 0 6px;
}
.dap-output :deep(p) {
  margin: 6px 0;
}
.dap-output :deep(code) {
  background: var(--bg-badge);
  border-radius: 3px;
  padding: 1px 5px;
  font-size: 13px;
}
.dap-output :deep(pre) {
  background: var(--bg-badge);
  border-radius: 6px;
  padding: 10px;
  overflow-x: auto;
}
.dap-output :deep(ul),
.dap-output :deep(ol) {
  padding-left: 22px;
  margin: 6px 0;
}
</style>
```

（`--border-color` 若 `style.css` 中不存在则退到 `--bg-badge`（写法已内置 fallback）；若项目有现成边框 token（如 `--border` / `--divider`），执行时替换为实际名字。错误提示若项目有状态色 token（如 `--status-error`）则使用之并确认 dark 变体，没有就保持 `--text-muted` 中性呈现。）

- [ ] **Step 2: 构建验证**

Run: `cd /Users/java/axon-link-frontend && npm run build`
Expected: 构建成功

- [ ] **Step 3: Commit**

```bash
git add src/components/DeepAnalysisPanel.vue
git commit -m "feat(deep-analysis): 深度分析面板组件——流式 markdown + 工具时间线（双主题 token）"
```

---

### Task 8: TransactionCard 挂载深度分析入口

**Files:**
- Modify: `/Users/java/axon-link-frontend/src/components/TransactionCard.vue`
  - 锚点 1：AI 解读按钮附近（`analysisButtonLabel`，约 176 行）加「深度分析」按钮
  - 锚点 2：AI 解读面板（`.analysis-panel`，约 218 行）之后挂 `DeepAnalysisPanel`
  - 锚点 3：`<script>` imports 与组件注册、data 增加 `deepAnalysisVisible`

TransactionCard 体量大（3895 行），**只做薄挂载**，逻辑全部在 Task 7 的组件内。

- [ ] **Step 1: 注册组件与状态**

`<script>` 顶部 import 区（跟随现有 import 风格）：

```javascript
import DeepAnalysisPanel from './DeepAnalysisPanel.vue'
```

`components` 注册处（若该 SFC 用 `components: {...}` 选项）加 `DeepAnalysisPanel`；`data()` 返回对象中加：

```javascript
deepAnalysisVisible: false,
```

- [ ] **Step 2: 加入口按钮**

在 AI 解读按钮（约 176 行 `{{ analysisButtonLabel }}` 所在 `<button>`）后并排新增（class 跟随相邻按钮，保证两主题样式一致）：

```html
<button
  class="analysis-trigger-btn deep"
  :class="{ active: deepAnalysisVisible }"
  @click.stop="deepAnalysisVisible = !deepAnalysisVisible"
>
  深度分析
</button>
```

（`analysis-trigger-btn` 为示意——以约 176 行现有按钮的真实 class 为准复用，不新写颜色样式；如需区分外观只调间距。）

- [ ] **Step 3: 挂面板**

在 `.analysis-panel`（约 218 行 `v-if="aiAnalysis.visible"` 的块）**之后**、同层级处新增：

```html
<DeepAnalysisPanel
  v-if="deepAnalysisVisible"
  :tx-id="transaction.txId || transaction.id"
  @close="deepAnalysisVisible = false"
/>
```

（`:tx-id` 的取值以 TransactionCard 现有 props/data 中交易 id 的真实字段为准——搜索该文件中调用 `analyzeTransaction`/`streamCodeExplanation` 时传的 id 字段，保持一致。）

- [ ] **Step 4: 构建 + 手动双主题验证**

```bash
cd /Users/java/axon-link-frontend && npm run build && npm run dev
```

浏览器打开交易页：
- light 主题：深度分析按钮/面板可读、边框与背景正常
- 切 dark（`data-theme="dark"`）：文字对比度足够、无白底残留、markdown 代码块底色正常
- 点「深度分析」→ 输入问题 →（后端未起时）面板显示错误提示而非崩溃

- [ ] **Step 5: Commit**

```bash
git add src/components/TransactionCard.vue
git commit -m "feat(deep-analysis): 交易卡挂载深度分析入口（薄挂载）"
```

---

### Task 9: 端到端联调验证 + 文档回写

**Files:**
- Modify: `/Users/java/obsidian/01 Engineering/axon-link-server/opencode深度分析接入-设计.md`（实施结论回写）
- Modify: `/Users/java/obsidian/01 Engineering/axon-link-server/_overview.md`（状态更新，如有出入）

- [ ] **Step 1: 本机端到端联调**

```bash
# 终端 1：opencode serve（Task 0 的 PoC 配置）
cd ~/poc-opencode && POC_API_KEY=xxx opencode serve --port 4096
# 终端 2：后端（开启深度分析）
cd /Users/java/axon-link-server && AI_OPENCODE_ENABLED=true AI_OPENCODE_PROVIDER_ID=poc AI_OPENCODE_MODEL_ID=gpt-4o-mini mvn spring-boot:run
# 终端 3：前端
cd /Users/java/axon-link-frontend && npm run dev
```

验证清单：
- [ ] 交易页点「深度分析」→ 时间线出现工具事件（read/grep）、正文流式增长、最终 done
- [ ] 停止按钮中断后可重新发起
- [ ] 关掉 opencode（终端 1 Ctrl+C）再发起 → 收到 fallback 事件、走单轮结果、前端显示降级徽标
- [ ] `AI_OPENCODE_ENABLED=false` 启动后端 → 同上降级
- [ ] curl 直接打端点确认 NDJSON 行完整：

```bash
curl -sN -X POST http://127.0.0.1:8080/api/ai/transactions/<某真实txId>/deep-analysis/stream \
  -H 'Content-Type: application/json' -d '{"question":"这支交易的主要业务流程？"}'
```

- [ ] **Step 2: 全量回归**

Run: `cd /Users/java/axon-link-server && mvn -q test`
Expected: 全部通过（新增 4 个测试类 + 存量测试无回归）

- [ ] **Step 3: 设计文档回写（Obsidian 单一来源）**

把 PoC 校准结论（端点/事件字段名、text 语义、权限验证结果）与实施偏差回写进设计文档「风险与开放问题」一节；`_overview.md` 无需改动（除非架构结论有变）。

- [ ] **Step 4: 最终提交**

```bash
cd /Users/java/axon-link-server && git add -A && git commit -m "feat(opencode): 深度分析路径联调完成"
cd /Users/java/axon-link-frontend && git add -A && git commit -m "feat(deep-analysis): 深度分析前端联调完成"
```

---

## 上线前人工事项（不在本计划任务内，勿遗漏）

1. **内网部署核查（设计阶段 2）**：现有 opencode 版本是否支持 agent/permission/format；不支持则离线升级（保留 `OPENCODE_MODELS_URL` 等离线配置）
2. 在内网 opencode.json 注册 `axon-deep` agent（配置见设计文档），工作目录确认指向与图谱构建同源的代码检出目录
3. 内网 `AI_OPENCODE_*` 环境变量下发（`ENABLED=true`、`PASSWORD` 与 opencode 侧一致）
4. 验证 Task 0 Step 5 的权限锁定在内网实例上同样生效
