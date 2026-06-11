package com.axonlink.ai.daoindex.sqlinspect.slowsql;

import com.axonlink.ai.daoindex.sqlinspect.service.OdbIdProjectIndex;
import org.springframework.stereotype.Component;

/**
 * {@link OdbLocationDomainResolver} 正式实现：基于 {@link OdbIdProjectIndex}
 * （启动期扫描各模块 {@code *.tables.xml} 的根 {@code <schema id>}）。
 *
 * <p>E 列冒号后段形如 {@code Kapp_serl_num.kapp_serl_num.Entity.selectByIndexWithLock_odb1}，
 * 取<b>首段</b>（第一个 "." 前）= schema id，查索引得模块名（如 {@code dept-bcc}）。
 */
@Component
public class TablesXmlOdbLocationDomainResolver implements OdbLocationDomainResolver {

    private final OdbIdProjectIndex index;

    public TablesXmlOdbLocationDomainResolver(OdbIdProjectIndex index) {
        this.index = index;
    }

    @Override
    public String resolveModule(String locationAfterColon) {
        if (locationAfterColon == null || locationAfterColon.isEmpty()) return null;
        int dot = locationAfterColon.indexOf('.');
        String schemaId = dot < 0 ? locationAfterColon : locationAfterColon.substring(0, dot);
        return index.lookupModule(schemaId.trim());
    }
}
