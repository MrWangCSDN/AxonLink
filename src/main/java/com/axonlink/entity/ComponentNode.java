package com.axonlink.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 构件节点
 * prefix: pbcc / pbct / pbcb / pbcp
 */
@Data
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
}
