package com.axonlink.ai.opencode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * opencode HTTP 协议的组装与解析（按 1.14.40 实测校准，见 docs/superpowers/plans/artifacts/opencode-poc-notes.md）。
 *
 * <p>协议要点：文本增量走 message.part.delta（delta 字段即增量片段）；
 * message.part.updated 只用于工具状态；session.idle 是终止信号。
 * opencode 版本升级引起的协议变化只应影响本类及其测试。
 */
@Component
public class OpencodeProtocol {

    private final ObjectMapper objectMapper;

    public OpencodeProtocol(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 组装 POST /session/{id}/prompt_async 请求体。 */
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
            case "message.part.delta": {
                if (!"text".equals(props.path("field").asText(""))) {
                    return OpencodeEvent.other();
                }
                return OpencodeEvent.text(
                        props.path("sessionID").asText(null),
                        props.path("delta").asText(""));
            }
            case "message.part.updated": {
                JsonNode part = props.path("part");
                if ("tool".equals(part.path("type").asText(""))) {
                    return OpencodeEvent.tool(
                            part.path("sessionID").asText(null),
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
}
