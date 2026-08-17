package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.persistence.ReplayTransactionPersonDao;
import com.axonlink.ai.user.persistence.SysUserDao;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ReplayTransactionPersonImportServiceTest {
    private JdbcTemplate jdbc;
    private ReplayTransactionPersonDao dao;
    private ReplayTransactionPersonImportService service;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .setName("replay_person_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1").build());
        jdbc.execute("CREATE TABLE dii_replay_transaction_person (id BIGINT AUTO_INCREMENT PRIMARY KEY, domain VARCHAR(64) NOT NULL, old_transaction_code VARCHAR(64) UNIQUE NOT NULL, old_transaction_name VARCHAR(256), developer VARCHAR(512), developer_usernames VARCHAR(512), bank_owner VARCHAR(512), bank_owner_emp_nos VARCHAR(512), imported_at DATETIME NOT NULL)");
        jdbc.execute("CREATE TABLE ccbs_ai_sys_user (id BIGINT AUTO_INCREMENT PRIMARY KEY, username VARCHAR(128), real_name VARCHAR(128), emp_no VARCHAR(64), email VARCHAR(128), phone VARCHAR(64), department VARCHAR(128), status INT, remark VARCHAR(255), creator_id BIGINT, create_time DATETIME, updater_id BIGINT, update_time DATETIME)");
        jdbc.update("INSERT INTO ccbs_ai_sys_user(username,real_name,emp_no,status) VALUES ('dev1','张三','1001',1),('dev2','李四','1002',1),('owner1','王五','2001',1)");
        dao = new ReplayTransactionPersonDao(jdbc);
        service = new ReplayTransactionPersonImportService(new ReplayTransactionPersonExcelParser(), dao, new SysUserDao(jdbc));
    }

    @Test
    void importsBlankPeopleAndMatchesMultiplePeopleByNameAndUsername() throws Exception {
        var result = service.importFile(workbook(new String[][]{
                {"贷款", "6001", "交易一", "张三(dev1)、李四", "王五"},
                {"贷款", "6002", "交易二", "", ""}
        }));

        assertTrue(result.imported());
        assertEquals(2, dao.count(null));
        var rows = dao.list(null, 50, 0);
        assertEquals("dev1、dev2", rows.get(0).developerUsernames());
        assertEquals("张三(dev1)、李四(dev2)", rows.get(0).developer());
        assertEquals("王五(owner1)", rows.get(0).bankOwner());
        assertEquals("2001", rows.get(0).bankOwnerEmpNos());
        assertNull(rows.get(1).developer());
        assertNull(rows.get(1).bankOwner());
    }

    @Test
    void duplicateCodeAndAmbiguousOrMissingPeopleRejectWholeReplacement() throws Exception {
        jdbc.update("INSERT INTO dii_replay_transaction_person(domain,old_transaction_code,old_transaction_name,imported_at) VALUES ('旧','OLD','旧数据',CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO ccbs_ai_sys_user(username,real_name,emp_no,status) VALUES ('dev3','李四','1003',1)");

        var result = service.importFile(workbook(new String[][]{
                {"贷款", "6001", "交易一", "李四", "不存在"},
                {"贷款", "6001", "交易二", "", ""}
        }));

        assertFalse(result.imported());
        assertTrue(result.errors().stream().anyMatch(e -> e.reason().contains("多个")));
        assertTrue(result.errors().stream().anyMatch(e -> e.reason().contains("未匹配")));
        assertTrue(result.errors().stream().anyMatch(e -> e.reason().contains("重复")));
        assertEquals("OLD", dao.list(null, 50, 0).get(0).oldTransactionCode());
    }

    private MockMultipartFile workbook(String[][] data) throws Exception {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Sheet1");
            var header = sheet.createRow(0);
            String[] headers = {"领域", "老交易码", "老交易名称", "开发人员", "行内负责人"};
            for (int i = 0; i < headers.length; i++) header.createCell(i).setCellValue(headers[i]);
            for (int r = 0; r < data.length; r++) {
                var row = sheet.createRow(r + 1);
                for (int c = 0; c < data[r].length; c++) row.createCell(c).setCellValue(data[r][c]);
            }
            workbook.write(output);
            return new MockMultipartFile("file", "persons.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", output.toByteArray());
        }
    }
}
