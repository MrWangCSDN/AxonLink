package com.axonlink.ai.opencode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/** Gateway 单测：本地 HttpServer stub 模拟 opencode serve（fixture 来自 1.14.40 实采格式）。 */
class OpencodeGatewayTest {

    private HttpServer server;
    private OpencodeProperties props;
    private OpencodeGateway gateway;
    /** stub 收到的请求记录：METHOD PATH [Authorization] */
    private final List<String> received = new CopyOnWriteArrayList<>();
    /** 置 true 后 /event 返回 500（覆盖订阅被拒分支）；volatile：HttpServer 工作线程与测试线程共享。 */
    private volatile boolean eventFail = false;
    /**
     * 置 true 后 /event 返回 200 并挂住连接（不发任何事件），用于模拟「订阅成功但 prompt
     * 派发失败」场景；须配合 {@link #hangLatch} 在 tearDown 释放，否则工作线程永久阻塞。
     */
    private volatile boolean eventHang = false;
    /** 置 true 后 prompt_async 返回 500（配合 eventHang 复现「解锁」路径）。 */
    private volatile boolean promptAsyncFail = false;
    /** 挂住 /event 连接的闸门；测试断言完成后在 tearDown 统一放行，避免工作线程泄漏。 */
    private final CountDownLatch hangLatch = new CountDownLatch(1);

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        // 默认单派发执行器下，/event 阻塞时 prompt_async 请求排不上队会死锁；
        // 用 cached pool 让两个 handler 并发执行，更贴近真实服务端的并发模型。
        server.setExecutor(Executors.newCachedThreadPool());

        server.createContext("/session", exchange -> {
            received.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath()
                    + " " + String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            byte[] body;
            if ("POST".equals(exchange.getRequestMethod())
                    && "/session".equals(exchange.getRequestURI().getPath())) {
                body = "{\"id\":\"ses_test\"}".getBytes(StandardCharsets.UTF_8);
            } else if ("POST".equals(exchange.getRequestMethod())
                    && exchange.getRequestURI().getPath().endsWith("/prompt_async")) {
                if (promptAsyncFail) {
                    exchange.sendResponseHeaders(500, -1);
                    exchange.close();
                    return;
                }
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

        // SSE：推 1 条 text delta、1 条 tool、1 条其他 session 的 delta（应被过滤）、
        //      1 条本 session 的 OTHER（session.status，应被过滤）、1 条 idle
        server.createContext("/event", exchange -> {
            if (eventHang) {
                // 订阅本身成功（200），但之后不发任何数据——模拟事件流建立后 prompt 派发失败的窗口期
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                try {
                    exchange.getResponseBody().flush();
                    hangLatch.await();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
                return;
            }
            if (eventFail) {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(sse("{\"type\":\"message.part.delta\",\"properties\":{\"sessionID\":\"ses_test\",\"messageID\":\"msg_1\",\"partID\":\"prt_1\",\"field\":\"text\",\"delta\":\"hello\"}}"));
                os.write(sse("{\"type\":\"message.part.updated\",\"properties\":{\"sessionID\":\"ses_test\",\"part\":{\"id\":\"prt_2\",\"messageID\":\"msg_1\",\"sessionID\":\"ses_test\",\"type\":\"tool\",\"tool\":\"read\",\"callID\":\"call_1\",\"state\":{\"status\":\"running\",\"input\":{\"filePath\":\"a.java\"}}}}}"));
                os.write(sse("{\"type\":\"message.part.delta\",\"properties\":{\"sessionID\":\"ses_other\",\"messageID\":\"msg_9\",\"partID\":\"prt_9\",\"field\":\"text\",\"delta\":\"noise\"}}"));
                os.write(sse("{\"type\":\"session.status\",\"properties\":{\"sessionID\":\"ses_test\",\"status\":{\"type\":\"busy\"}}}"));
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
        // 先放行可能挂住的 /event 工作线程，再停服务器，避免线程泄漏/优雅关闭卡住；
        // 未触发 eventHang 的测试里 countDown 在空 latch 上也是幂等的，无副作用。
        hangLatch.countDown();
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

        // 收到本 session 的 text/tool/done，过滤掉 ses_other 的 delta 与本 session 的 OTHER（session.status）
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
    @Timeout(5) // 该分支若回归为挂起（泄漏修复前的行为），要快速失败而不是拖死构建
    void streamPrompt_throwsWhenEventSubscribeRejected() {
        eventFail = true;
        IOException ex = assertThrows(IOException.class,
                () -> gateway.streamPrompt("ses_test", "{\"parts\":[]}", e -> {}, Duration.ofSeconds(5)));
        assertTrue(ex.getMessage().contains("HTTP 500"));
        // 订阅被拒时不应再派发 prompt
        assertTrue(received.stream().noneMatch(r -> r.contains("/prompt_async")));
    }

    /**
     * 回归覆盖：/event 订阅成功（200）之后、prompt_async 派发失败（500）——不同于上面
     * {@code streamPrompt_throwsWhenEventSubscribeRejected}（订阅本身就被拒），这里事件流已建立，
     * 主线程正阻塞在 {@code reader.readLine()} 等数据；覆盖 {@link OpencodeGateway#streamPrompt}
     * 中 prompt 派发失败时主动关闭事件流解锁的分支（915fa29 的评审修复）。
     * 用宽松的 overallTimeout（60s）+ 断言实际耗时 &lt; 10s，证明是「主动解锁」而不是巧合命中超时判断。
     */
    @Test
    @Timeout(15) // 若解锁修复回归，会一直挂到 overallTimeout（60s）才抛超时异常，此处应快速失败
    void streamPrompt_unlocksReadLineWhenPromptDispatchFails() throws Exception {
        eventHang = true;
        promptAsyncFail = true;

        // assertThrows 本身即断言「抛出 IOException」；具体消息是 JDK HttpClient 内部流实现
        // 对「主动 close()」的措辞（实测为 "closed"），不同 JDK 版本可能不同，不作强绑定断言。
        // 真正证明「主动解锁而非等待整体超时」的是下面的耗时上界。
        long start = System.nanoTime();
        assertThrows(IOException.class,
                () -> gateway.streamPrompt("ses_test", "{\"parts\":[]}", e -> {}, Duration.ofSeconds(60)));
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertTrue(elapsedMs < 10_000,
                "应由 prompt_async 失败主动关流解锁，而非等待 60s 整体超时；实际耗时=" + elapsedMs + "ms");
    }

    @Test
    void deleteSession_swallowsErrors() {
        server.stop(0);
        assertDoesNotThrow(() -> gateway.deleteSession("ses_test"));
    }
}
