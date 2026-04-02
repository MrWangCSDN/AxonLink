package com.axonlink.ai.dto;

/**
 * 模型返回结果。
 */
public class LlmResult {

    private String model;
    private String summary;
    private String businessSummary;
    private String technicalSummary;
    private String rawText;

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getBusinessSummary() {
        return businessSummary;
    }

    public void setBusinessSummary(String businessSummary) {
        this.businessSummary = businessSummary;
    }

    public String getTechnicalSummary() {
        return technicalSummary;
    }

    public void setTechnicalSummary(String technicalSummary) {
        this.technicalSummary = technicalSummary;
    }

    public String getRawText() {
        return rawText;
    }

    public void setRawText(String rawText) {
        this.rawText = rawText;
    }
}
