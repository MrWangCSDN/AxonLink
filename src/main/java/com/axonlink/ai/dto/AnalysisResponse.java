package com.axonlink.ai.dto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 解读响应。
 */
public class AnalysisResponse {

    private String txId;
    private String sessionId;
    private String mode;
    private boolean cached;
    private String model;
    private String summary;
    private String businessSummary;
    private String technicalSummary;
    private List<RuleFinding> findings = new ArrayList<>();
    private List<CodeSnippet> codeSnippets = new ArrayList<>();
    private Map<String, Object> contextStats = new LinkedHashMap<>();
    private Map<String, Object> chain = new LinkedHashMap<>();

    public String getTxId() {
        return txId;
    }

    public void setTxId(String txId) {
        this.txId = txId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public boolean isCached() {
        return cached;
    }

    public void setCached(boolean cached) {
        this.cached = cached;
    }

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

    public List<RuleFinding> getFindings() {
        return findings;
    }

    public void setFindings(List<RuleFinding> findings) {
        this.findings = findings == null ? new ArrayList<>() : new ArrayList<>(findings);
    }

    public List<CodeSnippet> getCodeSnippets() {
        return codeSnippets;
    }

    public void setCodeSnippets(List<CodeSnippet> codeSnippets) {
        this.codeSnippets = codeSnippets == null ? new ArrayList<>() : new ArrayList<>(codeSnippets);
    }

    public Map<String, Object> getContextStats() {
        return contextStats;
    }

    public void setContextStats(Map<String, Object> contextStats) {
        this.contextStats = contextStats == null ? new LinkedHashMap<>() : new LinkedHashMap<>(contextStats);
    }

    public Map<String, Object> getChain() {
        return chain;
    }

    public void setChain(Map<String, Object> chain) {
        this.chain = chain == null ? new LinkedHashMap<>() : new LinkedHashMap<>(chain);
    }
}
