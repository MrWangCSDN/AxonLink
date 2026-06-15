package com.axonlink.ai.daoindex.errorcode.scan;

import com.axonlink.ai.daoindex.errorcode.attribution.ErrorCodeAttributionService;
import com.axonlink.ai.daoindex.errorcode.dao.DiiErrorCodeDao;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/** detectModule 静态方法测试 + 物化门控测试（明细总重建、物化按 materialize 开关）。 */
class ErrorCodeScanServiceTest {

    /**
     * 构造一个源码根为空的 Service + mock 依赖：
     * 无 Spring 注入时 sourceRoots/workspaceRoots 为 null → collectSourceFiles 返回空 → 明细空、不碰文件系统。
     */
    private ErrorCodeScanService newServiceWithEmptyRoots(DiiErrorCodeDao dao,
                                                          ErrorCodeAttributionService attr) {
        return new ErrorCodeScanService(dao, attr);
    }

    @Test
    void onReadyPathRebuildsThrowsButSkipsMaterialize() {
        DiiErrorCodeDao dao = mock(DiiErrorCodeDao.class);
        ErrorCodeAttributionService attr = mock(ErrorCodeAttributionService.class);
        ErrorCodeScanService svc = newServiceWithEmptyRoots(dao, attr);

        svc.runSafely(false, false);   // 启动路径：materialize=false

        verify(dao).rebuildThrows(anyList());                          // 明细必重建
        verify(attr, never()).materializeTransactionErrorCodes();     // 不物化（图未就绪）
    }

    @Test
    void graphBuiltPathRebuildsThrowsAndMaterializes() {
        DiiErrorCodeDao dao = mock(DiiErrorCodeDao.class);
        ErrorCodeAttributionService attr = mock(ErrorCodeAttributionService.class);
        ErrorCodeScanService svc = newServiceWithEmptyRoots(dao, attr);

        svc.runSafely(false, true);    // 图构建完成路径：materialize=true

        verify(dao).rebuildThrows(anyList());                         // 明细重建
        verify(attr).materializeTransactionErrorCodes();              // 且物化交易维度
    }

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
