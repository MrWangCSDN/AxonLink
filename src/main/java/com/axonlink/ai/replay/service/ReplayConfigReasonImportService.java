package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.ReplayConfigOperator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class ReplayConfigReasonImportService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ReplayUnconditionalIgnoreReasonExcelParser parser;

    public ReplayConfigReasonImportService(JdbcTemplate diiResultJdbcTemplate,
                                           ReplayUnconditionalIgnoreReasonExcelParser parser) {
        this.jdbc = diiResultJdbcTemplate;
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        this.parser = parser;
    }

    public Result importReasons(String resource, MultipartFile file, ReplayConfigOperator operator) throws IOException {
        Format format = switch (resource) {
            case "conditional-ignores" -> Format.CONDITIONAL;
            case "sort-fields" -> Format.SORT;
            case "error-code-ignores" -> Format.ERROR_CODE;
            default -> throw new IllegalArgumentException("该页签不支持原因导入");
        };
        return importValues(format, "ignore_reason", file, operator, ReplayConfigValidation::requireIgnoreReason);
    }

    public ChineseNameResult importChineseNames(String resource, MultipartFile file, ReplayConfigOperator operator) throws IOException {
        Format format = switch (resource) {
            case "unconditional-ignores" -> Format.UNCONDITIONAL;
            case "conditional-ignores" -> Format.CONDITIONAL;
            case "sort-fields" -> Format.SORT;
            default -> throw new IllegalArgumentException("该页签不支持字段中文名称导入");
        };
        Result result = importValues(format, "field_chinese_name", file, operator,
                ReplayConfigValidation::requireFieldChineseName);
        return new ChineseNameResult(result.totalRows(), result.updatedRows(), result.skippedExistingReasonRows(),
                result.unmatchedRows(), result.invalidRows(), result.ambiguousRows());
    }

    private Result importValues(Format format, String column, MultipartFile file, ReplayConfigOperator operator,
                                java.util.function.UnaryOperator<String> validateValue) throws IOException {
        List<ImportRow> rows = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (var row : parser.parse(file, format.columns.size() + 1)) {
            try {
                rows.add(validate(format, row, validateValue));
            } catch (IllegalArgumentException ex) {
                errors.add("第 " + row.rowNumber() + " 行：" + ex.getMessage());
            }
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Excel 格式错误：" + String.join("；", errors));
        }
        return tx.execute(status -> apply(format, column, rows, operator));
    }

    private ImportRow validate(Format format, ReplayUnconditionalIgnoreReasonExcelParser.ExcelRow row,
                               java.util.function.UnaryOperator<String> validateValue) {
        if (!row.validColumnCount()) throw new IllegalArgumentException("请按页签说明填写 " + (format.columns.size() + 1) + " 列");
        List<String> cells = row.cells();
        String serviceCode = ReplayConfigValidation.requireText(cells.get(0), "服务码");
        String second;
        Object third;
        switch (format) {
            case UNCONDITIONAL -> {
                second = ReplayConfigValidation.requireText(cells.get(1), "忽略字段");
                third = null;
            }
            case CONDITIONAL -> {
                second = ReplayConfigValidation.requireText(cells.get(1), "忽略字段");
                try {
                    int index = Integer.parseInt(cells.get(2));
                    if (index <= 0) throw new NumberFormatException();
                    third = index;
                } catch (NumberFormatException ex) {
                    throw new IllegalArgumentException("字段索引必须为正整数");
                }
            }
            case SORT -> {
                second = ReplayConfigValidation.requireText(cells.get(1), "对象/数组名称");
                third = ReplayConfigValidation.requireText(cells.get(2), "排序字段");
            }
            case ERROR_CODE -> {
                second = ReplayConfigValidation.normalizeResponseCode(cells.get(1));
                String newCode = ReplayConfigValidation.normalizeResponseCode(cells.get(2));
                ReplayConfigValidation.requireAtLeastOneResponseCode(second, newCode);
                third = newCode;
            }
            default -> throw new IllegalStateException("Unsupported import format");
        }
        return new ImportRow(serviceCode, second, third, validateValue.apply(cells.get(format.columns.size())));
    }

    private Result apply(Format format, String column, List<ImportRow> rows, ReplayConfigOperator operator) {
        int updated = 0;
        int existing = 0;
        int unmatched = 0;
        int ambiguous = 0;
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        for (ImportRow row : rows) {
            List<Object> args = new ArrayList<>();
            List<String> predicates = new ArrayList<>();
            addMatch(predicates, args, format.columns.get(0), row.serviceCode());
            addMatch(predicates, args, format.columns.get(1), row.second());
            if (format.columns.size() == 3) addMatch(predicates, args, format.columns.get(2), row.third());
            List<Match> matches = jdbc.query(
                    "SELECT id," + column + " FROM " + format.table + " WHERE "
                            + String.join(" AND ", predicates) + " LIMIT 2 FOR UPDATE",
                    (rs, i) -> new Match(rs.getLong("id"), rs.getString(column)), args.toArray());
            if (matches.isEmpty()) {
                unmatched++;
                continue;
            }
            if (matches.size() > 1) {
                ambiguous++;
                continue;
            }
            Match match = matches.get(0);
            if (match.value() != null && !match.value().isBlank()) {
                existing++;
                continue;
            }
            jdbc.update("UPDATE " + format.table + " SET " + column + "=?,updated_at=?,version=version+1 WHERE id=?",
                    row.value(), now, match.id());
            jdbc.update("INSERT INTO " + format.table + "_operation "
                            + "(config_id,operation_type," + column + ",new_" + column + ",operator_username,"
                            + "operator_real_name,operation_source,created_at) VALUES (?,'UPDATE',?,?,?,?,'EXCEL',?)",
                    match.id(), match.value(), row.value(), operator == null ? null : operator.username(),
                    operator == null ? null : operator.realName(), now);
            updated++;
        }
        return new Result(rows.size(), updated, existing, unmatched, 0, ambiguous);
    }

    private static void addMatch(List<String> predicates, List<Object> args, String column, Object value) {
        if (value == null) {
            predicates.add(column + " IS NULL");
        } else {
            predicates.add(column + "=?");
            args.add(value);
        }
    }

    private enum Format {
        UNCONDITIONAL("dii_replay_unconditional_ignore", "tran_code", "field_name"),
        CONDITIONAL("dii_replay_conditional_rmove", "orig_trcd", "field_rmove_name", "field_file_indx"),
        SORT("dii_replay_sort_field", "orig_trcd", "orig_arry_name", "orig_field_name"),
        ERROR_CODE("dii_replay_error_code_ignore_config", "service_code", "old_resp_code", "new_resp_code");

        private final String table;
        private final List<String> columns;

        Format(String table, String... columns) {
            this.table = table;
            this.columns = List.of(columns);
        }
    }

    private record ImportRow(String serviceCode, String second, Object third, String value) {}
    private record Match(long id, String value) {}

    public record Result(int totalRows, int updatedRows, int skippedExistingReasonRows,
                         int unmatchedRows, int invalidRows, int ambiguousRows) {}

    public record ChineseNameResult(int totalRows, int updatedRows, int skippedExistingNameRows,
                                    int unmatchedRows, int invalidRows, int ambiguousRows) {}
}
