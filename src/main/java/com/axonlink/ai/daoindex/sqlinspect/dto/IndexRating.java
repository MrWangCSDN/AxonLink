package com.axonlink.ai.daoindex.sqlinspect.dto;

/**
 * SQL 巡检「是否需整改」判定（v3 起不再是"优良差"三档评级）。
 *
 * <p>由 EXPLAIN 真实执行计划派生，结果仅两类 + 一个不适用 + 一个弃用值：
 * <ul>
 *   <li>{@link #POOR} 待整改 — 需整改候选：全表扫描，或虽命中索引但 EXPLAIN
 *       估算扫描行数 ≥ 1000；会送 LLM 解读</li>
 *   <li>{@link #EXCELLENT} 无需整改 — 命中索引且估算扫描行数 &lt; 1000；不送 LLM</li>
 *   <li>{@link #NOT_APPLICABLE} 不适用 — INSERT(VALUES/SELECT) 不参与判定，不 EXPLAIN 不 LLM</li>
 *   <li>{@link #GOOD} <b>已弃用</b> — 旧"良"档，新口径不再产出；保留枚举值仅为
 *       不破坏 {@link #worstOf} 等历史逻辑与历史数据兼容</li>
 * </ul>
 *
 * <p>注意：看板 SQL 用的是枚举<b>名</b>字符串（{@code 'POOR'}/{@code 'EXCELLENT'}/
 * {@code 'NOT_APPLICABLE'}），不是中文 label；{@link #getLabel()} 仅
 * {@code SqlInspectionResult}/{@code TableRating} 用于 rating_label 展示列。
 */
public enum IndexRating {
    POOR("待整改"),
    /** 已弃用：旧"良"档，新口径不再产出，仅为兼容 {@link #worstOf} 与历史数据保留。 */
    GOOD("良"),
    EXCELLENT("无需整改"),
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
