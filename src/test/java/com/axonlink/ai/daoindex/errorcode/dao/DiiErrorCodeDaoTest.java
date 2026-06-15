package com.axonlink.ai.daoindex.errorcode.dao;

import com.axonlink.ai.daoindex.errorcode.dto.ErrorCodeThrow;
import com.axonlink.ai.daoindex.errorcode.dto.TxErrorCodeRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import javax.sql.DataSource;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** DiiErrorCodeDao 8 方法 16 例（H2 MySQL 兼容模式，整表重建语义对标 DiiSlowSqlDao）。 */
class DiiErrorCodeDaoTest {

    private JdbcTemplate jdbc;
    private DiiErrorCodeDao dao;

    @BeforeEach
    void setUp() {
        DataSource ds = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName("ec_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1")
                .build();
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE dii_error_code ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "error_code VARCHAR(20) NOT NULL, error_scope VARCHAR(64) NOT NULL,"
                + "throw_text VARCHAR(1024) NOT NULL, class_fqn VARCHAR(255) NOT NULL,"
                + "method_name VARCHAR(128) NOT NULL, file_path VARCHAR(512), line_no INT,"
                + "module_name VARCHAR(128), inner_class_name VARCHAR(128), code_signature VARCHAR(512),"
                + "throw_seq BIGINT NOT NULL, scanned_at DATETIME DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE dii_tx_error_code ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "tx_id VARCHAR(64) NOT NULL, tx_name VARCHAR(255), domain_key VARCHAR(16),"
                + "error_code VARCHAR(20) NOT NULL, error_scope VARCHAR(64) NOT NULL,"
                + "throw_text VARCHAR(1024) NOT NULL, class_fqn VARCHAR(255) NOT NULL,"
                + "method_name VARCHAR(128) NOT NULL, file_path VARCHAR(512), line_no INT,"
                + "module_name VARCHAR(128), component_code VARCHAR(255), component_name VARCHAR(255),"
                + "match_status VARCHAR(16) NOT NULL DEFAULT 'MATCHED', throw_seq BIGINT NOT NULL,"
                + "scanned_at DATETIME DEFAULT CURRENT_TIMESTAMP)");
        dao = new DiiErrorCodeDao(jdbc);
    }

    private ErrorCodeThrow throwRow(String code, String fqn, String method, long seq) {
        return new ErrorCodeThrow(code, "CmError", "throw CmError." + code + "()",
                fqn, method, "/abs/" + method + ".java", 10, "loan-bcc", null, null, seq);
    }

    private TxErrorCodeRow txRow(String txId, String domain, String code, String compCode,
                                 String status, long seq) {
        return new TxErrorCodeRow(txId, txId + "-name", domain, code, "CmError",
                "throw CmError." + code + "()", "com.x.A", "m", "/abs/A.java", 10, "loan-bcc",
                compCode, compCode == null ? null : compCode + "-name", status, seq);
    }

    // ── 轮1：明细写读 ───────────────────────────────
    @Test void deleteAllThrowsEmptiesTable() {
        dao.rebuildThrows(List.of(throwRow("E0001", "com.x.A", "m", 1)));
        dao.deleteAllThrows();
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM dii_error_code", Integer.class));
    }

    @Test void rebuildReplacesAll() {
        dao.rebuildThrows(List.of(throwRow("E0001", "com.x.A", "m", 1)));
        dao.rebuildThrows(List.of(throwRow("E0002", "com.x.B", "n", 2),
                                  throwRow("E0003", "com.x.C", "o", 3)));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM dii_error_code", Integer.class));
    }

    @Test void listAllThrowsReturnsRows() {
        dao.rebuildThrows(List.of(throwRow("E0001", "com.x.A", "m", 1)));
        List<ErrorCodeThrow> all = dao.listAllThrows();
        assertEquals(1, all.size());
        assertEquals("E0001", all.get(0).getErrorCode());
    }

    @Test void rebuildPersistsAllColumns() {
        dao.rebuildThrows(List.of(throwRow("E0009", "com.x.Z", "validate", 5)));
        ErrorCodeThrow r = dao.listAllThrows().get(0);
        assertEquals("com.x.Z", r.getClassFqn());
        assertEquals("validate", r.getMethodName());
        assertEquals("loan-bcc", r.getModuleName());
        assertEquals(Long.valueOf(5L), r.getThrowSeq());
    }

    // ── 轮2：物化写 ───────────────────────────────
    @Test void materializeReplacesAll() {
        dao.materializeTxErrorCodes(List.of(txRow("T1", "deposit", "E0001", "SVC1", "MATCHED", 1)));
        dao.materializeTxErrorCodes(List.of(txRow("T2", "loan", "E0002", "SVC2", "MATCHED", 2),
                                            txRow("T2", "loan", "E0003", "SVC2", "MATCHED", 3)));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM dii_tx_error_code", Integer.class));
    }

    @Test void materializePersistsComponentNull() {
        dao.materializeTxErrorCodes(List.of(txRow("T1", "deposit", "E0001", null, "MATCHED", 1)));
        assertNull(dao.listByTxId("T1").get(0).getComponentCode());
    }

    @Test void materializePersistsMatchStatus() {
        dao.materializeTxErrorCodes(List.of(txRow("T1", "deposit", "E0001", null, "UNMATCHED", 1)));
        assertEquals("UNMATCHED", dao.listByTxId("T1").get(0).getMatchStatus());
    }

    @Test void materializeEmptyListClearsTable() {
        dao.materializeTxErrorCodes(List.of(txRow("T1", "deposit", "E0001", "SVC1", "MATCHED", 1)));
        dao.materializeTxErrorCodes(List.of());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM dii_tx_error_code", Integer.class));
    }

    // ── 轮3：按交易读 ───────────────────────────────
    @Test void listByTxIdFiltersAndOrders() {
        dao.materializeTxErrorCodes(List.of(
                txRow("T1", "deposit", "E0003", "SVC1", "MATCHED", 1),
                txRow("T1", "deposit", "E0001", "SVC1", "MATCHED", 2),
                txRow("T2", "loan", "E0009", "SVC2", "MATCHED", 3)));
        List<TxErrorCodeRow> rs = dao.listByTxId("T1");
        assertEquals(2, rs.size());
        assertEquals("E0001", rs.get(0).getErrorCode());  // ORDER BY error_code
        assertEquals("E0003", rs.get(1).getErrorCode());
    }

    @Test void countByTxIdCountsAll() {
        dao.materializeTxErrorCodes(List.of(
                txRow("T1", "deposit", "E0001", "SVC1", "MATCHED", 1),
                txRow("T1", "deposit", "E0001", "SVC1", "MATCHED", 2),
                txRow("T1", "deposit", "E0002", "SVC1", "MATCHED", 3)));
        assertEquals(3, dao.countByTxId("T1"));
    }

    @Test void distinctCountByTxIdDedups() {
        dao.materializeTxErrorCodes(List.of(
                txRow("T1", "deposit", "E0001", "SVC1", "MATCHED", 1),
                txRow("T1", "deposit", "E0001", "SVC1", "MATCHED", 2),
                txRow("T1", "deposit", "E0002", "SVC1", "MATCHED", 3)));
        assertEquals(2, dao.distinctCountByTxId("T1"));   // 去重 = 2
    }

    @Test void listByTxIdEmptyForUnknown() {
        assertTrue(dao.listByTxId("NOPE").isEmpty());
    }

    // ── 轮4：全量读 ───────────────────────────────
    @Test void listAllNoFilterReturnsAll() {
        dao.materializeTxErrorCodes(List.of(
                txRow("T1", "deposit", "E0001", "SVC1", "MATCHED", 1),
                txRow("T2", "loan", "E0002", "SVC2", "MATCHED", 2)));
        assertEquals(2, dao.listAll(null).size());
    }

    @Test void listAllByDomainFilters() {
        dao.materializeTxErrorCodes(List.of(
                txRow("T1", "deposit", "E0001", "SVC1", "MATCHED", 1),
                txRow("T2", "loan", "E0002", "SVC2", "MATCHED", 2)));
        List<TxErrorCodeRow> rs = dao.listAll("loan");
        assertEquals(1, rs.size());
        assertEquals("T2", rs.get(0).getTxId());
    }

    @Test void listAllOrdersByDomainThenTx() {
        dao.materializeTxErrorCodes(List.of(
                txRow("T2", "loan", "E0002", "SVC2", "MATCHED", 1),
                txRow("T1", "deposit", "E0001", "SVC1", "MATCHED", 2)));
        List<TxErrorCodeRow> rs = dao.listAll(null);
        assertEquals("deposit", rs.get(0).getDomainKey());  // deposit < loan
        assertEquals("loan", rs.get(1).getDomainKey());
    }

    @Test void listAllEmptyWhenNoRows() {
        assertTrue(dao.listAll(null).isEmpty());
    }
}
