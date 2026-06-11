package com.axonlink.ai.daoindex.sqlinspect.service;

import com.axonlink.ai.daoindex.config.DaoIndexAnalysisProperties;
import com.axonlink.ai.daoindex.sqlinspect.slowsql.TablesXmlOdbLocationDomainResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("OdbIdProjectIndex —— tables.xml schema id 提取 + 模块查找 + odb 解析器")
class OdbIdProjectIndexTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("extractSchemaId：读根 <schema id>；XML 坏档回退文件名")
    void extractSchemaId_xmlAndFallback() throws Exception {
        // 正常：截图同款结构，根 <schema id="Kdpb_cb_alwc_acml">
        Path ok = tmp.resolve("Kdpb_cb_alwc_acml.tables.xml");
        Files.writeString(ok, """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <schema xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" id="Kdpb_cb_alwc_acml" package="com.spdb.ccbs.dept.bcc.tab">
                  <table id="kdpb_cb_alwc_acml" name="kdpb_cb_alwc_acml" longname="对公限额累计表"/>
                </schema>
                """);
        assertEquals("Kdpb_cb_alwc_acml", OdbIdProjectIndex.extractSchemaId(ok));

        // 坏档：解析失败 → 回退文件名去 .tables.xml 后缀
        Path bad = tmp.resolve("Kapp_serl_num.tables.xml");
        Files.writeString(bad, "not-xml-at-all <<<");
        assertEquals("Kapp_serl_num", OdbIdProjectIndex.extractSchemaId(bad));
    }

    @Test
    @DisplayName("lookupModule：精确命中 + 小写兜底 + 未命中 null")
    void lookupModule_exactAndLowercase() {
        OdbIdProjectIndex index = new OdbIdProjectIndex(new DaoIndexAnalysisProperties());
        index.replaceForTesting(Map.of(
                "Kapp_serl_num", "dept-bcc",
                "Kdpb_cb_alwc_acml", "dept-bcc"));
        assertEquals("dept-bcc", index.lookupModule("Kapp_serl_num"));
        assertEquals("dept-bcc", index.lookupModule("kapp_serl_num"));   // 大小写漂移兜底
        assertNull(index.lookupModule("NotExist"));
        assertNull(index.lookupModule(null));
        assertEquals(2, index.size());
    }

    @Test
    @DisplayName("resolver：冒号后段取首段查模块（E列 odb 实例）")
    void resolver_firstSegment() {
        OdbIdProjectIndex index = new OdbIdProjectIndex(new DaoIndexAnalysisProperties());
        index.replaceForTesting(Map.of("Kapp_serl_num", "dept-bcc"));
        TablesXmlOdbLocationDomainResolver resolver = new TablesXmlOdbLocationDomainResolver(index);
        // E 列冒号后段（SlowSqlImportService 传入的就是这一段）
        assertEquals("dept-bcc",
                resolver.resolveModule("Kapp_serl_num.kapp_serl_num.Entity.selectByIndexWithLock_odb1"));
        assertNull(resolver.resolveModule("Unknown.x.Entity.y"));
        assertNull(resolver.resolveModule(""));
        assertNull(resolver.resolveModule(null));
    }
}
