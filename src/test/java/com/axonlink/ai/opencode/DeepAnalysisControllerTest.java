package com.axonlink.ai.opencode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
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

        // 先固化异步起点：拿到 MvcResult 后显式 asyncDispatch，
        // 避免 verify 与 StreamingResponseBody 的异步写入产生时序竞争（偶发失败根因）。
        MvcResult mvcResult = mockMvc.perform(post("/api/ai/transactions/TX1/deep-analysis/stream")
                        .contentType("application/json")
                        .content("{\"question\":\"这支交易做什么\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        // asyncDispatch 后的响应已经是异步处理完成的最终状态，content-type 断言放这里最稳。
        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/x-ndjson;charset=UTF-8"));

        // 用 ArgumentCaptor 而不是 any()，验证 controller 真的把 POST body 反序列化透传给了 service，
        // 而不只是"调用过一次、参数是什么无所谓"。
        ArgumentCaptor<DeepAnalysisRequest> requestCaptor = ArgumentCaptor.forClass(DeepAnalysisRequest.class);
        verify(service).streamDeepAnalyze(eq("TX1"), requestCaptor.capture(), any());
        assertEquals("这支交易做什么", requestCaptor.getValue().getQuestion());
    }
}
