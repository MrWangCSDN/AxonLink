package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayConfigOperator;
import com.axonlink.ai.replay.dto.ReplayConfigPersonInfo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.axonlink.ai.replay.service.ReplayConfigServiceCodeResolver.SERVICE_MAPPING_TABLE;

/**
 * 配置服务码到「全量交易人员清单」的映射解析。
 *
 * <p>完整服务码对应映射表的 esf_service_code，交易码取自映射表的 tran_code。
 * 通过人员清单的 old_transaction_code 补充领域、开发人员和行方负责人。
 */
@Service
public class ReplayConfigPersonResolver {

    private final JdbcTemplate jdbc;

    public ReplayConfigPersonResolver(JdbcTemplate diiResultJdbcTemplate) {
        this.jdbc = diiResultJdbcTemplate;
    }

    /** 按最终服务码批量解析人员信息；返回 key 为传入的原始服务码。 */
    public Map<String, ReplayConfigPersonInfo> resolveByServiceCodes(Collection<String> finalServiceCodes) {
        Map<String, ReplayConfigPersonInfo> result = new LinkedHashMap<>();
        if (finalServiceCodes == null || finalServiceCodes.isEmpty()) {
            return result;
        }
        for (String code : finalServiceCodes) {
            if (code != null) {
                result.put(code, null);
            }
        }
        if (result.isEmpty()) {
            return result;
        }
        String placeholders = String.join(",", Collections.nCopies(result.size(), "?"));
        jdbc.query("SELECT z.esf_service_code, z.tran_code, p.old_transaction_code AS person_code, "
                        + "p.domain, p.developer, p.bank_owner, p.bank_owner_emp_nos "
                        + "FROM " + SERVICE_MAPPING_TABLE + " z "
                        + "LEFT JOIN dii_replay_transaction_person p ON p.old_transaction_code = z.tran_code "
                        + "WHERE z.esf_service_code IN (" + placeholders + ")",
                rs -> {
                    String code = rs.getString("esf_service_code");
                    if (result.containsKey(code)) {
                        result.putIfAbsent(code, new ReplayConfigPersonInfo(rs.getString("tran_code"),
                                rs.getString("domain"), rs.getString("developer"), rs.getString("bank_owner"),
                                rs.getString("bank_owner_emp_nos"), rs.getString("person_code") != null));
                    }
                }, result.keySet().toArray());
        return result;
    }

    /**
     * 解析某工号作为行方负责人可审核的全部最终服务码集合。
     *
     * <p>路径：人员清单 bank_owner_emp_nos 命中该工号 → old_transaction_code →
     * 映射表 tran_code → 完整 esf_service_code。
     */
    public Set<String> findReviewableServiceCodes(String empNo) {
        return findServiceCodesByIdentity("bank_owner_emp_nos", empNo);
    }

    /** 解析某登录账号作为开发人员负责的全部最终服务码集合。 */
    public Set<String> findDevelopedServiceCodes(String username) {
        return findServiceCodesByIdentity("developer_usernames", username);
    }

    private Set<String> findServiceCodesByIdentity(String identityColumn, String identity) {
        if (identity == null || identity.isBlank()) {
            return Set.of();
        }
        String target = identity.trim();
        List<String> transactionCodes = jdbc.query(
                        "SELECT old_transaction_code, " + identityColumn
                                + " FROM dii_replay_transaction_person WHERE TRIM(COALESCE("
                                + identityColumn + ",'')) <> ''",
                        (rs, rowNum) -> new String[]{rs.getString("old_transaction_code"),
                                rs.getString(identityColumn)})
                .stream()
                .filter(row -> splitIdentities(row[1]).contains(target))
                .map(row -> row[0])
                .filter(code -> code != null && !code.isBlank())
                .distinct()
                .toList();
        if (transactionCodes.isEmpty()) {
            return Set.of();
        }
        String placeholders = String.join(",", Collections.nCopies(transactionCodes.size(), "?"));
        List<String> esfCodes = jdbc.queryForList(
                "SELECT DISTINCT esf_service_code FROM " + SERVICE_MAPPING_TABLE
                        + " WHERE tran_code IN (" + placeholders + ")",
                String.class, transactionCodes.toArray());
        return new LinkedHashSet<>(esfCodes);
    }

    /** 全部领域选项，取自全量交易人员清单。 */
    public List<String> listDomains() {
        return jdbc.queryForList(
                "SELECT DISTINCT domain FROM dii_replay_transaction_person "
                        + "WHERE TRIM(COALESCE(domain,'')) <> '' ORDER BY domain",
                String.class);
    }

    public List<String> listDevelopers() {
        return jdbc.queryForList(
                "SELECT DISTINCT developer FROM dii_replay_transaction_person "
                        + "WHERE TRIM(COALESCE(developer,'')) <> '' ORDER BY developer",
                String.class);
    }

