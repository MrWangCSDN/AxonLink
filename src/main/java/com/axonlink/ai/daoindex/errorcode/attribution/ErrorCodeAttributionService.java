package com.axonlink.ai.daoindex.errorcode.attribution;

import com.axonlink.ai.daoindex.errorcode.dao.DiiErrorCodeDao;
import com.axonlink.ai.daoindex.errorcode.dto.ErrorCodeThrow;
import com.axonlink.ai.daoindex.errorcode.dto.TxErrorCodeRow;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 反向归属 + 物化编排。三名分层（勿混用）：
 *   纯函数 joinToTxRows           —— 把 reachable 与 throw 明细按 (classFqn, methodName) JOIN
 *   编排  materializeTransactionErrorCodes —— 拉 Neo4j → joinToTxRows → DAO.materializeTxErrorCodes
 *   写库  DiiErrorCodeDao.materializeTxErrorCodes —— DELETE + batchInsert（Task 5）
 */
@Service
public class ErrorCodeAttributionService {

    private static final Logger log = LoggerFactory.getLogger(ErrorCodeAttributionService.class);
    private static final int BATCH = 8;          // 分批 UNWIND 5~10
    private static final int RETRY = 3;

    private final Driver driver;               // 可为 null（无 Neo4j 时降级）
    private final DiiErrorCodeDao dao;         // 见 Task 5

    @Autowired
    public ErrorCodeAttributionService(@Qualifier("neo4jDriver") Driver driver,
                                       DiiErrorCodeDao dao) {
        this.driver = driver;
        this.dao = dao;
    }

    /** 可达方法值对象（Neo4j 查询结果一行 + 归属构件反查结果）。 */
    public static final class ReachableMethod {
        private final String txId;
        private final String txName;
        private final String domainKey;
        private final String classFqn;
        private final String methodName;
        private final String componentCode;
        private final String componentName;

        public ReachableMethod(String txId, String txName, String domainKey, String classFqn,
                               String methodName, String componentCode, String componentName) {
            this.txId = txId;
            this.txName = txName;
            this.domainKey = domainKey;
            this.classFqn = classFqn;
            this.methodName = methodName;
            this.componentCode = componentCode;
            this.componentName = componentName;
        }

        public String getTxId()          { return txId; }
        public String getTxName()        { return txName; }
        public String getDomainKey()     { return domainKey; }
        public String getClassFqn()      { return classFqn; }
        public String getMethodName()    { return methodName; }
        public String getComponentCode() { return componentCode; }
        public String getComponentName() { return componentName; }
    }

    /**
     * 纯函数：按 (classFqn, methodName) 把可达方法与 throw 明细做 JOIN。
     * 命中 → 每个 (tx × throw) 产出一行 MATCHED；未命中 → 一行 UNMATCHED（tx/component 字段空）。
     */
    public static List<TxErrorCodeRow> joinToTxRows(List<ReachableMethod> reachable,
                                                    List<ErrorCodeThrow> throwDetails) {
        Map<String, List<ReachableMethod>> byKey = new HashMap<>();
        for (ReachableMethod r : reachable) {
            byKey.computeIfAbsent(key(r.getClassFqn(), r.getMethodName()), k -> new ArrayList<>()).add(r);
        }
        List<TxErrorCodeRow> out = new ArrayList<>();
        for (ErrorCodeThrow t : throwDetails) {
            List<ReachableMethod> hits = byKey.get(key(t.getClassFqn(), t.getMethodName()));
            if (hits == null || hits.isEmpty()) {
                out.add(new TxErrorCodeRow(
                        null, null, null, t.getErrorCode(), t.getErrorScope(), t.getThrowText(),
                        t.getClassFqn(), t.getMethodName(), t.getFilePath(), t.getLineNo(),
                        t.getModuleName(), null, null, "UNMATCHED", t.getThrowSeq()));
                continue;
            }
            for (ReachableMethod r : hits) {
                out.add(new TxErrorCodeRow(
                        r.getTxId(), r.getTxName(), r.getDomainKey(), t.getErrorCode(),
                        t.getErrorScope(), t.getThrowText(), t.getClassFqn(), t.getMethodName(),
                        t.getFilePath(), t.getLineNo(), t.getModuleName(),
                        r.getComponentCode(), r.getComponentName(), "MATCHED", t.getThrowSeq()));
            }
        }
        return out;
    }

    private static String key(String fqn, String method) {
        return fqn + "#" + method;
    }

