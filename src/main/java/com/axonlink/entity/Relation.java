package com.axonlink.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.time.LocalDateTime;

/**
 * 通用关联关系表
 *
 * relation_type:
 *   SERVICE_TO_SERVICE   - pcs → pbs（编码调用）
 *   SERVICE_TO_COMPONENT - pbs → 构件
 *   COMPONENT_TO_COMPONENT - pbcb/pbcp → pbcc/pbct
 *   COMPONENT_TO_DATA    - 构件 → 数据表
 */
@TableName("t_relation")
public class Relation {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属交易 id */
    private Long txId;

    /** 关系类型 */
    private String relationType;

    /** 来源编码（服务码/构件码） */
    private String fromCode;

    /** 目标编码（服务码/构件码/表名） */
    private String toCode;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableLogic
    private Integer deleted;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTxId() { return txId; }
    public void setTxId(Long txId) { this.txId = txId; }
    public String getRelationType() { return relationType; }
    public void setRelationType(String relationType) { this.relationType = relationType; }
    public String getFromCode() { return fromCode; }
    public void setFromCode(String fromCode) { this.fromCode = fromCode; }
    public String getToCode() { return toCode; }
    public void setToCode(String toCode) { this.toCode = toCode; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer deleted) { this.deleted = deleted; }
}
