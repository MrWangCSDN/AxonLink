package com.axonlink.ai.daoindex.sqlinspect.slowsql;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 慢SQL导入纯函数：耗时解析、抽象SQL哈希、轮次生成。
 * 无 DB / 无 Spring 依赖，可独立单测。
 */
public final class SlowSqlParser {

    private SlowSqlParser() {}

    private static final Pattern LEADING_DIGITS = Pattern.compile("(\\d+)");
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE; // yyyyMMdd

    /** "20838ms" → 20838；取首个数字串（去逗号）；无数字 → 0。 */
    public static long parseCostMs(String raw) {
        if (raw == null) return 0L;
        String s = raw.replace(",", "").trim();
        Matcher m = LEADING_DIGITS.matcher(s);
        if (!m.find()) return 0L;
        try {
            return Long.parseLong(m.group(1));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** trim 后算 SHA-256 十六进制；用于"精确匹配抽象SQL文本"。 */
    public static String sha256Hex(String text) {
        String t = text == null ? "" : text.trim();
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(t.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * 生成下一个轮次号：{@code yyyyMMdd-(当日最大序号+1)}。
     * @param date          导入日期
     * @param existingRounds 库中已有轮次（任意日期，方法内自行按前缀过滤）
     */
    public static String nextRound(LocalDate date, List<String> existingRounds) {
        String prefix = date.format(YYYYMMDD);
        int max = 0;
        if (existingRounds != null) {
            for (String r : existingRounds) {
                if (r == null || !r.startsWith(prefix + "-")) continue;
                try {
                    max = Math.max(max, Integer.parseInt(r.substring(prefix.length() + 1)));
                } catch (NumberFormatException ignore) {
                    // 脏数据跳过
                }
            }
        }
        return prefix + "-" + (max + 1);
    }

    // ── serviceName 派生 领域 / 类型 ──────────────────────────────────────────
    // serviceName 形如 ccbs-<领域码>-<类型码>，如 ccbs-dept-online / ccbs-public-batch。
    // 用包含匹配（大小写无关），对前缀变体鲁棒。

    public static final String OTHER = "其他";

    /** 领域码 → 中文领域。 */
    private static final Map<String, String> DOMAIN_MAP = new LinkedHashMap<>();
    /** 类型码 → 中文类型。 */
    private static final Map<String, String> BIZ_TYPE_MAP = new LinkedHashMap<>();
    static {
        DOMAIN_MAP.put("dept", "存款");
        DOMAIN_MAP.put("loan", "贷款");
        DOMAIN_MAP.put("comm", "公共");
        DOMAIN_MAP.put("sett", "结算");
        DOMAIN_MAP.put("public", "全领域");
        BIZ_TYPE_MAP.put("hotspot", "热点账户");
        BIZ_TYPE_MAP.put("batch", "批量");
        BIZ_TYPE_MAP.put("online", "联机");
    }

    /** 从 serviceName 派生中文领域：dept/loan/comm/sett/public → 存款/贷款/公共/结算/全领域；无匹配→其他。 */
    public static String domainOf(String serviceName) {
        return mapByContains(serviceName, DOMAIN_MAP);
    }

    /** 从 serviceName 派生类型：online/hotspot/batch → 联机/热点账户/批量；无匹配→其他。 */
    public static String bizTypeOf(String serviceName) {
        return mapByContains(serviceName, BIZ_TYPE_MAP);
    }

    private static String mapByContains(String serviceName, Map<String, String> map) {
        if (serviceName == null) return OTHER;
        String s = serviceName.toLowerCase();
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (s.contains(e.getKey())) return e.getValue();
        }
        return OTHER;
    }
}
