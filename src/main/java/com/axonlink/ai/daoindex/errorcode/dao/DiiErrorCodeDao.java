package com.axonlink.ai.daoindex.errorcode.dao;

import com.axonlink.ai.daoindex.errorcode.dto.ErrorCodeThrow;
import com.axonlink.ai.daoindex.errorcode.dto.TxErrorCodeRow;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

/**
 * 错误码结果库 DAO，对标 DiiSlowSqlDao：DELETE 整表 + batchUpdate 重建。
 * 由 diiResultJdbcTemplate 构造注入（结果库 MySQL）。
 *
 * <p><b>两个整表重建方法必须在事务内执行</b>：JdbcTemplate 默认自动提交时，
 * DELETE 先独立提交、随后 batchInsert 一旦失败（如唯一键冲突、列截断），表就停留在
 * 0 行——2026-07-15 内网事故即此路径（uk_tx_throw 冲突 → dii_tx_error_code 全空，
 * 徽章消失/导出空表）。故用结果库自建 TransactionTemplate 包裹，失败整体回滚保留旧数据。
 */
@Repository
public class DiiErrorCodeDao {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate txTemplate;

    public DiiErrorCodeDao(JdbcTemplate diiResultJdbcTemplate) {
        this.jdbc = diiResultJdbcTemplate;
        // 结果库无全局事务管理器（多数据源工程），就地从本 DAO 的 DataSource 构建。
        // 判空兼容单测桩（子类覆写写方法、传 null JdbcTemplate 不触 SQL）。
        this.txTemplate = (diiResultJdbcTemplate == null || diiResultJdbcTemplate.getDataSource() == null)
                ? null
                : new TransactionTemplate(
                        new DataSourceTransactionManager(diiResultJdbcTemplate.getDataSource()));
    }

    /** 事务内执行；无事务管理器（单测桩）时直接执行。 */
    private void inTx(Runnable work) {
        if (txTemplate == null) {
            work.run();
        } else {
            txTemplate.executeWithoutResult(status -> work.run());
        }
    }

    // ── dii_error_code 明细 ─────────────────────────

    public void deleteAllThrows() {
        jdbc.update("DELETE FROM dii_error_code");
    }

    /** 明细表现存行数（供扫描侧「空结果拒绝覆盖」守卫）。 */
    public int countThrows() {
        Integer c = jdbc.queryForObject("SELECT COUNT(*) FROM dii_error_code", Integer.class);
        return c == null ? 0 : c;
    }

    /** 整表重建明细：事务内 DELETE + batchInsert，失败整体回滚（保留旧数据）。 */
    public void rebuildThrows(List<ErrorCodeThrow> rows) {
        inTx(() -> doRebuildThrows(rows));
    }

    private void doRebuildThrows(List<ErrorCodeThrow> rows) {
        jdbc.update("DELETE FROM dii_error_code");
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

    /** 物化写库入口（DAO 层）：事务内 DELETE 整表 + batchInsert，失败整体回滚（保留旧数据）。 */
    public void materializeTxErrorCodes(List<TxErrorCodeRow> rows) {
        inTx(() -> doMaterializeTxErrorCodes(rows));
    }

    private void doMaterializeTxErrorCodes(List<TxErrorCodeRow> rows) {
        jdbc.update("DELETE FROM dii_tx_error_code");
        if (rows == null || rows.isEmpty()) {
            return;
        }
        // ON DUPLICATE KEY UPDATE id=id：uk_tx_throw(tx_id,scope,code,class,method,throw_seq) 冲突时
        // 静默保留首行——上游 joinToTxRows 已按 (tx×throw) 去重，此处是兜底（物化视图宁可少一条
        // 归属变体，不可整表写失败）。H2 MODE=MySQL 同样支持该语法（单测可跑）。
        jdbc.batchUpdate(
                "INSERT INTO dii_tx_error_code (tx_id, tx_name, domain_key, error_code, error_scope,"
                + " throw_text, class_fqn, method_name, file_path, line_no, module_name,"
                + " component_code, component_name, match_status, throw_seq)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
                + " ON DUPLICATE KEY UPDATE id=id",
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
