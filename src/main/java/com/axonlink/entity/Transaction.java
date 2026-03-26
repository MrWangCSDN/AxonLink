package com.axonlink.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.time.LocalDateTime;

@TableName("t_transaction")
public class Transaction {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 交易编码，如 TD0101 */
    private String txCode;

    /** 交易名称 */
    private String name;

    /** 所属领域 id */
    private Long domainId;

    /** 链路层数 */
    private Integer layers;

    /** 排序号 */
    private Integer sortOrder;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTxCode() { return txCode; }
    public void setTxCode(String txCode) { this.txCode = txCode; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Long getDomainId() { return domainId; }
    public void setDomainId(Long domainId) { this.domainId = domainId; }
    public Integer getLayers() { return layers; }
    public void setLayers(Integer layers) { this.layers = layers; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer deleted) { this.deleted = deleted; }
}
