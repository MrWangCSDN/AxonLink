package com.axonlink.service;

import com.axonlink.dto.FlowtranDomain;
import com.axonlink.dto.FlowtranTransaction;

import java.util.List;
import java.util.Map;

/**
 * flowtran 数据服务接口。
 *
 * <p>以 flowtran / flow_step 表为数据源，驱动 AxonLink 的业务领域和交易展示。
 * 所有方法均不在内部阻塞，连接失败时返回空集合或降级结果。
 */
public interface FlowtranService {

    /**
     * 查询所有领域列表（按 flowtran.domain_key 分组统计）。
     *
     * @return 领域 VO 列表，txCount 为该领域下的交易总数；数据源不可用时返回空列表
     */
    List<FlowtranDomain> listDomains();

    /**
     * 分页查询某领域下的交易列表。
     *
     * @param domainKey 领域标识，如 {@code dept}
     * @param page      页码，从 1 开始
     * @param size      每页条数
     * @param keyword   关键词（可为 null），模糊匹配 id 或 longname
     * @return 包含 list(List&lt;FlowtranTransaction&gt;), total(long), page(int), size(int) 的 Map
     */
    Map<String, Object> listTransactions(String domainKey, int page, int size, String keyword);

    /**
     * 查询某交易的完整调用链路（flow_step 步骤 + ServiceNodeCache 富化）。
     *
     * @param txId flowtran.id，如 {@code TC0033}
     * @return FlowtranChain VO；txId 不存在时返回 null；缓存尚未就绪时返回原始节点 + cacheStatus
     */
    Map<String, Object> getChain(String txId);
}
