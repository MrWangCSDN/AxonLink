package com.axonlink.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.time.LocalDateTime;

/**
 * 服务节点
 * prefix: pbs / pcs
 */
@TableName("t_service_node")
public class ServiceNode {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属交易 id */
    private Long txId;

    /** 服务类型：pbs / pcs */
    private String prefix;

    /** 服务编码，如 SVC_AUTH_001 */
    private String serviceCode;

    /** 服务名称 */
    private String name;

    /** 跨领域标签（跨领域时填写来源领域名称） */
    private String crossDomain;

    /** 排序号 */
    private Integer sortOrder;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableLogic
    private Integer deleted;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTxId() { return txId; }
    public void setTxId(Long txId) { this.txId = txId; }
    public String getPrefix() { return prefix; }
    public void setPrefix(String prefix) { this.prefix = prefix; }
    public String getServiceCode() { return serviceCode; }
    public void setServiceCode(String serviceCode) { this.serviceCode = serviceCode; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCrossDomain() { return crossDomain; }
    public void setCrossDomain(String crossDomain) { this.crossDomain = crossDomain; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer deleted) { this.deleted = deleted; }
}
