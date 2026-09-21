package com.axonlink.ai.replay.dbcompare.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConfigScriptTargetTest {
    @Test void legacyDropsOnlyUnsupportedPartitionColumnsAndTheirValues() {
        String sql = "INSERT INTO tss_bcomp_conf\n(bcomp_index,bcomp_module,bcomp_name,bcomp_memo,bcomp_type,bcomp_range,bcomp_time_node,bcomp_state,bcomp_partition_num,bcomp_shard_strategy) VALUES\n(1,'a','b','x,y;z','2','1','3','1',16,'HASH'),\n(2,'a','b','memo','2','1','3','1',1,'HASH');";
        String result = ConfigScriptTarget.LEGACY.convert(sql);
        assertFalse(result.contains("bcomp_partition_num"));
        assertFalse(result.contains("'HASH'"));
        assertTrue(result.contains("(1,'a','b','x,y;z','2','1','3','1')"));
        assertTrue(result.contains("(2,'a','b','memo','2','1','3','1')"));
        assertTrue(ConfigScriptTarget.NEW.convert(sql).contains("16,'HASH'"));
    }

    @Test void changesOnlyIdentifiersOutsideLiteralsAndComments() {
        String sql = "TRUNCATE TABLE tss_bcomp_conf;\nINSERT INTO tss_bcomp_table_sql (orig_sql) VALUES ('select * from tss_bcomp_conf where x=''tss_bcomp_field''');\n-- tss_bcomp_conf\n/* tss_bcomp_field */\nINSERT INTO TSS_BCOMP_FIELD (field_name) VALUES ('ID');";
        String result = ConfigScriptTarget.NEW.convert(sql);
        assertTrue(result.startsWith("TRUNCATE TABLE tss_bcomp_conf_new;"));
        assertTrue(result.contains("INSERT INTO tss_bcomp_table_sql_new"));
        assertTrue(result.contains("INSERT INTO TSS_BCOMP_FIELD_NEW"));
        assertTrue(result.contains("'select * from tss_bcomp_conf where x=''tss_bcomp_field'''") );
        assertTrue(result.contains("-- tss_bcomp_conf\n/* tss_bcomp_field */"));
        assertEquals(sql, ConfigScriptTarget.LEGACY.convert(sql));
    }
}