    /** 按配置表实际存储的服务码筛选列表展示的映射字段。 */
    public Set<String> resolveConfigServiceCodes(String resource, String domain, String developer,
                                                 boolean domainEmpty, boolean developerEmpty,
                                                 boolean internalTransactionCodeEmpty) {
        String table;
        String codeColumn;
        switch (resource) {
            case "unconditional-ignores" -> { table = "dii_replay_unconditional_ignore"; codeColumn = "tran_code"; }
            case "conditional-ignores" -> { table = "dii_replay_conditional_rmove"; codeColumn = "orig_trcd"; }
            case "error-code-ignores" -> { table = "dii_replay_error_code_ignore_config"; codeColumn = "service_code"; }
            case "sort-fields" -> { table = "dii_replay_sort_field"; codeColumn = "orig_trcd"; }
            case "message-sequence-ignores" -> {
                table = "dii_replay_message_sequence_ignore";
                codeColumn = "service_code";
            }
            case "one-side-error-code-ignores" -> {
                table = "dii_replay_one_side_error_code_ignore";
                codeColumn = "service_code";
            }
            default -> throw new IllegalArgumentException("不支持的忽略清单类型");
        }
        if ((domain == null || domain.isBlank()) && (developer == null || developer.isBlank())
                && !domainEmpty && !developerEmpty && !internalTransactionCodeEmpty) {
            return null;
        }
        List<String> configCodes = jdbc.queryForList(
                "SELECT DISTINCT " + codeColumn + " FROM " + table, String.class);
        Map<String, ReplayConfigPersonInfo> personByCode = resolveByServiceCodes(configCodes);
        Set<String> result = new LinkedHashSet<>();
        for (String configCode : configCodes) {
            ReplayConfigPersonInfo person = personByCode.get(configCode);
            boolean domainMatches = domainEmpty
                    ? person == null || isBlank(person.domain())
                    : domain == null || domain.isBlank()
                    || person != null && Objects.equals(person.domain(), domain.trim());
            boolean developerMatches = developerEmpty
                    ? person == null || isBlank(person.developer())
                    : developer == null || developer.isBlank()
                    || person != null && Objects.equals(person.developer(), developer.trim());
            boolean transactionCodeMatches = !internalTransactionCodeEmpty
                    || person == null || isBlank(person.oldTransactionCode());
            if (domainMatches && developerMatches && transactionCodeMatches) {
                result.add(configCode);
            }
        }
        return result;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /** 服务码集合求交；任一侧为 {@code null} 表示不限制。 */
    public static Set<String> intersect(Set<String> left, Set<String> right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        Set<String> result = new LinkedHashSet<>(left);
        result.retainAll(right);
        return result;
    }

    /** 服务码集合求三方交集；任一侧为 {@code null} 表示不限制。 */
    public static Set<String> intersect(Set<String> first, Set<String> second, Set<String> third) {
        return intersect(intersect(first, second), third);
    }

    /** 登录人工号是否命中行方负责人工号列表。 */
    public static boolean matchesBankOwner(ReplayConfigPersonInfo info, ReplayConfigOperator operator) {
        if (info == null || operator == null || operator.empNo() == null || operator.empNo().isBlank()) {
            return false;
        }
        return splitIdentities(info.bankOwnerEmpNos()).contains(operator.empNo().trim());
    }

    /**
     * 审核按钮的禁用原因；返回 {@code null} 表示可审核。
     */
    public static String reviewDisabledReason(ReplayConfigPersonInfo info, ReplayConfigOperator operator,
                                              int reviewStatus) {
        if (info == null || !info.personMapped()) {
            return "无审核人";
        }
        if (reviewStatus == 1) {
            return "已审核";
        }
        return matchesBankOwner(info, operator) ? null : reviewForbiddenMessage(info);
    }

    /** 行方负责人姓名（去掉工号括号、按分隔符拆分去重），用于“请联系X进行审核”。 */
    public static String reviewerContactNames(ReplayConfigPersonInfo info) {
        if (info == null || info.bankOwner() == null || info.bankOwner().isBlank()) {
            return "";
        }
        Set<String> names = new LinkedHashSet<>();
        for (String part : info.bankOwner().split("[、,，;；]")) {
            String name = part.replaceAll("[（(][^）)]*[）)]", "").trim();
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return String.join("、", names);
    }

    /** 无审核权限提示：带上行方负责人姓名。 */
    public static String reviewForbiddenMessage(ReplayConfigPersonInfo info) {
        if (info == null || !info.personMapped()) {
            return "无审核人";
        }
        String names = reviewerContactNames(info);
        return names.isEmpty() ? "没有审核权限" : "没有权限，请联系" + names + "进行审核";
    }

    private static List<String> splitIdentities(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(value.split("[、,，;；\\s]+"))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .toList();
    }
}
