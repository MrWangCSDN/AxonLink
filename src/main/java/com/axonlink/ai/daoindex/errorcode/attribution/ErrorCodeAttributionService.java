package com.axonlink.ai.daoindex.errorcode.attribution;

import com.axonlink.ai.daoindex.errorcode.dao.DiiErrorCodeDao;
import com.axonlink.ai.daoindex.errorcode.dto.ErrorCodeThrow;
import com.axonlink.ai.daoindex.errorcode.dto.TxErrorCodeRow;
import com.axonlink.service.FlowtranService;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
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

    private final Driver driver;               // 可为 null（无 Neo4j 时降级）；仅用于取全量交易 id + 可用性判断
    private final DiiErrorCodeDao dao;         // 见 Task 5
    private final FlowtranService flowtranService;   // 复用 getChain 同款链路解析（collectChainMethods）

    @Autowired
    public ErrorCodeAttributionService(@Qualifier("neo4jDriver") Driver driver,
                                       DiiErrorCodeDao dao,
                                       FlowtranService flowtranService) {
        this.driver = driver;
        this.dao = dao;
        this.flowtranService = flowtranService;
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
     * 带重试的批解析：对批内每个交易调用 {@link FlowtranService#collectChainMethods}
     * （复用 getChain 同款链路解析，覆盖 ServiceOperation / 接口(SysUtil.getInstance) / Class / Method
     * 全部调用方式 + 实现方法内部可达，凡链路图上显示的构件其实现方法都覆盖）。
     *
     * <p>返回 {@link BatchResult#failed}=true 当且仅当批内<b>至少一个交易</b>重试 {@link #RETRY} 次仍失败。
     * 此时该批 reachable 不完整，编排层据此中止整表覆盖、保留旧数据（不写残缺）。
     */
    private BatchResult queryReachableWithRetry(List<String> batch) {
        List<ReachableMethod> rows = new ArrayList<>();
        boolean anyTxFailed = false;
        for (String txId : batch) {
            boolean ok = false;
            for (int attempt = 1; attempt <= RETRY && !ok; attempt++) {
                try {
                    rows.addAll(reachableForTx(txId));
                    ok = true;
                } catch (RuntimeException ex) {
                    log.warn("[error-code] collectChainMethods 失败 txId={} attempt={}/{}：{}",
                            txId, attempt, RETRY, ex.getMessage());
                }
            }
            if (!ok) {
                anyTxFailed = true;
                log.error("[error-code] 交易可达方法解析彻底失败 txId={}（将保留上一轮物化数据）", txId);
            }
        }
        return new BatchResult(rows, anyTxFailed);
    }

    /**
     * 单交易可达实现方法：复用 {@link FlowtranService#collectChainMethods}，结果摊平为 {@link ReachableMethod}。
     * collectChainMethods 返回 null（无 Neo4j / 交易不存在）→ 视为空；Neo4j 查询异常会上抛，触发上面的重试。
     */
    private List<ReachableMethod> reachableForTx(String txId) {
        Map<String, Object> chain = flowtranService.collectChainMethods(txId);
        if (chain == null) {
            return List.of();
        }
        String txName = (String) chain.get("txName");
        String domainKey = (String) chain.get("domainKey");
        Object raw = chain.get("methods");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<ReachableMethod> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                continue;
            }
            out.add(new ReachableMethod(
                    txId, txName, domainKey,
                    (String) m.get("classFqn"), (String) m.get("methodName"),
                    (String) m.get("componentCode"), (String) m.get("componentName")));
        }
        return out;
    }
}