    /**
     * 编排：拉 Neo4j 可达全集 → joinToTxRows → 写库。无 Neo4j 则只清空物化表并 WARN。
     *
     * <p>一致性保证（要么全量成功才覆盖，要么保留旧数据）：物化表由
     * {@link DiiErrorCodeDao#materializeTxErrorCodes} 整表 DELETE + INSERT 重建。
     * 若任一批次彻底失败（3 次重试 + 逐交易降级仍有 tx 查不出），其 reachable 集合不完整，
     * 此时若继续重建会用残缺数据覆盖历史归属行。因此只要存在彻底失败的批次，就
     * <b>跳过 DELETE + INSERT，保留上一轮物化结果</b>，仅 log.error 告警并返回
     * {@link MaterializeOutcome#incomplete()}，避免瞬时 Neo4j 抖动静默抹掉历史数据。
     * 设计 §4.6 仅授权「无 Neo4j / 缺表」整体降级，不授权「部分成功即整表覆盖」。
     *
     * @return 物化结果（complete=是否完整覆盖、skippedDueToPartialFailure=是否因部分失败而保留旧数据）
     */
    public MaterializeOutcome materializeTransactionErrorCodes() {
        List<ErrorCodeThrow> throwDetails = dao.listAllThrows();   // Task 5
        if (driver == null) {
            log.warn("[error-code] 无 Neo4j Driver，跳过物化，dii_tx_error_code 留空");
            dao.materializeTxErrorCodes(List.of());
            return MaterializeOutcome.empty();
        }
        // 用真正的 Neo4j 批查询（带重试 + 逐交易降级）作为 resolver 注入编排核心。
        return materialize(listAllTransactionIds(), throwDetails, this::queryReachableWithRetry);
    }

    /**
     * 编排核心（与 Neo4j Driver 解耦，便于单测注入 resolver）：分批拉可达全集，
     * <b>只要任一批次彻底失败就拒绝整表覆盖、保留旧数据并返回 incomplete</b>，否则 JOIN + 重建。
     *
     * @param txIds        全量交易 id
     * @param throwDetails throw 明细
     * @param resolver     按批解析可达方法的函数（生产环境即 {@link #queryReachableWithRetry}）
     */
    MaterializeOutcome materialize(List<String> txIds, List<ErrorCodeThrow> throwDetails,
                                   BatchResolver resolver) {
        List<ReachableMethod> reachable = new ArrayList<>();
        boolean anyBatchFailed = false;
        for (int i = 0; i < txIds.size(); i += BATCH) {
            List<String> batch = txIds.subList(i, Math.min(i + BATCH, txIds.size()));
            BatchResult br = resolver.resolve(batch);
            reachable.addAll(br.rows);
            anyBatchFailed = anyBatchFailed || br.failed;
        }
        if (anyBatchFailed) {
            // 部分批次彻底失败 → reachable 不完整 → 拒绝用残缺数据重建整表，保留上一轮物化结果。
            log.error("[error-code] 物化中止：存在彻底失败的批次，reachable 集合不完整，"
                    + "已跳过 DELETE+INSERT 以保留上一轮物化数据（接通 Neo4j 后重试 rescan）"
                    + " txIds={} 已拉取 reachable={}", txIds.size(), reachable.size());
            return MaterializeOutcome.incomplete();
        }
        List<TxErrorCodeRow> rows = joinToTxRows(reachable, throwDetails);
        dao.materializeTxErrorCodes(rows);                          // Task 5
        log.info("[error-code] 物化完成 reachable={} throw={} txRows={}",
                reachable.size(), throwDetails.size(), rows.size());
        return MaterializeOutcome.complete(reachable.size(), rows.size());
    }

    /** 按批解析可达方法的函数式接口（生产用 Neo4j，单测可注入桩）。 */
    @FunctionalInterface
    interface BatchResolver {
        BatchResult resolve(List<String> batch);
    }

    /**
     * 物化结果。{@code complete=false} 表示本轮物化不完整（因部分批次彻底失败），
     * 此时物化表保留上一轮数据未被覆盖，调用方/前端可据此显示「本轮物化不完整」状态。
     */
    public static final class MaterializeOutcome {
        private final boolean complete;
        private final boolean skippedDueToPartialFailure;
        private final int reachableCount;
        private final int txRowCount;

        private MaterializeOutcome(boolean complete, boolean skippedDueToPartialFailure,
                                   int reachableCount, int txRowCount) {
            this.complete = complete;
            this.skippedDueToPartialFailure = skippedDueToPartialFailure;
            this.reachableCount = reachableCount;
            this.txRowCount = txRowCount;
        }

        /** 全量成功并完成整表覆盖。 */
        static MaterializeOutcome complete(int reachableCount, int txRowCount) {
            return new MaterializeOutcome(true, false, reachableCount, txRowCount);
        }

        /** 无 Neo4j：物化表被清空（设计 §4.6 授权的整体降级），视为完整。 */
        static MaterializeOutcome empty() {
            return new MaterializeOutcome(true, false, 0, 0);
        }

        /** 部分批次彻底失败：跳过覆盖，保留旧数据。 */
        static MaterializeOutcome incomplete() {
            return new MaterializeOutcome(false, true, 0, 0);
        }

