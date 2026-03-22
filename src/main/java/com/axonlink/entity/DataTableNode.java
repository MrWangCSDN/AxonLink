package com.axonlink.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_data_table_node")
public class DataTableNode {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属交易 id */
    private Long txId;

    /** 表名，如 USR_INFO */
    private String tableCode;

    /** 显示名 */
    private String name;

    /** 排序号 */
    private Integer sortOrder;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableLogic
    private Integer deleted;
}
