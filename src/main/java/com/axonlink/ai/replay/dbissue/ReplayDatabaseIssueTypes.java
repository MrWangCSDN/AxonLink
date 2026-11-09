package com.axonlink.ai.replay.dbissue;

import java.util.List;
import java.util.Set;

/** The issue type catalog and status-specific choices for database replay issues. */
public final class ReplayDatabaseIssueTypes {
    public static final List<String> ALL = List.of(
            "不参与比对", "修改比对规则", "修改迁移规则", "修改代码", "规则性差异", "前后交易差异", "其他问题");
    public static final Set<String> NO_ACTION = Set.of("不参与比对", "规则性差异");
    public static final Set<String> ACTIONABLE = Set.of("修改比对规则", "修改迁移规则", "修改代码", "前后交易差异", "其他问题");

    private ReplayDatabaseIssueTypes() {
    }
}
