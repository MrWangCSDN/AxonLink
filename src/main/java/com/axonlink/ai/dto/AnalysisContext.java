package com.axonlink.ai.dto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一的分析上下文。
 */
public class AnalysisContext {

    private String txId;
    private String sessionId;
    private AnalysisMode mode = AnalysisMode.FULL;
    private String focus = "";
    private Map<String, Object> chain = new LinkedHashMap<>();
    private List<String> selectedPath = new ArrayList<>();
    private List<CodeSnippet> codeSnippets = new ArrayList<>();
    private Map<String, Object> metadata = new LinkedHashMap<>();
    private List<RuleFinding> ruleFindings = new ArrayList<>();

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

    public AnalysisMode getMode() {
        return mode;
    }

    public void setMode(AnalysisMode mode) {
        this.mode = mode;
    }

    public String getFocus() {
        return focus;
    }

    public void setFocus(String focus) {
        this.focus = focus;
    }

    public Map<String, Object> getChain() {
        return chain;
    }

    public void setChain(Map<String, Object> chain) {
        this.chain = chain == null ? new LinkedHashMap<>() : new LinkedHashMap<>(chain);
    }

    public List<String> getSelectedPath() {
        return selectedPath;
    }

    public void setSelectedPath(List<String> selectedPath) {
        this.selectedPath = selectedPath == null ? new ArrayList<>() : new ArrayList<>(selectedPath);
    }

    public List<CodeSnippet> getCodeSnippets() {
        return codeSnippets;
    }

    public void setCodeSnippets(List<CodeSnippet> codeSnippets) {
        this.codeSnippets = codeSnippets == null ? new ArrayList<>() : new ArrayList<>(codeSnippets);
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata);
    }

    public List<RuleFinding> getRuleFindings() {
        return ruleFindings;
    }

    public void setRuleFindings(List<RuleFinding> ruleFindings) {
        this.ruleFindings = ruleFindings == null ? new ArrayList<>() : new ArrayList<>(ruleFindings);
    }
}
