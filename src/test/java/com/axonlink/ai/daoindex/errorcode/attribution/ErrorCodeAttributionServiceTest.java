package com.axonlink.ai.daoindex.errorcode.attribution;

import com.axonlink.ai.daoindex.errorcode.dao.DiiErrorCodeDao;
import com.axonlink.ai.daoindex.errorcode.dto.ErrorCodeThrow;
import com.axonlink.ai.daoindex.errorcode.dto.TxErrorCodeRow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** joinToTxRows 纯函数测试 + materialize 编排「部分失败保留旧数据」测试（无 Neo4j/Spring）。 */
class ErrorCodeAttributionServiceTest {

    private ErrorCodeThrow et(String code, String fqn, String method, long seq) {
        return new ErrorCodeThrow(code, "CmError", "throw CmError." + code + "()",
                fqn, method, "/abs/" + method + ".java", 10, "loan-bcc", null, null, seq);
    }

    private ErrorCodeAttributionService.ReachableMethod rm(
            String txId, String domain, String fqn, String method, String compCode) {
        return new ErrorCodeAttributionService.ReachableMethod(
                txId, txId + "-name", domain, fqn, method,
                compCode, compCode == null ? null : compCode + "-name");
    }

    @Test
    void joinMatchesByClassAndMethod() {
        List<TxErrorCodeRow> rs = ErrorCodeAttributionService.joinToTxRows(
                List.of(rm("T1", "deposit", "com.x.A", "m", "SVC1")),
                List.of(et("E0001", "com.x.A", "m", 1)));
        assertEquals(1, rs.size());
        assertEquals("T1", rs.get(0).getTxId());
        assertEquals("MATCHED", rs.get(0).getMatchStatus());
        assertEquals("SVC1", rs.get(0).getComponentCode());
    }

    @Test
    void joinUnmatchedThrowMarkedUnmatched() {
        List<TxErrorCodeRow> rs = ErrorCodeAttributionService.joinToTxRows(
                List.of(rm("T1", "deposit", "com.x.A", "m", "SVC1")),
                List.of(et("E0009", "com.x.ENUM", "f", 9)));
        assertEquals(1, rs.size());
        TxErrorCodeRow u = rs.get(0);
        assertEquals("UNMATCHED", u.getMatchStatus());
        assertNull(u.getTxId());
        assertNull(u.getComponentCode());
        assertEquals("E0009", u.getErrorCode());
    }

    @Test
    void joinFanOutToMultipleTx() {
        List<TxErrorCodeRow> rs = ErrorCodeAttributionService.joinToTxRows(
                List.of(rm("T1", "deposit", "com.x.A", "m", "SVC1"),
                        rm("T2", "loan", "com.x.A", "m", "SVC2")),
                List.of(et("E0001", "com.x.A", "m", 1)));
        assertEquals(2, rs.size());
        List<String> txs = rs.stream().map(TxErrorCodeRow::getTxId).sorted().collect(Collectors.toList());
        assertEquals(List.of("T1", "T2"), txs);
    }

    @Test
    void joinKeepsComponentNullForUtil() {
        List<TxErrorCodeRow> rs = ErrorCodeAttributionService.joinToTxRows(
                List.of(rm("T1", "deposit", "com.x.Util", "help", null)),
                List.of(et("E0001", "com.x.Util", "help", 1)));
        assertNull(rs.get(0).getComponentCode());
        assertNull(rs.get(0).getComponentName());
        assertEquals("MATCHED", rs.get(0).getMatchStatus());
    }

    @Test
    void joinCarriesTxAndDomainName() {
        List<TxErrorCodeRow> rs = ErrorCodeAttributionService.joinToTxRows(
                List.of(rm("T1", "deposit", "com.x.A", "m", "SVC1")),
                List.of(et("E0001", "com.x.A", "m", 1)));
        assertEquals("T1-name", rs.get(0).getTxName());
        assertEquals("deposit", rs.get(0).getDomainKey());
    }

    // ── 物化编排：部分批次失败 → 保留旧数据，不用残缺集合覆盖整表 ──────────────

    /** 记录 materializeTxErrorCodes 是否被调用（即是否触发 DELETE+INSERT 整表覆盖）的桩 DAO。 */
    private static final class RecordingDao extends DiiErrorCodeDao {
        boolean materializeCalled = false;             // 被调用即代表整表已被 DELETE+INSERT 覆盖
        List<TxErrorCodeRow> lastRows = null;          // 最近一次写入的行

        RecordingDao() {
            super(null);                               // 父类构造只存 JdbcTemplate，单测不触 SQL
        }

        @Override
        public List<ErrorCodeThrow> listAllThrows() {
            return List.of();                          // 编排只把它透传给 joinToTxRows，空表即可
        }

        @Override
        public void materializeTxErrorCodes(List<TxErrorCodeRow> rows) {
            materializeCalled = true;                  // 标记整表覆盖被执行
            lastRows = rows;
        }
    }

    private ErrorCodeAttributionService svc(RecordingDao dao) {
        // driver 传 null：本组测试只验证 materialize(...) 编排核心，不触 Neo4j。
        return new ErrorCodeAttributionService(null, dao);
    }

    @Test
    void partialBatchFailureSkipsOverwriteAndKeepsOldData() {
        RecordingDao dao = new RecordingDao();
        ErrorCodeAttributionService s = svc(dao);
        // 第一批成功、第二批彻底失败（failed=true）。BATCH=8，故两批用 16 个 tx。
        List<String> txIds = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            txIds.add("T" + i);
        }
        ErrorCodeAttributionService.BatchResolver resolver = batch -> {
            boolean batchFailed = batch.contains("T8");   // 第二批起始 tx，命中即标记整批失败
            return new ErrorCodeAttributionService.BatchResult(List.of(), batchFailed);
        };

        ErrorCodeAttributionService.MaterializeOutcome out =
                s.materialize(txIds, dao.listAllThrows(), resolver);

        // 关键断言：任一批次失败 → 跳过 DELETE+INSERT，保留旧数据。
        assertFalse(out.isComplete(), "部分失败时本轮物化应判为不完整");
        assertTrue(out.isSkippedDueToPartialFailure(), "应标记为因部分失败而跳过覆盖");
        assertFalse(dao.materializeCalled, "存在失败批次时绝不能触发整表 DELETE+INSERT");
    }

    @Test
    void allBatchesSuccessRebuildsTable() {
        RecordingDao dao = new RecordingDao();
        ErrorCodeAttributionService s = svc(dao);
        List<String> txIds = List.of("T1", "T2");          // 单批即可
        ErrorCodeAttributionService.BatchResolver resolver = batch -> {
            // 全部成功，返回一条可达方法，与下方 throw 明细 JOIN 命中。
            ErrorCodeAttributionService.ReachableMethod r =
                    rm("T1", "deposit", "com.x.A", "m", "SVC1");
            return new ErrorCodeAttributionService.BatchResult(List.of(r), false);
        };

        ErrorCodeAttributionService.MaterializeOutcome out =
                s.materialize(txIds, List.of(et("E0001", "com.x.A", "m", 1)), resolver);

        assertTrue(out.isComplete(), "全量成功应判为完整");
        assertFalse(out.isSkippedDueToPartialFailure());
        assertTrue(dao.materializeCalled, "全量成功应执行整表重建");
        assertEquals(1, dao.lastRows.size(), "JOIN 命中应写入 1 行");
    }
}
