package com.axonlink.ai.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * flowtrans.xml 标签词典定义。
 */
public class FlowtransTagDefinition {

    private String name;
    private String category;
    private Integer observedCount;
    private String meaningDraft;
    private String businessMeaningDraft;
    private String technicalMeaningDraft;
    private List<String> exampleFiles = new ArrayList<>();
    private List<FlowtransAttributeDefinition> commonAttributes = new ArrayList<>();
    private List<String> openQuestions = new ArrayList<>();

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public Integer getObservedCount() {
        return observedCount;
    }

    public void setObservedCount(Integer observedCount) {
        this.observedCount = observedCount;
    }

    public String getMeaningDraft() {
        return meaningDraft;
    }

    public void setMeaningDraft(String meaningDraft) {
        this.meaningDraft = meaningDraft;
    }

    public String getBusinessMeaningDraft() {
        return businessMeaningDraft;
    }

    public void setBusinessMeaningDraft(String businessMeaningDraft) {
        this.businessMeaningDraft = businessMeaningDraft;
    }

    public String getTechnicalMeaningDraft() {
        return technicalMeaningDraft;
    }

    public void setTechnicalMeaningDraft(String technicalMeaningDraft) {
        this.technicalMeaningDraft = technicalMeaningDraft;
    }

    public List<String> getExampleFiles() {
        return exampleFiles;
    }

    public void setExampleFiles(List<String> exampleFiles) {
        this.exampleFiles = exampleFiles == null ? new ArrayList<>() : exampleFiles;
    }

    public List<FlowtransAttributeDefinition> getCommonAttributes() {
        return commonAttributes;
    }

    public void setCommonAttributes(List<FlowtransAttributeDefinition> commonAttributes) {
        this.commonAttributes = commonAttributes == null ? new ArrayList<>() : commonAttributes;
    }

    public List<String> getOpenQuestions() {
        return openQuestions;
    }

    public void setOpenQuestions(List<String> openQuestions) {
        this.openQuestions = openQuestions == null ? new ArrayList<>() : openQuestions;
    }
}
