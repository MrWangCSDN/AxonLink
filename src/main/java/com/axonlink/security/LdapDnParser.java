package com.axonlink.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.naming.ldap.LdapName;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LDAP DN 解析工具：从事先认证后拿到的 {@code LdapUserDetailsImpl.dn}
 * 字符串里提取 cn（中文真实姓名）。
 *
 * <p>DN 形如：{@code cn=王山河(长亮科技),ou=对公渠道中台建设项目,ou=...}
 * <p>cn 部分会带括号（厂商后缀），本工具去掉括号部分只保留中文姓名。
 */
public final class LdapDnParser {

    private static final Logger log = LoggerFactory.getLogger(LdapDnParser.class);

    /** cn=... 段内容（兼容 RDN 中可能出现的引号转义）。 */
    private static final Pattern CN_PATTERN = Pattern.compile("(^|,)cn=([^,]+)(,|$)", Pattern.CASE_INSENSITIVE);

    private LdapDnParser() {}

    /**
     * 从 LDAP DN 中提取 cn（中文真实姓名），去掉括号后缀。
     *
     * @param dn LDAP 完整 DN（如 {@code cn=王山河(长亮科技),ou=...}）
     * @return 中文姓名（如 {@code 王山河}），提取失败返回 null
     */
    public static String extractCn(String dn) {
        if (dn == null || dn.isBlank()) return null;
        try {
            // 优先用 javax.naming 解析（更严谨）
            LdapName ln = new LdapName(dn);
            for (javax.naming.ldap.Rdn rdn : ln.getRdns()) {
                if ("cn".equalsIgnoreCase(rdn.getType())) {
                    Object v = rdn.getValue();
                    return stripBracket(v == null ? null : String.valueOf(v));
                }
            }
        } catch (Exception e) {
            // 解析失败 fallback 到正则
            log.debug("[ldap-dn] 解析失败，fallback 正则: {}", e.getMessage());
        }
        // fallback：正则匹配第一个 cn=
        Matcher m = CN_PATTERN.matcher(dn);
        if (m.find()) {
            return stripBracket(m.group(2).trim());
        }
        return null;
    }

    /** 去掉括号及其后缀："王山河(长亮科技)" → "王山河" */
    static String stripBracket(String s) {
        if (s == null) return null;
        int idx = s.indexOf('(');
        if (idx > 0) return s.substring(0, idx).trim();
        return s.trim();
    }
}
