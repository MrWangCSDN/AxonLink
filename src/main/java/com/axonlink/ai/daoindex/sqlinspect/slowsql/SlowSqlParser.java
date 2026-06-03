package com.axonlink.ai.daoindex.sqlinspect.slowsql;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
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
}
