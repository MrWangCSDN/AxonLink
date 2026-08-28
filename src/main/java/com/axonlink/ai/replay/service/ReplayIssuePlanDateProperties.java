package com.axonlink.ai.replay.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Employee numbers, or usernames for users without an employee number, allowed to edit dates by group. */
@Component
@ConfigurationProperties(prefix = "dii.replay.issue-plan-date")
public class ReplayIssuePlanDateProperties {
    private Map<String, EditorGroup> editors = new LinkedHashMap<>();

    public Map<String, EditorGroup> getEditors() {
        return editors;
    }

    public void setEditors(Map<String, EditorGroup> editors) {
        this.editors = editors == null ? new LinkedHashMap<>() : new LinkedHashMap<>(editors);
    }

    public static class EditorGroup {
        private List<String> empNos = new ArrayList<>();

        public List<String> getEmpNos() {
            return empNos;
        }

        public void setEmpNos(List<String> empNos) {
            this.empNos = empNos == null ? new ArrayList<>() : new ArrayList<>(empNos);
        }
    }
}
