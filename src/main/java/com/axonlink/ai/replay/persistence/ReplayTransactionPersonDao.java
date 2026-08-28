package com.axonlink.ai.replay.persistence;

import com.axonlink.ai.replay.dto.ReplayTransactionPersonRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Repository
public class ReplayTransactionPersonDao {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public ReplayTransactionPersonDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    }

    public void replaceAll(List<ReplayTransactionPersonRow> rows, LocalDateTime importedAt) {
        tx.executeWithoutResult(status -> {
          jdbc.update("DELETE FROM dii_replay_transaction_person");
          jdbc.batchUpdate("INSERT INTO dii_replay_transaction_person "
                + "(domain,old_transaction_code,old_transaction_name,developer,developer_usernames,bank_owner,bank_owner_emp_nos,imported_at) "
                + "VALUES (?,?,?,?,?,?,?,?)", rows, 500, (ps, row) -> {
            ps.setString(1, row.domain()); ps.setString(2, row.oldTransactionCode()); ps.setString(3, row.oldTransactionName());
            ps.setString(4, row.developer()); ps.setString(5, row.developerUsernames()); ps.setString(6, row.bankOwner());
            ps.setString(7, row.bankOwnerEmpNos()); ps.setTimestamp(8, Timestamp.valueOf(importedAt));
          });
        });
    }

    public List<ReplayTransactionPersonRow> list(String keyword, int limit, int offset) {
        return listInternal(keyword, Math.min(Math.max(limit, 1), 200), Math.max(offset, 0));
    }

    public List<ReplayTransactionPersonRow> listAll(String keyword) {
        return listInternal(keyword, Integer.MAX_VALUE, 0);
    }

    private List<ReplayTransactionPersonRow> listInternal(String keyword, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT * FROM dii_replay_transaction_person WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (domain LIKE ? OR old_transaction_code LIKE ? OR old_transaction_name LIKE ? OR developer LIKE ? OR bank_owner LIKE ?)");
            String value = "%" + keyword.trim() + "%";
            for (int i = 0; i < 5; i++) args.add(value);
        }
        sql.append(" ORDER BY domain, old_transaction_code LIMIT ? OFFSET ?");
        args.add(limit); args.add(offset);
        return jdbc.query(sql.toString(), (rs, n) -> new ReplayTransactionPersonRow(rs.getLong("id"), rs.getString("domain"),
                rs.getString("old_transaction_code"), rs.getString("old_transaction_name"), rs.getString("developer"),
                rs.getString("developer_usernames"), rs.getString("bank_owner"), rs.getString("bank_owner_emp_nos"),
                rs.getTimestamp("imported_at").toLocalDateTime()), args.toArray());
    }

    public long count(String keyword) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM dii_replay_transaction_person WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (domain LIKE ? OR old_transaction_code LIKE ? OR old_transaction_name LIKE ? OR developer LIKE ? OR bank_owner LIKE ?)");
            String value = "%" + keyword.trim() + "%"; for (int i = 0; i < 5; i++) args.add(value);
        }
        Long result = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return result == null ? 0 : result;
    }

    public ReplayTransactionPersonRow findByTransactionCode(String transactionCode) {
        if (transactionCode == null || transactionCode.isBlank()) return null;
        List<ReplayTransactionPersonRow> rows = jdbc.query("SELECT * FROM dii_replay_transaction_person WHERE old_transaction_code=? LIMIT 1",
                (rs, n) -> new ReplayTransactionPersonRow(rs.getLong("id"), rs.getString("domain"),
                        rs.getString("old_transaction_code"), rs.getString("old_transaction_name"), rs.getString("developer"),
                        rs.getString("developer_usernames"), rs.getString("bank_owner"), rs.getString("bank_owner_emp_nos"),
                        rs.getTimestamp("imported_at") == null ? null : rs.getTimestamp("imported_at").toLocalDateTime()),
                transactionCode.trim());
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<String> findTransactionCodesByBankOwnerEmpNo(String empNo) {
        if (empNo == null || empNo.isBlank()) return List.of();
        String expected = empNo.trim();
        return jdbc.query("SELECT old_transaction_code,bank_owner_emp_nos FROM dii_replay_transaction_person " +
                        "WHERE TRIM(COALESCE(bank_owner_emp_nos,''))<>'' ORDER BY old_transaction_code",
                (rs, rowNum) -> new String[] {rs.getString("old_transaction_code"), rs.getString("bank_owner_emp_nos")})
                .stream()
                .filter(row -> splitEmployeeNumbers(row[1]).contains(expected))
                .map(row -> row[0])
                .filter(code -> code != null && !code.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    public List<String> findBankOwnerEmpNosByTransactionCode(String transactionCode) {
        ReplayTransactionPersonRow row = findByTransactionCode(transactionCode);
        return row == null ? List.of() : splitEmployeeNumbers(row.bankOwnerEmpNos());
    }

    private static List<String> splitEmployeeNumbers(String value) {
        if (value == null || value.isBlank()) return List.of();
        return java.util.Arrays.stream(value.split("[、,，;；]"))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .distinct()
                .toList();
    }
}
