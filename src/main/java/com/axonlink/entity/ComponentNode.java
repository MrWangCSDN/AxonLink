package com.axonlink.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.time.LocalDateTime;

/**
 * 构件节点
 * prefix: pbcc / pbct / pbcb / pbcp
 */
@TableName("t_component_node")
public class ComponentNode {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属交易 id */
    private Long txId;

    /** 构件类型：pbcc / pbct / pbcb / pbcp */
    private String prefix;

    /** 构件编码，如 COMP_AUTH_001 */
    private String componentCode;

    /** 构件名称 */
    private String name;

    /** 跨领域标签 */
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
    public String getComponentCode() { return componentCode; }
    public void setComponentCode(String componentCode) { this.componentCode = componentCode; }
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
