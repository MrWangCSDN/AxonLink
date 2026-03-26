package com.axonlink.dto;

/**
 * 交易信息 VO（来源：flowtran 表）
 */
public class FlowtranTransaction {

    /** 交易码，如 TC0076 */
    private String id;
    /** 交易名称（中文） */
    private String longname;
    /** 归属领域标识 */
    private String domainKey;
    /** 事务模式：R=查询，A=写入 */
    private String txnMode;
    /** 来源 jar 包/工程 */
    private String fromJar;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getLongname() { return longname; }
    public void setLongname(String longname) { this.longname = longname; }
    public String getDomainKey() { return domainKey; }
    public void setDomainKey(String domainKey) { this.domainKey = domainKey; }
    public String getTxnMode() { return txnMode; }
    public void setTxnMode(String txnMode) { this.txnMode = txnMode; }
    public String getFromJar() { return fromJar; }
    public void setFromJar(String fromJar) { this.fromJar = fromJar; }
}
