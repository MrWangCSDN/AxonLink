package com.axonlink.ai.replay.service;
import java.util.List;
import java.util.Set;
/** Ordered business types shared by transaction and database replay issues. */
public final class ReplayIssueTypes {
    public static final List<String> ALL=List.of("迁移问题","防腐问题","代码问题","新核心下线","参数问题","平台问题","合理差异","规则性差异问题","外围问题","需规问题","其他问题");
    public static final Set<String> NO_ACTION=Set.of("合理差异","规则性差异问题","外围问题");
    private ReplayIssueTypes(){}
}
