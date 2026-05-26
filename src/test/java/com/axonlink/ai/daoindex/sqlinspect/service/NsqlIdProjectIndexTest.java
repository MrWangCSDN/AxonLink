package com.axonlink.ai.daoindex.sqlinspect.service;

import com.axonlink.ai.daoindex.config.DaoIndexAnalysisProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link NsqlIdProjectIndex} 单元测试。
 *
 * <p>构造内存级文件树：
 * <pre>
 *   tmpRoot/
 *     loan-bcc/src/main/resources/namedsql/ft/FtBusiNamedSql.nsql.xml  → id=FtBusiNamedSql
 *     dept-bcc/src/main/resources/dp/DpCbQryAcctCount.nsql.xml         → id=DpCbQryAcctCount
 *     other-module/foo/Bar.nsql.xml                                    → 不匹配 *-bcc，被过滤
 * </pre>
 *
 * <p>断言：
 * <ul>
 *   <li>命中 → 返回对应模块名</li>
 *   <li>不命中 → "other"</li>
 *   <li>非 *-bcc 模块下的 nsql 文件被忽略</li>
 *   <li>extractSqlsId 直接从文件取 root 标签的 id</li>
 * </ul>
 */
@DisplayName("NsqlIdProjectIndex —— nsql.xml 扫描")
class NsqlIdProjectIndexTest {

    /** 一份能解析的最简 nsql.xml；只有 root <sqls id="..."> 才被读，其余无所谓。 */
    private static String nsqlXml(String id) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
               "<sqls xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" id=\"" + id + "\">\n" +
               "  <select id=\"sel_x\"><sql desc=\"\">select 1</sql></select>\n" +
               "</sqls>\n";
    }

    @Test
    @DisplayName("rebuild() 扫到 *-bcc 模块下的 nsql.xml，构建 sqlsId → 模块名 映射")
    void scanBuildsIndex(@TempDir Path tmp) throws IOException {
        Path loanFile = tmp.resolve("loan-bcc/src/main/resources/namedsql/ft/FtBusiNamedSql.nsql.xml");
        Files.createDirectories(loanFile.getParent());
        Files.writeString(loanFile, nsqlXml("FtBusiNamedSql"), StandardCharsets.UTF_8);

        Path deptFile = tmp.resolve("dept-bcc/src/main/resources/dp/DpCbQryAcctCount.nsql.xml");
        Files.createDirectories(deptFile.getParent());
        Files.writeString(deptFile, nsqlXml("DpCbQryAcctCount"), StandardCharsets.UTF_8);

        // 非 *-bcc 模块：应被 module-pattern 过滤掉
        Path stray = tmp.resolve("other-module/foo/Bar.nsql.xml");
        Files.createDirectories(stray.getParent());
        Files.writeString(stray, nsqlXml("Bar"), StandardCharsets.UTF_8);

        DaoIndexAnalysisProperties props = new DaoIndexAnalysisProperties();
        props.getScan().setProjectRoots(List.of(tmp.toAbsolutePath().toString()));
        props.getScan().setModulePattern("*-bcc");

        NsqlIdProjectIndex index = new NsqlIdProjectIndex(props);
        index.rebuild();

        assertEquals("loan-bcc", index.lookupProject("FtBusiNamedSql"));
        assertEquals("dept-bcc", index.lookupProject("DpCbQryAcctCount"));
        assertEquals(NsqlIdProjectIndex.UNKNOWN_PROJECT, index.lookupProject("Bar"),
                "非 *-bcc 模块下的文件应被忽略");
        assertEquals(NsqlIdProjectIndex.UNKNOWN_PROJECT, index.lookupProject("NotExisted"));
        assertEquals(2, index.size());
    }

    @Test
    @DisplayName("空 project-roots → 全部回退 other")
    void emptyRoots() {
        DaoIndexAnalysisProperties props = new DaoIndexAnalysisProperties();
        // 不设 projectRoots，默认空
        NsqlIdProjectIndex index = new NsqlIdProjectIndex(props);
        index.rebuild();

        assertEquals(NsqlIdProjectIndex.UNKNOWN_PROJECT, index.lookupProject("AnyId"));
        assertEquals(0, index.size());
    }

    @Test
    @DisplayName("extractSqlsId 直接读 root <sqls id=...>")
    void extractSqlsIdSingleFile(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("Foo.nsql.xml");
        Files.writeString(f, nsqlXml("FooNamedSql"), StandardCharsets.UTF_8);
        assertEquals("FooNamedSql", NsqlIdProjectIndex.extractSqlsId(f));
    }

    @Test
    @DisplayName("extractSqlsId 解析失败 → null")
    void extractSqlsIdBrokenXml(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("Broken.nsql.xml");
        Files.writeString(f, "<not-well-formed>", StandardCharsets.UTF_8);
        assertNull(NsqlIdProjectIndex.extractSqlsId(f));
    }
}
