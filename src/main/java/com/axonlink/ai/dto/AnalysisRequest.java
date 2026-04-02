package com.axonlink.ai.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 分析请求。
 */
public class AnalysisRequest {

    private String sessionId;
    private String focus = "";
    private String mode = "FULL";
    private boolean includeCode = true;
    private boolean forceRefresh = false;
    private int maxCodeSnippets = 0;
    private List<String> selectedPath = new ArrayList<>();

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getFocus() {
        return focus;
    }

    public void setFocus(String focus) {
        this.focus = focus;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public boolean isIncludeCode() {
        return includeCode;
    }

    public void setIncludeCode(boolean includeCode) {
        this.includeCode = includeCode;
    }

    public boolean isForceRefresh() {
        return forceRefresh;
    }

    public void setForceRefresh(boolean forceRefresh) {
        this.forceRefresh = forceRefresh;
    }

    public int getMaxCodeSnippets() {
        return maxCodeSnippets;
    }

    public void setMaxCodeSnippets(int maxCodeSnippets) {
        this.maxCodeSnippets = maxCodeSnippets;
    }

    public List<String> getSelectedPath() {
        return selectedPath;
    }

    public void setSelectedPath(List<String> selectedPath) {
        this.selectedPath = selectedPath == null ? new ArrayList<>() : new ArrayList<>(selectedPath);
    }

    public AnalysisMode resolveMode() {
        return AnalysisMode.fromNullable(mode);
    }
}
