package com.axonlink.ai.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * flowtrans.xml 标签词典返回体。
 */
public class FlowtransTagCatalogResponse {

    private String schemaType;
    private String version;
    private Integer sampleFileCount;
    private String draftNote;
    private List<FlowtransTagDefinition> tags = new ArrayList<>();

    public String getSchemaType() {
        return schemaType;
    }

    public void setSchemaType(String schemaType) {
        this.schemaType = schemaType;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public Integer getSampleFileCount() {
        return sampleFileCount;
    }

    public void setSampleFileCount(Integer sampleFileCount) {
        this.sampleFileCount = sampleFileCount;
    }

    public String getDraftNote() {
        return draftNote;
    }

    public void setDraftNote(String draftNote) {
        this.draftNote = draftNote;
    }

    public List<FlowtransTagDefinition> getTags() {
        return tags;
    }

    public void setTags(List<FlowtransTagDefinition> tags) {
        this.tags = tags == null ? new ArrayList<>() : tags;
    }
}
