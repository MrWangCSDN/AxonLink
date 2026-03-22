package com.axonlink.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.axonlink.entity.Transaction;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TransactionMapper extends BaseMapper<Transaction> {

    /** 按领域 id 查交易列表，按 sort_order + tx_code 排序 */
    List<Transaction> selectByDomainId(@Param("domainId") Long domainId);

    /** 原生 COUNT，绕过 @TableLogic，用于统计展示（定义在 TransactionMapper.xml） */
    long countAll();
}
