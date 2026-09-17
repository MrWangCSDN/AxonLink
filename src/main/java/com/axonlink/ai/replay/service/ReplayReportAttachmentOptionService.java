package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayReportAttachmentOption;
import com.axonlink.ai.replay.dto.ReplayReportAttachmentOptionPage;
import com.axonlink.ai.replay.dto.ReplayReportPeriod;
import com.axonlink.ai.replay.persistence.ReplayDailyDataDao;
import com.axonlink.ai.replay.persistence.ReplayWeeklyReportDao;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class ReplayReportAttachmentOptionService {
    private final ReplayDailyDataDao dailyDataDao;
    private final ReplayWeeklyReportDao weeklyReportDao;

    public ReplayReportAttachmentOptionService(ReplayDailyDataDao dailyDataDao,
                                               ReplayWeeklyReportDao weeklyReportDao) {
        this.dailyDataDao = dailyDataDao;
        this.weeklyReportDao = weeklyReportDao;
    }

    public ReplayReportAttachmentOptionPage search(String keyword, String period,
                                                   String family, int page, int size) {
        String normalizedPeriod = period == null ? "ALL" : period.trim().toUpperCase();
        if (!List.of("ALL", "DAILY", "WEEKLY").contains(normalizedPeriod))
            throw new IllegalArgumentException("报告周期错误");
        int normalizedPage = Math.max(0, page);
        int normalizedSize = Math.max(1, Math.min(100, size));
        List<ReplayReportAttachmentOption> all = new ArrayList<>();
        if (!"WEEKLY".equals(normalizedPeriod))
            all.addAll(dailyDataDao.findReportAttachmentOptions(keyword, family));
        if (!"DAILY".equals(normalizedPeriod))
            all.addAll(weeklyReportDao.findReportAttachmentOptions(keyword, family));
        all.sort(Comparator.comparing(ReplayReportAttachmentOption::generatedAt).reversed()
                .thenComparing(Comparator.comparing(ReplayReportAttachmentOption::businessKey).reversed()));
        int from = Math.min(all.size(), normalizedPage * normalizedSize);
        int to = Math.min(all.size(), from + normalizedSize);
        return new ReplayReportAttachmentOptionPage(all.subList(from, to), normalizedPage, normalizedSize, all.size());
    }
}
