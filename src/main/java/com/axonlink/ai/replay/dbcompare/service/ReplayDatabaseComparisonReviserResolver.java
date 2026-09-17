package com.axonlink.ai.replay.dbcompare.service;

import com.axonlink.ai.user.entity.SysUser;
import com.axonlink.ai.user.persistence.SysUserDao;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ReplayDatabaseComparisonReviserResolver {

    private static final Pattern DISPLAY_PATTERN = Pattern.compile(
            "^\\s*(.*?)\\s*[（(]\\s*([^（）()]+)\\s*[）)]\\s*$");

    private final SysUserDao userDao;

    public ReplayDatabaseComparisonReviserResolver(SysUserDao userDao) {
        this.userDao = userDao;
    }

    public Resolution resolve(String input) {
        String normalized = input == null ? "" : input.trim();
        if (normalized.isEmpty()) {
            return Resolution.empty();
        }
        Matcher display = DISPLAY_PATTERN.matcher(normalized);
        if (display.matches()) {
            List<SysUser> matches = userDao.findActiveByExactUsernameAndRealName(
                    display.group(2).trim(), display.group(1).trim());
            return matches.size() == 1
                    ? Resolution.resolved(matches.get(0))
                    : Resolution.failed("姓名与账号不匹配");
        }
        List<SysUser> names = userDao.findActiveByExactRealName(normalized);
        if (names.size() == 1) {
            return Resolution.resolved(names.get(0));
        }
        if (names.size() > 1) {
            String candidates = names.stream().map(SysUser::getUsername)
                    .filter(value -> value != null && !value.isBlank())
                    .distinct().sorted().reduce((left, right) -> left + "、" + right).orElse("");
            return Resolution.failed("姓名不唯一，可选账号：" + candidates);
        }
        return Resolution.failed("人员不存在或已停用");
    }

    public record Resolution(boolean blank, SysUser user, String reason) {

        private static Resolution empty() {
            return new Resolution(true, null, null);
        }

        private static Resolution resolved(SysUser user) {
            return new Resolution(false, user, null);
        }

        private static Resolution failed(String reason) {
            return new Resolution(false, null, reason);
        }

        public boolean valid() {
            return blank || user != null;
        }

        public String displayName() {
            if (user == null) {
                return "";
            }
            return user.getRealName() + "（" + user.getUsername() + "）";
        }
    }
}
