package com.axonlink.ai.replay.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "axon-link.replay.daily-report-mail")
public class ReplayDailyReportMailProperties {

    private List<String> to = new ArrayList<>();
    private List<String> cc = new ArrayList<>();
    private String subjectPrefix = "对公分布式核心回放问题日报-";
    private String body = "各位好，附件为本批次回放问题日报，请查收。";

    public List<String> getTo() {
        return to;
    }

    public void setTo(List<String> to) {
        this.to = to == null ? new ArrayList<>() : new ArrayList<>(to);
    }

    public List<String> getCc() {
        return cc;
    }

    public void setCc(List<String> cc) {
        this.cc = cc == null ? new ArrayList<>() : new ArrayList<>(cc);
    }

    public String getSubjectPrefix() {
        return subjectPrefix;
    }

    public void setSubjectPrefix(String subjectPrefix) {
        this.subjectPrefix = subjectPrefix;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

}
