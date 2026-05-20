package com.axonlink.ai.code.scheduler;

import com.axonlink.ai.code.service.CodeAnalysisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Phase 8 / 08-01 定时框架。仿 QuotaExpirationScheduler。
 * 默认每日 01:00（错开额度过期任务 00:30）；全局开关在 CodeAnalysisService 内判定，
 * 关闭时 runAllAsync() 自身 no-op，故开发/测试环境默认不会触发 git。
 */
@Component
public class CodeAnalysisScheduler {

    private static final Logger log = LoggerFactory.getLogger(CodeAnalysisScheduler.class);

    @Autowired
    private CodeAnalysisService codeAnalysisService;

    @Scheduled(cron = "${code-analysis.cron:0 0 1 * * *}")
    public void run() {
        try {
            codeAnalysisService.runAllAsync();
        } catch (Exception e) {
            log.error("源码提交分析定时任务异常", e);
        }
    }
}
