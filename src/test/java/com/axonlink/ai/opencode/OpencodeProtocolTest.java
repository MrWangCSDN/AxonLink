package com.axonlink.ai.opencode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OpencodeProtocol} 单元测试。
 *
 * <p>样例 JSON 取自 opencode 1.14.40 实采事件流
 * （见 {@code docs/superpowers/plans/artifacts/opencode-events-sample.jsonl}），
 * 无关字段（如工具 output 的长文本、metadata）做了精简，但字段名与嵌套结构与实采保持一致。
 * 纯 POJO + 手工 new ObjectMapper()，不需要 Spring 上下文，跑得快。
 */
class OpencodeProtocolTest {

    /** 真实样例第 11 条事件里的 sessionID，贯穿本文件所有样例。 */
    private static final String SES = "ses_0a06821feffeV3uvBtEoGluJFw";

    private final OpencodeProtocol protocol = new OpencodeProtocol(new ObjectMapper());

    @Test
    @DisplayName("message.part.delta（field=text）→ TEXT 事件，delta 即增量文本")
    void parse_textDelta_asTextEvent() {
        // 真实样例第 7 条：三条连续 delta 中的第一条（"这是"）
        String json = """
                {"id":"evt_f5f97e434001BoN5D48VZJ4XLp","type":"message.part.delta",\
                "properties":{"sessionID":"ses_0a06821feffeV3uvBtEoGluJFw",\
                "messageID":"msg_f5f97e3f1001AQ6J2yVt77n3ds",\
                "partID":"prt_f5f97e433001ZBhrwd2HTPVD6a","field":"text","delta":"这是"}}
                """;

        OpencodeEvent event = protocol.parseEvent(json);

        assertEquals(OpencodeEvent.Kind.TEXT, event.getKind());
        assertEquals(SES, event.getSessionId());
        assertEquals("这是", event.getText());
        assertFalse(event.isTerminal());
    }

    @Test
    @DisplayName("message.part.delta 但 field!=text（如 reasoning）→ OTHER")
    void parse_textDelta_nonTextField_asOther() {
        // 基于真实样例第 7 条改写：field 从 "text" 改成 "reasoning"
        String json = """
                {"id":"evt_f5f97e434001BoN5D48VZJ4XLp","type":"message.part.delta",\
                "properties":{"sessionID":"ses_0a06821feffeV3uvBtEoGluJFw",\
                "messageID":"msg_f5f97e3f1001AQ6J2yVt77n3ds",\
                "partID":"prt_f5f97e433001ZBhrwd2HTPVD6a","field":"reasoning","delta":"这是"}}
                """;

        OpencodeEvent event = protocol.parseEvent(json);

        assertEquals(OpencodeEvent.Kind.OTHER, event.getKind());
    }

    /** 真实样例第 4/5/6 条：同一个 read 工具调用的 pending → running → completed 三态。 */
    static Stream<Arguments> toolStateSamples() {
        return Stream.of(
                Arguments.of("""
                        {"id":"evt_f5f97e383001ONM15yYZtfbmse","type":"message.part.updated",\
                        "properties":{"sessionID":"ses_0a06821feffeV3uvBtEoGluJFw",\
                        "part":{"id":"prt_f5f97e382001gvwbdR0GLgHTwJ",\
                        "messageID":"msg_f5f97e27d0011chWDpDYoYmHBd",\
                        "sessionID":"ses_0a06821feffeV3uvBtEoGluJFw","type":"tool","tool":"read",\
                        "callID":"call_8d95d92440fa4537a0c4cce4",\
                        "state":{"status":"pending","input":{},"raw":""}},"time":1784015217539}}
                        """, "pending"),
                Arguments.of("""
                        {"id":"evt_f5f97e386001CJVkwTj3i0O0Pa","type":"message.part.updated",\
                        "properties":{"sessionID":"ses_0a06821feffeV3uvBtEoGluJFw",\
                        "part":{"type":"tool","tool":"read","callID":"call_8d95d92440fa4537a0c4cce4",\
                        "state":{"status":"running","input":{"filePath":"opencode.json"},"raw":""},\
                        "id":"prt_f5f97e382001gvwbdR0GLgHTwJ","sessionID":"ses_0a06821feffeV3uvBtEoGluJFw",\
                        "messageID":"msg_f5f97e27d0011chWDpDYoYmHBd"},"time":1784015217542}}
                        """, "running"),
                Arguments.of("""
                        {"id":"evt_f5f97e390001TuPYaQTgD6zGVu","type":"message.part.updated",\
                        "properties":{"sessionID":"ses_0a06821feffeV3uvBtEoGluJFw",\
                        "part":{"type":"tool","tool":"read","callID":"call_8d95d92440fa4537a0c4cce4",\
                        "state":{"status":"completed","input":{"filePath":"opencode.json"},\
                        "output":"<path>opencode.json</path>(内容已精简，与本测试无关)"},\
                        "id":"prt_f5f97e382001gvwbdR0GLgHTwJ","sessionID":"ses_0a06821feffeV3uvBtEoGluJFw",\
                        "messageID":"msg_f5f97e27d0011chWDpDYoYmHBd"},"time":1784015217552}}
                        """, "completed")
        );
    }

