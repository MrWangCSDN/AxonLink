package com.axonlink.ai.daoindex.errorcode.dto;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ErrorCodeThrow 字段顺序锁定 + getter 行为锁定。
 * 字段顺序与 V24 dii_error_code 写库列顺序一一对应，改顺序即破坏 batchInsert 绑定。
 */
class ErrorCodeThrowTest {

    /** 锁定字段名与声明顺序（与 dii_error_code INSERT 列序一致）。 */
    @Test
    void fieldOrderLocked() {
        List<String> actual = Arrays.stream(ErrorCodeThrow.class.getDeclaredFields())
                .filter(f -> !f.isSynthetic())
                .map(Field::getName)
                .collect(Collectors.toList());
        List<String> expected = List.of(
                "errorCode", "errorScope", "throwText", "classFqn", "methodName",
                "filePath", "lineNo", "moduleName", "innerClassName", "codeSignature", "throwSeq");
        assertEquals(expected, actual);
    }

    /** 全参构造 + getter 回读一致。 */
    @Test
    void allArgsAndGetters() {
        ErrorCodeThrow t = new ErrorCodeThrow(
                "E0003", "CmError.Brch", "throw CmError.Brch.E0003(x)",
                "com.x.CmError", "validate",
                "/abs/CmError.java", 42, "loan-bcc", null, null, 7L);
        assertEquals("E0003", t.getErrorCode());
        assertEquals("CmError.Brch", t.getErrorScope());
        assertEquals("throw CmError.Brch.E0003(x)", t.getThrowText());
        assertEquals("com.x.CmError", t.getClassFqn());
        assertEquals("validate", t.getMethodName());
        assertEquals("/abs/CmError.java", t.getFilePath());
        assertEquals(Integer.valueOf(42), t.getLineNo());
        assertEquals("loan-bcc", t.getModuleName());
        assertEquals(Long.valueOf(7L), t.getThrowSeq());
    }
}