        public boolean isComplete()                   { return complete; }
        public boolean isSkippedDueToPartialFailure() { return skippedDueToPartialFailure; }
        public int getReachableCount()                { return reachableCount; }
        public int getTxRowCount()                    { return txRowCount; }
    }

    /** 单批查询结果：rows 为已拉取的可达方法，failed 表示该批存在彻底查不出的交易。 */
    static final class BatchResult {
        final List<ReachableMethod> rows;
        final boolean failed;

        BatchResult(List<ReachableMethod> rows, boolean failed) {
            this.rows = rows;
            this.failed = failed;
        }
    }

    private List<String> listAllTransactionIds() {
        List<String> ids = new ArrayList<>();
        try (Session s = driver.session()) {
            s.run("MATCH (tx:Transaction) RETURN tx.id AS id")
                    .forEachRemaining(r -> ids.add(r.get("id").asString()));
        }
        return ids;
    }

    /**
     * 带重试 + 逐交易降级的批查询。
     *
     * <p>返回 {@link BatchResult#failed}=true 当且仅当批查询 3 次重试失败、改逐交易降级后
     * <b>仍有至少一个交易彻底查不出</b>。此时该批的 reachable 集合不完整，由编排层据此
     * 决定是否中止整表覆盖（保留旧数据），而非把残缺集合静默写入物化表。
     */
    private BatchResult queryReachableWithRetry(List<String> batch) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= RETRY; attempt++) {
            try {
                // 批查询成功 → 该批完整，failed=false。
                return new BatchResult(queryReachable(batch), false);
            } catch (RuntimeException ex) {
                last = ex;
                log.warn("[error-code] Cypher 批查询失败 attempt={}/{} size={}", attempt, RETRY, batch.size());
            }
        }
        // 降级：逐交易循环单查。任一交易降级仍失败 → 整批标记 failed，触发上层中止覆盖。
        List<ReachableMethod> fallback = new ArrayList<>();
        boolean anyTxFailed = false;
        for (String txId : batch) {
            try {
                fallback.addAll(queryReachable(List.of(txId)));
            } catch (RuntimeException ex) {
                anyTxFailed = true;
                log.error("[error-code] 单交易降级仍失败 txId={}", txId, ex);
            }
        }
        if (anyTxFailed && last != null) {
            log.error("[error-code] 批 + 降级均失败（该批 reachable 不完整）", last);
        }
        return new BatchResult(fallback, anyTxFailed);
    }

    private List<ReachableMethod> queryReachable(List<String> batch) {
        String cypher =
                "UNWIND $txIds AS txId\n"
                + "MATCH (tx:Transaction {id: txId})-[:HAS_FLOW]->(flow:FlowBlock)\n"
                + "MATCH p = (flow)-[:HAS_STEP|EXECUTES|HAS_BRANCH|NEXT*0..24]->(step)\n"
                + "WHERE step:FlowMethodStep OR step:FlowServiceStep\n"
                + "OPTIONAL MATCH (step)-[:RESOLVES_TO_METHOD]->(entry:Method)\n"
                + "OPTIONAL MATCH (step)-[:CALLS_SERVICE]->(op0:ServiceOperation)-[:IMPLEMENTS_BY]->(entry:Method)\n"
                + "WITH DISTINCT txId, tx, entry\n"
                + "WHERE entry IS NOT NULL\n"
                + "MATCH (entry)-[:CALLS|SELF_CALLS|SYS_UTIL_CALLS*0..8]->(reachable:Method)\n"
                + "OPTIONAL MATCH (op:ServiceOperation)-[:IMPLEMENTS_BY]->(reachable)\n"
                + "OPTIONAL MATCH (op)<-[:DECLARES_OPERATION]-(st:ServiceType)\n"
                + "RETURN DISTINCT txId AS tx_id,\n"
                + "                tx.longname AS tx_name,\n"
                + "                tx.domainKey AS domain_key,\n"
                + "                reachable.classFqn AS class_fqn,\n"
                + "                reachable.name AS method_name,\n"
                + "                coalesce(op.serviceId, st.id) AS component_code,\n"
                + "                coalesce(op.longname, st.longname) AS component_name";
        List<ReachableMethod> out = new ArrayList<>();
        try (Session s = driver.session()) {
            for (Record r : s.run(cypher, Values.parameters("txIds", batch)).list()) {
                out.add(new ReachableMethod(
                        str(r, "tx_id"), str(r, "tx_name"), str(r, "domain_key"),
                        str(r, "class_fqn"), str(r, "method_name"),
                        str(r, "component_code"), str(r, "component_name")));
            }
        }
        return out;
    }

    private static String str(Record r, String key) {
        return r.get(key).isNull() ? null : r.get(key).asString();
    }
}