    @ParameterizedTest(name = "status={1}")
    @MethodSource("toolStateSamples")
    @DisplayName("message.part.updated（part.type=tool）→ TOOL 事件，携带工具名与状态")
    void parse_toolPartUpdated_asToolEvent(String json, String expectedStatus) {
        OpencodeEvent event = protocol.parseEvent(json);

        assertEquals(OpencodeEvent.Kind.TOOL, event.getKind());
        assertEquals(SES, event.getSessionId());
        assertEquals("read", event.getToolName());
        assertEquals(expectedStatus, event.getToolStatus());
        assertFalse(event.isTerminal());
    }

    @Test
    @DisplayName("message.part.updated（part.type=text，全量快照/用户回显）→ OTHER，不当作增量")
    void parse_textPartUpdated_asOther() {
        // 真实样例第 2 条：用户消息回显（发起提问那一条）
        String userEcho = """
                {"id":"evt_f5f97e2430017G4CDxlWVDTAcI","type":"message.part.updated",\
                "properties":{"sessionID":"ses_0a06821feffeV3uvBtEoGluJFw",\
                "part":{"type":"text","text":"读取当前目录的 opencode.json 并总结内容",\
                "messageID":"msg_f5f97e2380010RLzRD0OtYFCi2",\
                "sessionID":"ses_0a06821feffeV3uvBtEoGluJFw","id":"prt_f5f97e23a001B0UWupZ8iyxthD"},\
                "time":1784015217219}}
                """;
        // 真实样例第 10 条：助手回复的全量快照（与第 7-9 条 delta 拼接结果一致，但不应重复消费）
        String assistantSnapshot = """
                {"id":"evt_f5f97e607001iHyZkYhaPDDMvE","type":"message.part.updated",\
                "properties":{"sessionID":"ses_0a06821feffeV3uvBtEoGluJFw",\
                "part":{"id":"prt_f5f97e433001ZBhrwd2HTPVD6a",\
                "messageID":"msg_f5f97e3f1001AQ6J2yVt77n3ds",\
                "sessionID":"ses_0a06821feffeV3uvBtEoGluJFw","type":"text","text":"这是一个测试响应",\
                "time":{"start":1784015217715,"end":1784015218182}},"time":1784015218182}}
                """;

        assertEquals(OpencodeEvent.Kind.OTHER, protocol.parseEvent(userEcho).getKind());
        assertEquals(OpencodeEvent.Kind.OTHER, protocol.parseEvent(assistantSnapshot).getKind());
    }

    @Test
    @DisplayName("session.idle → DONE，terminal")
    void parse_sessionIdle_asTerminal() {
        // 真实样例第 11 条：会话结束
        String json = """
                {"id":"evt_f5f97e67f002BwYwWAnzjMpc4d","type":"session.idle",\
                "properties":{"sessionID":"ses_0a06821feffeV3uvBtEoGluJFw"}}
                """;

        OpencodeEvent event = protocol.parseEvent(json);

        assertEquals(OpencodeEvent.Kind.DONE, event.getKind());
        assertEquals(SES, event.getSessionId());
        assertTrue(event.isTerminal());
    }

    @Test
    @DisplayName("session.error → ERROR，terminal（PoC 未实采到，按校准笔记的容错结构构造）")
    void parse_sessionError_asErrorTerminal() {
        String json = """
                {"type":"session.error","properties":{"sessionID":"ses_1",\
                "error":{"message":"boom"}}}
                """;

        OpencodeEvent event = protocol.parseEvent(json);

        assertEquals(OpencodeEvent.Kind.ERROR, event.getKind());
        assertEquals("ses_1", event.getSessionId());
        assertEquals("boom", event.getErrorMessage());
        assertTrue(event.isTerminal());
    }

    @Test
    @DisplayName("未知/忽略类型的事件 → OTHER")
    void parse_unknownType_asOther() {
        // 真实样例第 1 条：连接建立
        String serverConnected = """
                {"id":"evt_f5f97de23001UgLWZFpiExUrIx","type":"server.connected","properties":{}}
                """;
        // 真实样例第 3 条：会话状态变化（busy）
        String sessionStatus = """
                {"id":"evt_f5f97e26b001IaQrQDAQrmj21P","type":"session.status",\
                "properties":{"sessionID":"ses_0a06821feffeV3uvBtEoGluJFw","status":{"type":"busy"}}}
                """;

        assertEquals(OpencodeEvent.Kind.OTHER, protocol.parseEvent(serverConnected).getKind());
        assertEquals(OpencodeEvent.Kind.OTHER, protocol.parseEvent(sessionStatus).getKind());
    }

    @Test
    @DisplayName("非法 JSON → OTHER，不抛异常")
    void parse_malformedJson_asOther() {
        OpencodeEvent event = protocol.parseEvent("not-json{");

        assertEquals(OpencodeEvent.Kind.OTHER, event.getKind());
    }

    @Test
    @DisplayName("buildPromptBody 组装的请求体包含 agent/model/parts，字段名与实测被接受的请求一致")
    void buildPromptBody_containsAgentModelAndText() throws Exception {
        String body = protocol.buildPromptBody("axon-deep", "poc", "mock-model", "读取当前目录的 opencode.json 并总结内容");

        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(body);

        assertEquals("axon-deep", root.path("agent").asText());
        assertEquals("poc", root.path("model").path("providerID").asText());
        assertEquals("mock-model", root.path("model").path("modelID").asText());
        assertEquals("text", root.path("parts").get(0).path("type").asText());
        assertEquals("读取当前目录的 opencode.json 并总结内容", root.path("parts").get(0).path("text").asText());
    }
}
