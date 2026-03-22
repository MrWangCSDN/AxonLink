package com.axonlink.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
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
}
