package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayIssueWeeklyTaskConfig;
import com.axonlink.ai.replay.persistence.ReplayIssueWeeklyTaskDao;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Validates and replaces the manually controlled current weekly-task batch set. */
@Service
public class ReplayIssueWeeklyTaskService {

    private final ReplayIssueWeeklyTaskDao dao;

    public ReplayIssueWeeklyTaskService(ReplayIssueWeeklyTaskDao dao) {
        this.dao = dao;
    }

    public ReplayIssueWeeklyTaskConfig current() {
        return snapshot();
    }

    public ReplayIssueWeeklyTaskConfig replace(List<String> requestedBatchNames) {
        List<String> normalized = normalize(requestedBatchNames);
        Set<String> available = new TreeSet<>(dao.availableBatchNames());
        List<String> unknown = normalized.stream().filter(name -> !available.contains(name)).toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("出现批次不存在：" + String.join("、", unknown));
        }
        dao.replaceBatchNames(normalized);
        return snapshot();
    }

    private ReplayIssueWeeklyTaskConfig snapshot() {
        return new ReplayIssueWeeklyTaskConfig(
                dao.currentBatchNames(), dao.availableBatchNames(), dao.currentIssueCount());
    }

    private static List<String> normalize(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        TreeSet<String> normalized = new TreeSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) normalized.add(value.trim());
        }
        return List.copyOf(normalized);
    }
}
