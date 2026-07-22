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
