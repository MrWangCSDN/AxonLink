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
    void joinDedupesMultiComponentAttributionPerTxThrow() {
        // 同一实现方法被两个 ServiceOperation 归属（IMPLEMENTS_BY 多对一，pbcb 常见）：
        // 同 (tx × throw) 只能产 1 行，否则撞物化表唯一键 uk_tx_throw（2026-07-15 内网事故：
        // TG121 × Bkdf.B0191 Duplicate entry → 非事务写把整表留成 0 行）
        List<TxErrorCodeRow> rs = ErrorCodeAttributionService.joinToTxRows(
                List.of(rm("T1", "sett", "com.x.Pbcb", "m", "OP_A"),
                        rm("T1", "sett", "com.x.Pbcb", "m", "OP_B")),
                List.of(et("B0191", "com.x.Pbcb", "m", 1)));
        assertEquals(1, rs.size(), "同 (tx×throw) 多构件归属必须收敛为 1 行");
        assertEquals("T1", rs.get(0).getTxId());
        assertEquals("OP_A", rs.get(0).getComponentCode());
    }

    @Test
    void joinDedupePrefersNonNullComponent() {
        // 先 null 后非空：应保留非空 component 的归属变体
        List<TxErrorCodeRow> rs = ErrorCodeAttributionService.joinToTxRows(
                List.of(rm("T1", "sett", "com.x.Pbcb", "m", null),
                        rm("T1", "sett", "com.x.Pbcb", "m", "OP_B")),
                List.of(et("B0191", "com.x.Pbcb", "m", 1)));
        assertEquals(1, rs.size());
        assertEquals("OP_B", rs.get(0).getComponentCode());
    }

    @Test
    void joinDedupeDoesNotCollapseDifferentTx() {
        // 去重只作用于同一 tx 内：不同交易的扇出行为保持不变
        List<TxErrorCodeRow> rs = ErrorCodeAttributionService.joinToTxRows(
                List.of(rm("T1", "sett", "com.x.Pbcb", "m", "OP_A"),
                        rm("T1", "sett", "com.x.Pbcb", "m", "OP_B"),
                        rm("T2", "sett", "com.x.Pbcb", "m", "OP_A")),
                List.of(et("B0191", "com.x.Pbcb", "m", 1),
                        et("B0192", "com.x.Pbcb", "m", 2)));
        assertEquals(4, rs.size(), "2 tx × 2 throw = 4 行（每对唯一）");
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
        List<ErrorCodeThrow> throwsToReturn = List.of();   // 桩明细（守卫测试需要非空明细）

        RecordingDao() {
            super(null);                               // 父类构造只存 JdbcTemplate，单测不触 SQL
        }

        @Override
        public List<ErrorCodeThrow> listAllThrows() {
            return throwsToReturn;
        }

        @Override
        public void materializeTxErrorCodes(List<TxErrorCodeRow> rows) {
            materializeCalled = true;                  // 标记整表覆盖被执行
            lastRows = rows;
        }
    }

    private ErrorCodeAttributionService svc(RecordingDao dao) {
        // driver / flowtranService 传 null：本组测试只验证 materialize(...) 编排核心（注入桩 resolver），不触 Neo4j。
        return new ErrorCodeAttributionService(null, dao, null);
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

    // ── 2026-07-15 事故守卫：空明细 / 空图 / 无 Driver 一律拒绝覆盖，保留旧数据 ──

    @Test
    void emptyThrowDetailsSkipsOverwrite() {
        RecordingDao dao = new RecordingDao();          // 明细为空（默认）
        ErrorCodeAttributionService s = svc(dao);
        ErrorCodeAttributionService.MaterializeOutcome out = s.materializeTransactionErrorCodes();
        assertFalse(out.isComplete(), "明细为空（疑似源码替换窗口）应判不完整");
        assertFalse(dao.materializeCalled, "明细为空绝不能触发整表覆盖");
    }

    @Test
    void nullDriverKeepsOldDataInsteadOfClearing() {
        RecordingDao dao = new RecordingDao();
        dao.throwsToReturn = List.of(et("E0001", "com.x.A", "m", 1));   // 明细非空
        ErrorCodeAttributionService s = svc(dao);       // driver=null
        ErrorCodeAttributionService.MaterializeOutcome out = s.materializeTransactionErrorCodes();
        assertFalse(out.isComplete());
        assertFalse(dao.materializeCalled, "无 Driver 时不得再清空物化表（改为保留旧数据）");
    }

    @Test
    void emptyGraphWithThrowsSkipsAllUnmatchedOverwrite() {
        RecordingDao dao = new RecordingDao();
        ErrorCodeAttributionService s = svc(dao);
        // 图无交易（txIds 空）而 throw 非空 → 覆盖会把整表写成全 UNMATCHED，必须拒绝
        ErrorCodeAttributionService.MaterializeOutcome out = s.materialize(
                List.of(), List.of(et("E0001", "com.x.A", "m", 1)), batch -> {
                    throw new AssertionError("txIds 为空不应触达 resolver");
                });
        assertFalse(out.isComplete());
        assertFalse(dao.materializeCalled, "空图+非空明细不得覆盖成全 UNMATCHED");
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
