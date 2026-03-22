package com.axonlink.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

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
@Data
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
}
