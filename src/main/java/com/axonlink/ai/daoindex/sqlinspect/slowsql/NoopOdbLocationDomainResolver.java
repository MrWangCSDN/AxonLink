package com.axonlink.ai.daoindex.sqlinspect.slowsql;

import org.springframework.stereotype.Component;

/**
 * {@link OdbLocationDomainResolver} 占位实现：恒返回 {@code null}（odb 来源领域=其他）。
 *
 * <p>用户的「odb 文件 → 模块」查找逻辑整理好后，替换/新增一个 @Component 实现并
 * 删除本类（或加 @ConditionalOnMissingBean 语义自行裁决），导入链路即自动生效。
 */
@Component
public class NoopOdbLocationDomainResolver implements OdbLocationDomainResolver {

    @Override
    public String resolveModule(String locationAfterColon) {
        return null;   // TODO：用户提供 odb 查找逻辑后填入真实实现
    }
}
