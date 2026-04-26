package com.axonlink.ai.daoindex.sqlinspect.dto;

/**
 * SQL 索引命中评级。
 *
 * <p>三档由规则引擎按最左匹配原则确定性判定：
 * <ul>
 *   <li>{@link #POOR} 差 — 没有任何索引前缀能匹配 SQL 的谓词字段</li>
 *   <li>{@link #GOOD} 良 — 命中了某索引的部分前缀（会额外输出命中了哪个索引）</li>
 *   <li>{@link #EXCELLENT} 优 — SQL 的所有等值谓词完全覆盖某索引的全部列</li>
 * </ul>
 */
public enum IndexRating {
    POOR("差"),
    GOOD("良"),
    EXCELLENT("优"),
    /** 不适用：比如 INSERT VALUES 不需要索引评级。 */
    NOT_APPLICABLE("不适用");

    private final String label;

    IndexRating(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /**
     * 多表场景取最差档（整体评级不优于最差的那张表）。
     * {@code NOT_APPLICABLE} 不参与比较，若全部都是 N/A 则整体为 N/A。
     */
    public static IndexRating worstOf(IndexRating a, IndexRating b) {
        if (a == NOT_APPLICABLE) return b;
        if (b == NOT_APPLICABLE) return a;
        if (a == POOR || b == POOR) return POOR;
        if (a == GOOD || b == GOOD) return GOOD;
        return EXCELLENT;
    }
}
