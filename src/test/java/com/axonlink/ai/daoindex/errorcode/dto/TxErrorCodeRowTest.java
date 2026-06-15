package com.axonlink.ai.daoindex.errorcode.dto;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** TxErrorCodeRow 字段顺序锁定（与 dii_tx_error_code 写库列序一致）。 */
class TxErrorCodeRowTest {

    @Test
    void fieldOrderLocked() {
        List<String> actual = Arrays.stream(TxErrorCodeRow.class.getDeclaredFields())
                .filter(f -> !f.isSynthetic())
                .map(Field::getName)
                .collect(Collectors.toList());
        List<String> expected = List.of(
                "txId", "txName", "domainKey", "errorCode", "errorScope", "throwText",
                "classFqn", "methodName", "filePath", "lineNo", "moduleName",
                "componentCode", "componentName", "matchStatus", "throwSeq");
        assertEquals(expected, actual);
    }

    @Test
    void allArgsAndGetters() {
        TxErrorCodeRow r = new TxErrorCodeRow(
                "TC0033", "存款查询", "deposit", "E0003", "CmError.Brch",
                "throw CmError.Brch.E0003(x)", "com.x.CmError", "validate",
                "/abs/CmError.java", 42, "loan-bcc",
                "SVC001", "存款服务", "MATCHED", 7L);
        assertEquals("TC0033", r.getTxId());
        assertEquals("deposit", r.getDomainKey());
        assertEquals("SVC001", r.getComponentCode());
        assertEquals("MATCHED", r.getMatchStatus());
        assertEquals(Long.valueOf(7L), r.getThrowSeq());
    }
}
