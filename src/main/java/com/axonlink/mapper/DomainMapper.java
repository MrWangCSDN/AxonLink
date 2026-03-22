package com.axonlink.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.axonlink.entity.Domain;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DomainMapper extends BaseMapper<Domain> {

    @Select("SELECT COUNT(*) FROM t_domain")
    long countAll();
}
