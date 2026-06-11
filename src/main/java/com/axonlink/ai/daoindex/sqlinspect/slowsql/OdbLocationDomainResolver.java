package com.axonlink.ai.daoindex.sqlinspect.slowsql;

/**
 * 慢SQL v2：odb 来源 → 模块名 解析器。
 *
 * <p>E 列形如 {@code S010010048.CorpDmdOpnccnt:Kapp_serl_num.kapp_serl_num.Entity.selectByIndexWithLock_odb1}
 * （含 ":" 且含 "Entity"）判定为 odb 来源；本接口负责把<b>冒号后段</b>解析回所属模块
 * （如 {@code dept-bcc}），上层再映射成中文领域。
 *
 * <p>与 nsql 的 {@link com.axonlink.ai.daoindex.sqlinspect.service.NsqlIdProjectIndex}
 * 对称：nsql 走内存索引已有实现；odb 的文件查找逻辑由用户后续提供，填入实现类即可，
 * 调用方（SlowSqlImportService）零改动。
 */
public interface OdbLocationDomainResolver {

    /**
     * @param locationAfterColon E 列冒号后段，如
     *                           {@code Kapp_serl_num.kapp_serl_num.Entity.selectByIndexWithLock_odb1}
     * @return 模块名（如 {@code dept-bcc}）；解析不出返回 {@code null}（上层落「其他」领域）
     */
    String resolveModule(String locationAfterColon);
}
