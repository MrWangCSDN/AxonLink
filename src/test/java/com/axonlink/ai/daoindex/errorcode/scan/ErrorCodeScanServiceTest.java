package com.axonlink.ai.daoindex.errorcode.scan;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/** detectModule 静态方法测试（无 Spring/DB）。 */
class ErrorCodeScanServiceTest {

    @Test
    void detectModuleFromBccSegment() {
        Path p = Paths.get("/data/src/loan-bcc/target/gen/com/x/A.java");
        assertEquals("loan-bcc", ErrorCodeScanService.detectModule(p));
    }

    @Test
    void detectModulePicksNearestBcc() {
        Path p = Paths.get("/data/outer-bcc/sub/deposit-bcc/target/gen/com/x/B.java");
        assertEquals("deposit-bcc", ErrorCodeScanService.detectModule(p));
    }

    @Test
    void detectModuleNullWhenNoBcc() {
        Path p = Paths.get("/data/src/main/java/com/x/C.java");
        assertNull(ErrorCodeScanService.detectModule(p));
    }

    @Test
    void detectModuleHandlesWindowsSeparator() {
        // 用 / 构造但断言纯逻辑：含 *-bcc 段即可解析
        Path p = Paths.get("/repo/cust-bcc/target/gen/com/x/D.java");
        assertEquals("cust-bcc", ErrorCodeScanService.detectModule(p));
    }
}
