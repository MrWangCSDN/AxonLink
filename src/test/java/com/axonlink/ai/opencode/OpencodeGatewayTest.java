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

/** Gateway 单测：本地 HttpServer stub 模拟 opencode serve（fixture 来自 1.14.40 实采格式）。 */
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
            } else if ("POST".equals(exchange.getRequestMethod())
                    && exchange.getRequestURI().getPath().endsWith("/prompt_async")) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
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

        // SSE：推 1 条 text delta、1 条 tool、1 条其他 session 的 delta（应被过滤）、1 条 idle
        server.createContext("/event", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(sse("{\"type\":\"message.part.delta\",\"properties\":{\"sessionID\":\"ses_test\",\"messageID\":\"msg_1\",\"partID\":\"prt_1\",\"field\":\"text\",\"delta\":\"hello\"}}"));
                os.write(sse("{\"type\":\"message.part.updated\",\"properties\":{\"sessionID\":\"ses_test\",\"part\":{\"id\":\"prt_2\",\"messageID\":\"msg_1\",\"sessionID\":\"ses_test\",\"type\":\"tool\",\"tool\":\"read\",\"callID\":\"call_1\",\"state\":{\"status\":\"running\",\"input\":{\"filePath\":\"a.java\"}}}}}"));
                os.write(sse("{\"type\":\"message.part.delta\",\"properties\":{\"sessionID\":\"ses_other\",\"messageID\":\"msg_9\",\"partID\":\"prt_9\",\"field\":\"text\",\"delta\":\"noise\"}}"));
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

        // 收到本 session 的 text/tool/done，过滤掉 ses_other 的 delta
        assertEquals(3, events.size());
        assertEquals(OpencodeEvent.Kind.TEXT, events.get(0).getKind());
        assertEquals("hello", events.get(0).getText());
        assertEquals(OpencodeEvent.Kind.TOOL, events.get(1).getKind());
        assertEquals("read", events.get(1).getToolName());
        assertEquals(OpencodeEvent.Kind.DONE, events.get(2).getKind());
        // prompt_async 是 fire-and-forget（sendAsync，不阻塞事件读取），本 stub 又是连接建立后
        // 立即同步吐完全部 SSE，不像真实 opencode 要等 LLM 处理，所以 streamPrompt() 返回时
        // 该请求不一定已经打到 server；轮询等待而非立即断言，避免假性抖动。
        assertTrue(awaitReceived("POST /session/ses_test/prompt_async", Duration.ofSeconds(2)));
    }

    /** 轮询等待 {@link #received} 中出现匹配前缀的记录；用于断言异步（fire-and-forget）请求的副作用。 */
    private boolean awaitReceived(String prefix, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (received.stream().anyMatch(r -> r.startsWith(prefix))) {
                return true;
            }
            Thread.sleep(10);
        }
        return received.stream().anyMatch(r -> r.startsWith(prefix));
    }

    @Test
    void deleteSession_swallowsErrors() {
        server.stop(0);
        assertDoesNotThrow(() -> gateway.deleteSession("ses_test"));
    }
}
