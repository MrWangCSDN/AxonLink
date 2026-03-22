package com.axonlink.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_domain")
public class Domain {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 领域唯一标识：public / loan / deposit / settlement */
    private String domainKey;

    /** 领域名称 */
    private String name;

    /** 图标标识 */
    private String icon;

    /** 排序号 */
    private Integer sortOrder;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
