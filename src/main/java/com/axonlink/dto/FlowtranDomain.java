package com.axonlink.dto;

/**
 * 领域信息 VO（来源：flowtran 表 GROUP BY domain_key）。
 *
 * <p>同时暴露兼容旧接口的 {@code id} / {@code name} / {@code count} 字段，
 * 使前端 DomainSidebar.vue 无需改动即可直接使用。
 */
public class FlowtranDomain {

    /** 领域标识，如 deposit / loan / settlement */
    private String domainKey;
    /** 领域中文名称 */
    private String domainName;
    /** 该领域下的交易总数 */
    private long txCount;
    /** 图标标识（静态映射） */
    private String icon;

    public String getDomainKey() { return domainKey; }
    public void setDomainKey(String domainKey) { this.domainKey = domainKey; }
    public String getDomainName() { return domainName; }
    public void setDomainName(String domainName) { this.domainName = domainName; }
    public long getTxCount() { return txCount; }
    public void setTxCount(long txCount) { this.txCount = txCount; }
    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }

    // ── 兼容旧接口字段（DomainSidebar.vue 使用 domain.id / domain.name / domain.count） ──
    public String getId()    { return domainKey; }
    public String getName()  { return domainName; }
    public long   getCount() { return txCount; }
}
