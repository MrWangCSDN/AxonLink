package com.axonlink.ai.replay.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reviewer employee numbers configured by replay issue group. */
@Component
@ConfigurationProperties(prefix = "dii.replay.issue-review")
public class ReplayIssueReviewProperties {
    private Map<String, ReviewerGroup> reviewers = new LinkedHashMap<>();

    public Map<String, ReviewerGroup> getReviewers() {
        return reviewers;
    }

    public void setReviewers(Map<String, ReviewerGroup> reviewers) {
        this.reviewers = reviewers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(reviewers);
    }

    public static class ReviewerGroup {
        private List<String> empNos = new ArrayList<>();

        public List<String> getEmpNos() {
            return empNos;
        }

        public void setEmpNos(List<String> empNos) {
            this.empNos = empNos == null ? new ArrayList<>() : new ArrayList<>(empNos);
        }
    }
}
