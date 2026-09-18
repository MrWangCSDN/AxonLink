package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayReportPeriod;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReplayReportMailBodyComposerTest {

    private final ReplayReportMailBodyComposer composer = new ReplayReportMailBodyComposer();

    @Test
    void composesDailyQueryAndAccountingInFixedOrder() {
        String body = composer.compose(ReplayReportPeriod.DAILY, List.of(
                family("DZ", 20, 18, 200),
                family("RPT", 10, 9, 100)));

        assertThat(body).isEqualTo("""
                各位领导、老师：
                查询交易总交易10，本轮回放实发交易9，采集交易量100，实发交易量100；
                账务交易总交易20，本轮回放实发交易18，采集交易量200，实发交易量200。""");
    }

    @Test
    void composesWeeklyAccountingOnly() {
        String body = composer.compose(ReplayReportPeriod.WEEKLY, List.of(
                family("DZ", 20, 18, 200)));

        assertThat(body).isEqualTo("""
                各位领导、老师：
                本周回放比对主要内容如下，请查阅，谢谢。
                账务交易总交易20，本轮回放实发交易18，采集交易量200，实发交易量200。""");
    }

    @Test
    void composesDailyQueryOnly() {
        String body = composer.compose(ReplayReportPeriod.DAILY, List.of(
                family("RPT", 10, 9, 100)));

        assertThat(body).isEqualTo("""
                各位领导、老师：
                查询交易总交易10，本轮回放实发交易9，采集交易量100，实发交易量100。""");
    }

    private static ReplayReportMailBodyComposer.FamilyMetrics family(
            String family, long expected, long actual, long collected) {
        return new ReplayReportMailBodyComposer.FamilyMetrics(family, expected, actual, collected);
    }
}
