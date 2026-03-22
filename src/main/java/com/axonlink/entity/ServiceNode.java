package com.axonlink.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 服务节点
 * prefix: pbs / pcs
 */
@Data
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
}
