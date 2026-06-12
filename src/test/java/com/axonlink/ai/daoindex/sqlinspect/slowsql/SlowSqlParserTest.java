package com.axonlink.ai.daoindex.sqlinspect.slowsql;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SlowSqlParser —— 耗时解析 / 哈希 / 轮次生成")
class SlowSqlParserTest {

    @Test
    @DisplayName("耗时解析：取前导数字，单位/逗号容错")
    void parseCostMs_variants() {
        assertEquals(20838, SlowSqlParser.parseCostMs("20838ms"));
        assertEquals(0,     SlowSqlParser.parseCostMs("0ms"));
        assertEquals(12143, SlowSqlParser.parseCostMs("12,143ms"));
        assertEquals(0,     SlowSqlParser.parseCostMs(""));
        assertEquals(0,     SlowSqlParser.parseCostMs(null));
        assertEquals(0,     SlowSqlParser.parseCostMs("ms"));
        assertEquals(500,   SlowSqlParser.parseCostMs(" 500 ms "));
    }

    @Test
    @DisplayName("哈希：trim 后相同→同 hash；中间空白差异→不同 hash")
    void sha256_exactMatch() {
        assertEquals(SlowSqlParser.sha256Hex("select * from t"),
                     SlowSqlParser.sha256Hex("  select * from t  "));
        assertNotEquals(SlowSqlParser.sha256Hex("select * from t"),
                        SlowSqlParser.sha256Hex("select  *  from t"));
        assertEquals(64, SlowSqlParser.sha256Hex("x").length());
    }

    @Test
    @DisplayName("serviceName 派生领域：dept/loan/comm/sett/public → 存款/贷款/公共/结算/全领域")
    void domainOf_mapping() {
        assertEquals("存款", SlowSqlParser.domainOf("ccbs-dept-online"));
        assertEquals("存款", SlowSqlParser.domainOf("ccbs-dept-hotspot"));
        assertEquals("贷款", SlowSqlParser.domainOf("ccbs-loan-online"));
        assertEquals("公共", SlowSqlParser.domainOf("ccbs-comm-online"));
        assertEquals("公共", SlowSqlParser.domainOf("ccbs-comm-hotspot"));
        assertEquals("结算", SlowSqlParser.domainOf("ccbs-sett-online"));
        assertEquals("全领域", SlowSqlParser.domainOf("ccbs-public-batch"));
        assertEquals("其他", SlowSqlParser.domainOf("ccbs-unknown-x"));
        assertEquals("其他", SlowSqlParser.domainOf(null));
    }

    @Test
    @DisplayName("serviceName 派生类型：online/hotspot/batch → 联机/热点账户/批量")
    void bizTypeOf_mapping() {
        assertEquals("联机", SlowSqlParser.bizTypeOf("ccbs-dept-online"));
        assertEquals("热点账户", SlowSqlParser.bizTypeOf("ccbs-comm-hotspot"));
        assertEquals("热点账户", SlowSqlParser.bizTypeOf("ccbs-dept-hotspot"));
        assertEquals("批量", SlowSqlParser.bizTypeOf("ccbs-public-batch"));
        assertEquals("其他", SlowSqlParser.bizTypeOf("ccbs-dept-foo"));
        assertEquals("其他", SlowSqlParser.bizTypeOf(null));
    }

    @Test
    @DisplayName("轮次：当日无→-1；已有-1,-2→-3；跨日重置")
    void nextRound_sequence() {
        LocalDate d = LocalDate.of(2025, 6, 1);
        assertEquals("20250601-1", SlowSqlParser.nextRound(d, List.of()));
        assertEquals("20250601-3", SlowSqlParser.nextRound(d, List.of("20250601-1", "20250601-2")));
        // 跨日：当天没有任何轮次
        assertEquals("20250601-1", SlowSqlParser.nextRound(d, List.of("20250531-7")));
        // 乱序/脏数据容错
        assertEquals("20250601-6", SlowSqlParser.nextRound(d, List.of("20250601-5", "20250601-x", "20250601-2")));
    }

    // ── v2：E 列来源分类 / sqlsId 提取 / 模块→领域 ──

    @Test
    @DisplayName("v2 E列分类：无冒号→平台；含Entity→odb；有冒号无Entity→nsql")
    void locationKindOf_rules() {
        // 平台：无 ":" / 空 / null
        assertEquals(SlowSqlParser.LocationKind.PLATFORM, SlowSqlParser.locationKindOf("BUF0101"));
        assertEquals(SlowSqlParser.LocationKind.PLATFORM, SlowSqlParser.locationKindOf(""));
        assertEquals(SlowSqlParser.LocationKind.PLATFORM, SlowSqlParser.locationKindOf(null));
        // odb：含 ":" 且含 Entity
        assertEquals(SlowSqlParser.LocationKind.ODB, SlowSqlParser.locationKindOf(
                "S010010048.CorpDmdOpnccnt:Kapp_serl_num.kapp_serl_num.Entity.selectByIndexWithLock_odb1"));
        // nsql：含 ":" 不含 Entity
        assertEquals(SlowSqlParser.LocationKind.NSQL, SlowSqlParser.locationKindOf(
                "BLP7001:DpcbUD55Qry.sel_kdpb_cb_async_task_register_qry"));
        assertEquals(SlowSqlParser.LocationKind.NSQL, SlowSqlParser.locationKindOf(
                "S010030002.AcctNoCorpHvQry:LnAcctBusi.sel_klnl_loan_txt_opnac_dt_txnamt"));
    }

