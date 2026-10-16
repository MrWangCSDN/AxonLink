package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.persistence.ReplayDatabaseTablePersonDao;
import com.axonlink.ai.user.persistence.SysUserDao;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockMultipartFile;
import java.io.ByteArrayOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class ReplayDatabaseTablePersonImportServiceTest {
    private ReplayDatabaseTablePersonDao dao;
    private ReplayDatabaseTablePersonImportService service;
    private JdbcTemplate jdbc;

    @BeforeEach void setUp() {
        jdbc = new JdbcTemplate(new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .setName("table_person_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1").build());
        new ResourceDatabasePopulator(new ClassPathResource("db/daoindex/V82__replay_database_table_person.sql"))
                .execute(jdbc.getDataSource());
        jdbc.execute("CREATE TABLE ccbs_ai_sys_user (id BIGINT AUTO_INCREMENT PRIMARY KEY, username VARCHAR(128), real_name VARCHAR(128), emp_no VARCHAR(64), email VARCHAR(128), phone VARCHAR(64), department VARCHAR(128), status INT, remark VARCHAR(255), creator_id BIGINT, create_time DATETIME, updater_id BIGINT, update_time DATETIME)");
        jdbc.update("INSERT INTO ccbs_ai_sys_user(username,real_name,emp_no,status) VALUES ('dev1','张三',NULL,1),('owner1','王五','2001',1)");
        dao = new ReplayDatabaseTablePersonDao(jdbc);
        service = new ReplayDatabaseTablePersonImportService(new ReplayDatabaseTablePersonExcelParser(), dao, new SysUserDao(jdbc));
    }

    @Test void importsSearchesAndNormalizesTableNames() throws Exception {
        var result=service.importFile(workbook(new String[][]{{"贷款组"," KOBA_A ","表A","张三(dev1)","王五(owner1)"}}));
        assertTrue(result.imported());
        var row=dao.findByTableName("koba_A");
        assertEquals("koba_a",row.tableName());
        assertEquals("贷款组",row.domain());
        assertEquals("张三(dev1)",row.developer());
        assertEquals("2001",row.technologyOwnerEmpNos());
        assertEquals(1,dao.count("王五"));
        assertEquals(0,dao.count("不存在"));
    }

    @Test void duplicateOrInvalidPeopleKeepPreviousList() throws Exception {
        service.importFile(workbook(new String[][]{{"贷款组","OLD","旧表","",""}}));
        var result=service.importFile(workbook(new String[][]{
                {"贷款组","KOBA_A","表A","张三","张三"},
                {"贷款组","koba_a","表A","",""}}));
        assertFalse(result.imported());
        assertTrue(result.errors().stream().anyMatch(error->error.reason().contains("工号")));
        assertTrue(result.errors().stream().anyMatch(error->error.reason().contains("重复")));
        assertNotNull(dao.findByTableName("old"));
        assertEquals(1,dao.count(null));
    }

    private MockMultipartFile workbook(String[][] values)throws Exception {
        try(var workbook=new XSSFWorkbook();var output=new ByteArrayOutputStream()){
            var sheet=workbook.createSheet("数据库表人员清单");
            var header=sheet.createRow(0);
            for(int i=0;i<5;i++)header.createCell(i).setCellValue(ReplayDatabaseTablePersonExcelParser.HEADERS.get(i));
            for(int r=0;r<values.length;r++){
                var row=sheet.createRow(r+1);
                for(int c=0;c<5;c++)row.createCell(c).setCellValue(values[r][c]);
            }
            workbook.write(output);
            return new MockMultipartFile("file","人员.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",output.toByteArray());
        }
    }
}
