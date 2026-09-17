package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayReportSummaryView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class ReplayReportSummaryCodec {

    private final ObjectMapper objectMapper;

    public ReplayReportSummaryCodec() {
        this(new ObjectMapper().findAndRegisterModules()
                .enable(DeserializationFeature.USE_LONG_FOR_INTS)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS));
    }

    ReplayReportSummaryCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String encode(ReplayReportSummaryView view) {
        try {
            return objectMapper.writeValueAsString(Objects.requireNonNull(view, "view"));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("回放报告汇总序列化失败", exception);
        }
    }

    public ReplayReportSummaryView decode(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("回放报告汇总为空");
        }
        try {
            ReplayReportSummaryView view = objectMapper.readValue(json, ReplayReportSummaryView.class);
            if (view.schemaVersion() != 1) {
                throw new IllegalArgumentException("不支持的回放报告汇总版本");
            }
            return view;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("回放报告汇总格式错误", exception);
        }
    }
}