    @Test
    @DisplayName("v3 E列新规则：剥[]包裹；[]空→平台；冒号后数字开头→平台；冒号后为空→平台")
    void locationKindOf_v3_rules() {
        // 剥 []：normalizeLocation
        assertEquals("BGBP101", SlowSqlParser.normalizeLocation("[BGBP101]"));
        assertEquals("", SlowSqlParser.normalizeLocation("[]"));
        assertEquals("", SlowSqlParser.normalizeLocation(null));
        assertEquals("a:b", SlowSqlParser.normalizeLocation(" [a:b] "));
        // [] 空 / 剥后无冒号 → 平台
        assertEquals(SlowSqlParser.LocationKind.PLATFORM, SlowSqlParser.locationKindOf("[]"));
        assertEquals(SlowSqlParser.LocationKind.PLATFORM, SlowSqlParser.locationKindOf("[BGBP101]"));
        // 冒号后以数字开头（IP:端口 / 集群心跳）→ 平台
        assertEquals(SlowSqlParser.LocationKind.PLATFORM, SlowSqlParser.locationKindOf(
                "[BatchCluster.21.42.23.137:9010LeaseElectionThread]"));
        assertEquals(SlowSqlParser.LocationKind.PLATFORM, SlowSqlParser.locationKindOf(
                "[21.42.23.106:9010-NodeHeart]"));
        assertEquals(SlowSqlParser.LocationKind.PLATFORM, SlowSqlParser.locationKindOf(
                "[ReverseClientCluster.21.42.23.125:9502LeaseElectionThread]"));
        // 冒号后为空 → 平台
        assertEquals(SlowSqlParser.LocationKind.PLATFORM, SlowSqlParser.locationKindOf("TX0001:"));
        // 带 [] 的 odb / nsql 仍正常分类
        assertEquals(SlowSqlParser.LocationKind.ODB, SlowSqlParser.locationKindOf(
                "[S120020919.AnlspnCorpMntn:Kdpa_cb_cust_acct_num.kdpa_cb_cust_acct_num.Entity.selectByIndex_odb1]"));
        assertEquals(SlowSqlParser.LocationKind.NSQL, SlowSqlParser.locationKindOf(
                "[S090020261.CBSPRcnclApl:PyDataQry.sel_kstb_lclccy_pymt_acg_inf_for_count]"));
        // 带 [] 时 nsqlIdOf / afterColon 同样剥壳生效
        assertEquals("PyDataQry", SlowSqlParser.nsqlIdOf(
                "[S090020261.CBSPRcnclApl:PyDataQry.sel_kstb_lclccy_pymt_acg_inf_for_count]"));
        assertEquals("Kdpa_cb_cust_acct_num.kdpa_cb_cust_acct_num.Entity.selectByIndex_odb1",
                SlowSqlParser.locationAfterColon(
                        "[S120020919.AnlspnCorpMntn:Kdpa_cb_cust_acct_num.kdpa_cb_cust_acct_num.Entity.selectByIndex_odb1]"));
    }

    @Test
    @DisplayName("v2 nsqlId：取冒号后段首段；无冒号→空串")
    void nsqlIdOf_extract() {
        assertEquals("DpcbUD55Qry", SlowSqlParser.nsqlIdOf(
                "BLP7001:DpcbUD55Qry.sel_kdpb_cb_async_task_register_qry"));
        assertEquals("LnAcctBusi", SlowSqlParser.nsqlIdOf(
                "S010030002.AcctNoCorpHvQry:LnAcctBusi.sel_klnl_loan_txt_opnac_dt_txnamt"));
        // 冒号后无 "."：整段即 sqlsId
        assertEquals("OnlyId", SlowSqlParser.nsqlIdOf("TX0001:OnlyId"));
        assertEquals("", SlowSqlParser.nsqlIdOf("BUF0101"));
        assertEquals("", SlowSqlParser.nsqlIdOf(null));
    }

    @Test
    @DisplayName("v3 采集过滤：前缀大小写不敏感命中；空名单/不命中→false")
    void startsWithAnyPrefix_rules() {
        List<String> prefixes = List.of("EXPLAIN", "SET");
        assertTrue(SlowSqlParser.startsWithAnyPrefix(
                "EXPLAIN (FORMAT JSON) select * from t", prefixes));
        assertTrue(SlowSqlParser.startsWithAnyPrefix("explain select 1", prefixes));   // 大小写不敏感
        assertTrue(SlowSqlParser.startsWithAnyPrefix("  set session timeout=1", prefixes)); // 前导空白 trim
        assertTrue(SlowSqlParser.startsWithAnyPrefix("SET NAMES utf8", prefixes));
        // 不以名单前缀开头 → 采集
        assertFalse(SlowSqlParser.startsWithAnyPrefix("select * from kdpa_cb_acct_info", prefixes));
        assertFalse(SlowSqlParser.startsWithAnyPrefix("update t set a=1", prefixes));  // SET 在中间不算
        // 边界
        assertFalse(SlowSqlParser.startsWithAnyPrefix("select 1", List.of()));
        assertFalse(SlowSqlParser.startsWithAnyPrefix(null, prefixes));
    }

    @Test
    @DisplayName("v2 模块→领域：dept/loan/comm/sett/public 命中；null/other→其他")
    void domainOfModule_map() {
        assertEquals("存款", SlowSqlParser.domainOfModule("dept-bcc"));
        assertEquals("贷款", SlowSqlParser.domainOfModule("loan-bcc"));
        assertEquals("公共", SlowSqlParser.domainOfModule("comm-bcc"));
        assertEquals("结算", SlowSqlParser.domainOfModule("sett-bcc"));
        assertEquals("全领域", SlowSqlParser.domainOfModule("public-bcc"));
        assertEquals("其他", SlowSqlParser.domainOfModule("other"));
        assertEquals("其他", SlowSqlParser.domainOfModule(null));
    }
}
