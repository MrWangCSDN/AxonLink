package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayTransactionPersonImportError;
import com.axonlink.ai.replay.dto.ReplayTransactionPersonImportResult;
import com.axonlink.ai.replay.dto.ReplayTransactionPersonRow;
import com.axonlink.ai.replay.persistence.ReplayTransactionPersonDao;
import com.axonlink.ai.user.entity.SysUser;
import com.axonlink.ai.user.persistence.SysUserDao;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ReplayTransactionPersonImportService {
    private static final Pattern NAME_WITH_USERNAME = Pattern.compile("^(.+?)\\(([^()]+)\\)$");
    private final ReplayTransactionPersonExcelParser parser;
    private final ReplayTransactionPersonDao dao;
    private final SysUserDao userDao;

    public ReplayTransactionPersonImportService(ReplayTransactionPersonExcelParser parser,
                                                ReplayTransactionPersonDao dao, SysUserDao userDao) {
        this.parser = parser; this.dao = dao; this.userDao = userDao;
    }

    public ReplayTransactionPersonImportResult importFile(MultipartFile file) throws IOException {
        var workbook = parser.parse(file);
        List<ReplayTransactionPersonImportError> errors = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        List<ReplayTransactionPersonRow> rows = new ArrayList<>();
        LocalDateTime importedAt = LocalDateTime.now();
        for (var raw : workbook.rows()) {
            if (raw.domain() == null || raw.domain().isBlank()) {
                errors.add(error(raw, "领域", raw.domain(), "领域不能为空"));
            }
            String code = raw.oldTransactionCode() == null ? "" : raw.oldTransactionCode().trim();
            if (code.isBlank()) errors.add(error(raw, "老交易码", raw.oldTransactionCode(), "老交易码不能为空"));
            else if (!codes.add(code)) errors.add(error(raw, "老交易码", code, "Excel 内老交易码重复"));
            Match developers = matchPeople(raw.developer(), raw.rowNumber(), "开发人员", true, errors);
            Match owners = matchPeople(raw.bankOwner(), raw.rowNumber(), "行方负责人", false, errors);
            rows.add(new ReplayTransactionPersonRow(null, text(raw.domain()), code, text(raw.oldTransactionName()),
                    developers.displayNames(), developers.identities(), owners.displayNames(), owners.identities(), importedAt));
        }
        if (!errors.isEmpty()) return new ReplayTransactionPersonImportResult(false, workbook.rows().size(), 0, errors.size(), errors);
        dao.replaceAll(rows, importedAt);
        return new ReplayTransactionPersonImportResult(true, rows.size(), rows.size(), 0, List.of());
    }

    private Match matchPeople(String raw, int row, String column, boolean developer,
                              List<ReplayTransactionPersonImportError> errors) {
        if (raw == null || raw.isBlank()) return Match.EMPTY;
        List<String> display = Arrays.stream(raw.split("、" )).map(String::trim).filter(s -> !s.isBlank()).toList();
        List<String> identities = new ArrayList<>();
        List<String> displayNames = new ArrayList<>();
        for (String token : display) {
            Matcher matcher = NAME_WITH_USERNAME.matcher(token);
            String name = matcher.matches() ? matcher.group(1).trim() : token;
            String username = matcher.matches() ? matcher.group(2).trim() : null;
            List<SysUser> users = username == null
                    ? userDao.findActiveByExactRealName(name)
                    : userDao.findActiveByExactUsernameAndRealName(username, name);
            if (users.isEmpty()) {
                errors.add(new ReplayTransactionPersonImportError(row, column, token,
                        username == null ? "人员姓名未匹配到有效用户" : "姓名和 username 未匹配到同一有效用户"));
            } else if (users.size() > 1) {
                errors.add(new ReplayTransactionPersonImportError(row, column, token, "人员姓名匹配到多个有效用户"));
            } else {
                SysUser user = users.get(0);
                if (!developer && (user.getEmpNo() == null || user.getEmpNo().isBlank())) {
                    errors.add(new ReplayTransactionPersonImportError(row, column, token, "匹配用户缺少 emp_no 工号"));
                } else {
                    displayNames.add(user.getRealName() + "(" + user.getUsername() + ")");
                    identities.add(developer ? user.getUsername() : user.getEmpNo());
                }
            }
        }
        return new Match(emptyJoin(displayNames), emptyJoin(identities));
    }

    private ReplayTransactionPersonImportError error(ReplayTransactionPersonExcelParser.RawRow row, String column, String value, String reason) {
        return new ReplayTransactionPersonImportError(row.rowNumber(), column, value == null ? "" : value, reason);
    }

    private String text(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private String emptyJoin(List<String> values) { return values.isEmpty() ? null : String.join("、", values); }
    private record Match(String displayNames, String identities) { static final Match EMPTY = new Match(null, null); }
}
