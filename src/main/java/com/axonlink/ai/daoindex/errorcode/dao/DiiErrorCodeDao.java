package com.axonlink.ai.daoindex.errorcode.dao;

import com.axonlink.ai.daoindex.errorcode.dto.ErrorCodeThrow;
import com.axonlink.ai.daoindex.errorcode.dto.TxErrorCodeRow;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

/**
 * 错误码结果库 DAO，对标 DiiSlowSqlDao：DELETE 整表 + batchUpdate 重建。
 * 由 diiResultJdbcTemplate 构造注入（结果库 MySQL）。
 */
@Repository
public class DiiErrorCodeDao {

    private final JdbcTemplate jdbc;

    public DiiErrorCodeDao(JdbcTemplate diiResultJdbcTemplate) {
        this.jdbc = diiResultJdbcTemplate;
    }

    // ── dii_error_code 明细 ─────────────────────────

    public void deleteAllThrows() {
        jdbc.update("DELETE FROM dii_error_code");
    }

    /** 整表重建明细：DELETE + batchInsert。 */
    public void rebuildThrows(List<ErrorCodeThrow> rows) {
        deleteAllThrows();
        if (rows == null || rows.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(
                "INSERT INTO dii_error_code (error_code, error_scope, throw_text, class_fqn,"
                + " method_name, file_path, line_no, module_name, inner_class_name,"
                + " code_signature, throw_seq) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        ErrorCodeThrow r = rows.get(i);
                        ps.setString(1, r.getErrorCode());
                        ps.setString(2, r.getErrorScope());
                        ps.setString(3, r.getThrowText());
                        ps.setString(4, r.getClassFqn());
                        ps.setString(5, r.getMethodName());
                        ps.setString(6, r.getFilePath());
                        if (r.getLineNo() == null) {
                            ps.setNull(7, java.sql.Types.INTEGER);
                        } else {
                            ps.setInt(7, r.getLineNo());
                        }
                        ps.setString(8, r.getModuleName());
                        ps.setString(9, r.getInnerClassName());
                        ps.setString(10, r.getCodeSignature());
                        ps.setLong(11, r.getThrowSeq());
                    }

                    @Override
                    public int getBatchSize() {
                        return rows.size();
                    }
                });
    }

    private static final RowMapper<ErrorCodeThrow> THROW_MAPPER = (rs, n) -> new ErrorCodeThrow(
            rs.getString("error_code"), rs.getString("error_scope"), rs.getString("throw_text"),
            rs.getString("class_fqn"), rs.getString("method_name"), rs.getString("file_path"),
            (Integer) rs.getObject("line_no"), rs.getString("module_name"),
            rs.getString("inner_class_name"), rs.getString("code_signature"),
            rs.getLong("throw_seq"));

    public List<ErrorCodeThrow> listAllThrows() {
        return jdbc.query("SELECT * FROM dii_error_code", THROW_MAPPER);
    }

    // ── dii_tx_error_code 物化 ─────────────────────

    /** 物化写库入口（DAO 层）：DELETE 整表 + batchInsert。 */
    public void materializeTxErrorCodes(List<TxErrorCodeRow> rows) {
        jdbc.update("DELETE FROM dii_tx_error_code");
        if (rows == null || rows.isEmpty()) {
            return;
        }
        jdbc.batchUpdate(
                "INSERT INTO dii_tx_error_code (tx_id, tx_name, domain_key, error_code, error_scope,"
                + " throw_text, class_fqn, method_name, file_path, line_no, module_name,"
                + " component_code, component_name, match_status, throw_seq)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        TxErrorCodeRow r = rows.get(i);
                        ps.setString(1, r.getTxId());
                        ps.setString(2, r.getTxName());
                        ps.setString(3, r.getDomainKey());
                        ps.setString(4, r.getErrorCode());
                        ps.setString(5, r.getErrorScope());
                        ps.setString(6, r.getThrowText());
                        ps.setString(7, r.getClassFqn());
                        ps.setString(8, r.getMethodName());
                        ps.setString(9, r.getFilePath());
                        if (r.getLineNo() == null) {
                            ps.setNull(10, java.sql.Types.INTEGER);
                        } else {
                            ps.setInt(10, r.getLineNo());
                        }
                        ps.setString(11, r.getModuleName());
                        ps.setString(12, r.getComponentCode());
                        ps.setString(13, r.getComponentName());
                        ps.setString(14, r.getMatchStatus());
                        ps.setLong(15, r.getThrowSeq());
                    }

                    @Override
                    public int getBatchSize() {
                        return rows.size();
                    }
                });
    }

    private static final RowMapper<TxErrorCodeRow> TX_MAPPER = (rs, n) -> new TxErrorCodeRow(
            rs.getString("tx_id"), rs.getString("tx_name"), rs.getString("domain_key"),
            rs.getString("error_code"), rs.getString("error_scope"), rs.getString("throw_text"),
            rs.getString("class_fqn"), rs.getString("method_name"), rs.getString("file_path"),
            (Integer) rs.getObject("line_no"), rs.getString("module_name"),
            rs.getString("component_code"), rs.getString("component_name"),
            rs.getString("match_status"), rs.getLong("throw_seq"));

    public List<TxErrorCodeRow> listByTxId(String txId) {
        return jdbc.query(
                "SELECT * FROM dii_tx_error_code WHERE tx_id=? ORDER BY error_code, line_no",
                TX_MAPPER, txId);
    }

    public int countByTxId(String txId) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dii_tx_error_code WHERE tx_id=?", Integer.class, txId);
        return c == null ? 0 : c;
    }

    public int distinctCountByTxId(String txId) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT error_code) FROM dii_tx_error_code WHERE tx_id=?",
                Integer.class, txId);
        return c == null ? 0 : c;
    }

    /** 全量导出读取；domainKey 为 null 不过滤。 */
    public List<TxErrorCodeRow> listAll(String domainKey) {
        if (domainKey == null || domainKey.isBlank()) {
            return jdbc.query(
                    "SELECT * FROM dii_tx_error_code ORDER BY domain_key, tx_id, error_code, line_no",
                    TX_MAPPER);
        }
        return jdbc.query(
                "SELECT * FROM dii_tx_error_code WHERE domain_key=?"
                + " ORDER BY domain_key, tx_id, error_code, line_no",
                TX_MAPPER, domainKey);
    }
}
