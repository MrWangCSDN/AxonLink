package com.axonlink.ai.replay.dbissue;

import com.axonlink.ai.replay.ReplayIssueTestFixtures;
import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.user.persistence.SysUserDao;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReplayDatabaseIssueTest {
    private final ReplayDatabaseIssueExcelParser parser = new ReplayDatabaseIssueExcelParser();
    private JdbcTemplate jdbc;
    private ReplayDatabaseIssueDao dao;
    private ReplayDatabaseIssueService service;
    private SysUserDao users;
    private final ReplayIssueOperator actor = new ReplayIssueOperator("editor", "编辑人");

    @BeforeEach void setup() {
        jdbc=ReplayIssueTestFixtures.newJdbc();
        ReplayDatabaseIssueReviewProjectionTest.installJsonHelpers(jdbc);
        new ResourceDatabasePopulator(new ClassPathResource("db/daoindex/V62__dii_replay_database_comparison_fields.sql"),
                new ClassPathResource("db/daoindex/V82__replay_database_table_person.sql"),
                new ClassPathResource("db/daoindex/V77__replay_database_issues.sql"),new ClassPathResource("db/daoindex/V78__replay_database_issue_review_projection.sql"),new ClassPathResource("db/daoindex/V79__replay_database_issue_repair_date_projection.sql"),new ClassPathResource("db/daoindex/V80__replay_database_issue_collaborator_projection.sql"),new ClassPathResource("db/user/01_user_ddl.sql")).execute(jdbc.getDataSource());
        dao=new ReplayDatabaseIssueDao(jdbc);
        jdbc.update("INSERT INTO ccbs_ai_sys_user(username,real_name,emp_no,status) VALUES ('editor','编辑人','E001',1)");
        users=new SysUserDao(jdbc);
        service=new ReplayDatabaseIssueService(parser,dao,users,
                Clock.fixed(Instant.parse("2026-10-12T02:00:00Z"),ZoneId.of("Asia/Shanghai")));
    }
    @Test void parsesBothDirectionsAndFieldIdsIgnoringReportOwnerAndKeepingZeroCountScope() {
        var parsed=parser.parse(file(true,"DB20261012-01",3,1,2),null);
        assertEquals(List.of("koba_off_sheet_acct-ccbs","koba_off_sheet_acct-528","koba_off_sheet_acct-dtl_sn"),
                parsed.issues().stream().map(ReplayDatabaseIssueExcelParser.IssueInput::issueId).toList());
        assertEquals(3L,parsed.issues().get(0).differenceCount());
        assertEquals("",parsed.issues().get(0).owner());assertEquals("",parsed.issues().get(0).domain());
        assertEquals("仅ccbs",parsed.issues().get(0).issueLevel());
        assertEquals(LocalDate.of(2026,10,12),parsed.batchDate());
        assertTrue(parser.parse(file(false,"",0,0,0),"DB20261012-01").issues().isEmpty());
        assertEquals(Set.of("koba_off_sheet_acct"),parsed.tables());
    }
    @Test void parsesRealBdExportIncludingDirectionTitleAndAppendedBatch() throws Exception {
        var fixture=new ClassPathResource("replay-db-issues/bd-export-DB20261012-01.xlsx");
        var parsed=parser.parse(new MockMultipartFile("file","bd.xlsx","",fixture.getInputStream()),null);
        assertEquals("DB20261012-01",parsed.batchNo());
        assertEquals(List.of("koba_off_sheet_acct-ccbs","koba_off_sheet_acct-528","koba_off_sheet_acct-amount"),parsed.issues().stream().map(ReplayDatabaseIssueExcelParser.IssueInput::issueId).toList());
        assertEquals(List.of(3L,1L,2L),parsed.issues().stream().map(ReplayDatabaseIssueExcelParser.IssueInput::differenceCount).toList());
    }
    @Test void importsIgnoreMissingOrConflictingReportDomainAndOwnerAndSnapshotCurrentRegistration() throws Exception {
        jdbc.update("INSERT INTO dii_replay_db_compare_registration(schema_name,table_name,table_comment,domain_name,reviser_emp_no,reviser_name,registered_date,created_by,created_name,created_at,updated_by,updated_name,updated_at) VALUES ('public','KOBA_OFF_SHEET_ACCT','登记表中文名','登记领域',' R009 ',' 登记修订人 ','2026-10-12','editor','编辑人',CURRENT_TIMESTAMP,'editor','编辑人',CURRENT_TIMESTAMP)");
        com.axonlink.ai.replay.ReplayDatabaseTablePersonTestFixtures.sync(jdbc, "koba_off_sheet_acct");
        try(var workbook=new XSSFWorkbook(file(true,"DB20261012-01",3,1,2).getInputStream());var out=new ByteArrayOutputStream()) {
            for(var sheet:workbook){
                sheet.getRow(0).getCell(0).setCellValue("未使用领域列");
                sheet.getRow(0).getCell(2).setCellValue("未使用负责人列");
                sheet.getRow(1).getCell(0).setCellValue("表");
                sheet.getRow(1).getCell(2).setCellValue(sheet.getSheetName().equals("表明细")?"仅ccbs存在":"差异数量");
            }
            workbook.write(out);
            var input=new MockMultipartFile("file","no-metadata.xlsx","",out.toByteArray());
            var parsed=parser.parse(input,null);
            assertTrue(parsed.issues().stream().allMatch(row->row.domain().isEmpty()&&row.owner().isEmpty()));
            service.importFile(input,null,actor);
            var stored=dao.findByIssueId("koba_off_sheet_acct-ccbs");
            assertEquals("登记领域",stored.get("domain"));assertEquals("登记修订人(R009)",stored.get("owner"));
            assertEquals("登记表中文名",stored.get("tableComment"));
            var history=dao.history(((Number)stored.get("id")).longValue()).get(0);
            assertEquals("登记领域",((Map<?,?>)history.get("incoming")).get("domain"));
            assertEquals("登记修订人(R009)",((Map<?,?>)history.get("after")).get("owner"));
            for(var sheet:workbook){
                sheet.getRow(0).getCell(0).setCellValue("领域");sheet.getRow(0).getCell(2).setCellValue("负责人");
                sheet.getRow(1).getCell(0).setCellValue(sheet.getSheetName()+"x".repeat(200));
                sheet.getRow(1).getCell(2).setCellValue(sheet.getSheetName()+"y".repeat(300));
            }
            out.reset();workbook.write(out);
            service.importFile(new MockMultipartFile("file","ignored-metadata.xlsx","",out.toByteArray()),null,actor);
            assertEquals("登记领域",dao.findByIssueId("koba_off_sheet_acct-dtl_sn").get("domain"));
            jdbc.update("UPDATE dii_replay_db_compare_registration SET domain_name='更新领域',reviser_name='更新修订人',reviser_emp_no=' R010 '");
            com.axonlink.ai.replay.ReplayDatabaseTablePersonTestFixtures.sync(jdbc, "koba_off_sheet_acct");
            service.importFile(file(true,"DB20261013-01",0,0,0),null,actor);
            var repaired=dao.findByIssueId("koba_off_sheet_acct-ccbs");
            assertEquals("已修复",repaired.get("issueStatus"));assertEquals("更新领域",repaired.get("domain"));
            assertEquals("更新修订人(R010)",repaired.get("owner"));
            var events=dao.history(((Number)repaired.get("id")).longValue());
            assertEquals("更新领域",((Map<?,?>)events.get(0).get("after")).get("domain"));
            assertEquals("登记领域",((Map<?,?>)events.get(1).get("after")).get("domain"));
        }
    }
    @Test void structuralHeaderMissingColumnsStillReportsSheetAndRowWithoutDomainHeader() throws Exception {
        try(var workbook=new XSSFWorkbook(file(true,"DB20261012-01",3,1,2).getInputStream());var out=new ByteArrayOutputStream()) {
            workbook.getSheet("表字段明细").getRow(0).getCell(0).setCellValue("忽略领域");
            workbook.getSheet("表字段明细").getRow(0).getCell(4).setCellValue("缺少数量列");
            workbook.write(out);
            var error=assertThrows(IllegalArgumentException.class,()->parser.parse(new MockMultipartFile("file","missing-column.xlsx","",out.toByteArray()),null));
            assertTrue(error.getMessage().contains("Sheet“表字段明细”第1行"),error.getMessage());
            assertTrue(error.getMessage().contains("差异数量"),error.getMessage());
        }
    }
    @Test void rejectsTruncatedPositiveFieldDetailsBeforeAnyStateChange() throws Exception {
        service.importFile(file(true,"DB20261012-01",3,0,2),null,actor);
        try(var workbook=new XSSFWorkbook(file(true,"DB20261013-01",3,0,2).getInputStream());var out=new ByteArrayOutputStream()) {
            workbook.getSheet("表字段明细").getRow(1).getCell(4).setCellValue(1);
            workbook.write(out);
            assertThrows(IllegalArgumentException.class,()->service.importFile(new MockMultipartFile("file","truncated.xlsx","",out.toByteArray()),null,actor));
        }
        assertEquals(2L,dao.findByIssueId("koba_off_sheet_acct-dtl_sn").get("differenceCount"));
        assertEquals("DB20261012-01",dao.findByIssueId("koba_off_sheet_acct-dtl_sn").get("batchNo"));
    }
    @Test void supplementalReviewReasonPreservesFirstApprovalDate() {
        service.importFile(file(true,"DB20261012-01",3,0,0),null,actor);
        var row=dao.findByIssueId("koba_off_sheet_acct-ccbs");long id=((Number)row.get("id")).longValue();
        row=service.update(id,Map.of("version",row.get("version"),"issueStatus","无需处理","issueType","合理差异"),actor);
        registerTechnologyOwner();
        row=service.approve(id,Map.of("version",row.get("version"),"reason","已核实"),actor);
        var tomorrow=new ReplayDatabaseIssueService(parser,dao,users,Clock.fixed(Instant.parse("2026-10-13T02:00:00Z"),ZoneId.of("Asia/Shanghai")));
        row=tomorrow.approve(id,Map.of("version",row.get("version"),"reason","补充说明"),actor);
        assertEquals("2026-10-12",row.get("defectRepairDate"));
        assertEquals("补充说明",row.get("reviewReason"));
        assertEquals(1L,dao.list(Map.of("defectRepairDates",List.of("2026-10-12"))).get("total"));
    }
    @Test void rejectsInvalidCountAndMixedOrMissingBatchBeforeWriting() {
        assertThrows(IllegalArgumentException.class,()->parser.parse(file(true,"DB20261012-01",-1,0,0),null));
        assertThrows(IllegalArgumentException.class,()->parser.parse(file(false,"",1,0,0),null));
        assertThrows(IllegalArgumentException.class,()->parser.parse(file(true,"DB20261012-01",1,0,0),"DB20261013-01"));
        assertThrows(IllegalArgumentException.class,()->parser.parse(file(true,"DB20260230-01",1,0,0),null));
    }
    @Test void acceptsReorderedColumnsAndRepeatedHeadersButRejectsDuplicateTables() throws Exception {
        try(var workbook=new XSSFWorkbook(file(true,"DB20261012-01",3,1,2).getInputStream());var out=new ByteArrayOutputStream()) {
            for(var sheet:workbook) {
                for(var row:sheet){String first=row.getCell(0).getStringCellValue(),second=row.getCell(1).getStringCellValue();row.getCell(0).setCellValue(second);row.getCell(1).setCellValue(first);}
                var repeated=sheet.createRow(3);for(var cell:sheet.getRow(0))repeated.createCell(cell.getColumnIndex()).setCellValue(cell.getStringCellValue());
            }
            workbook.write(out);
            assertEquals(3,parser.parse(new MockMultipartFile("file","reordered.xlsx","",out.toByteArray()),null).issues().size());
            var sheet=workbook.getSheet("表明细");var duplicate=sheet.createRow(4);
            for(var cell:sheet.getRow(1)){var copy=duplicate.createCell(cell.getColumnIndex());if(cell.getCellType()==org.apache.poi.ss.usermodel.CellType.NUMERIC)copy.setCellValue(cell.getNumericCellValue());else copy.setCellValue(cell.getStringCellValue());}
            out.reset();workbook.write(out);
            assertThrows(IllegalArgumentException.class,()->service.importFile(new MockMultipartFile("file","duplicate.xlsx","",out.toByteArray()),null,actor));
            assertTrue(dao.current().isEmpty());
        }
    }
    @Test void firstImportCreatesNewIssuesAndUsesExistingRegistrationMetadataForOldFiles() {
        jdbc.update("INSERT INTO dii_replay_db_compare_registration(schema_name,table_name,table_comment,domain_name,reviser_username,reviser_name,registered_date,created_by,created_name,created_at,updated_by,updated_name,updated_at) VALUES ('public','koba_off_sheet_acct','表外账户','贷款组','c-zhangs3','张三','2026-10-12','editor','编辑人',CURRENT_TIMESTAMP,'editor','编辑人',CURRENT_TIMESTAMP)");
        com.axonlink.ai.replay.ReplayDatabaseTablePersonTestFixtures.sync(jdbc, "koba_off_sheet_acct");
        var result=service.importFile(file(false,"",3,1,2),"DB20261012-01",actor);
        assertEquals(3,result.get("createdRows"));
        var row=dao.findByIssueId("koba_off_sheet_acct-ccbs");
        assertEquals("新建",row.get("issueStatus"));
        assertEquals("表外账户",row.get("tableComment"));
        assertEquals("张三(c-zhangs3)",row.get("owner"));
        assertEquals("",row.get("issueType"));
        assertEquals(1,service.tracking(((Number)row.get("id")).longValue()).size());
    }
    @Test void preservesHumanContentAndReopensOnlyWhenBatchDateIsAfterManualSaveDate() {
        service.importFile(file(true,"DB20261012-01",3,0,0),null,actor);
        var row=dao.findByIssueId("koba_off_sheet_acct-ccbs");
        long id=((Number)row.get("id")).longValue();
        service.update(id,Map.of("version",row.get("version"),"issueStatus","修复待验证","issueType","需规问题","initialAnalysis","人工分析","finalSolution","修复方案","remark","备注"),actor);
        service.importFile(file(true,"DB20261012-02",2,0,0),null,actor);
        assertEquals("修复待验证",dao.findByIssueId("koba_off_sheet_acct-ccbs").get("issueStatus"));
        service.importFile(file(true,"DB20261013-01",1,0,0),null,actor);
        var after=dao.findByIssueId("koba_off_sheet_acct-ccbs");
        assertEquals("重新打开",after.get("issueStatus"));
        assertEquals("需规问题",after.get("issueType"));
        assertEquals("人工分析",after.get("initialAnalysis"));
        assertEquals("修复方案",after.get("finalSolution"));
        assertEquals("备注",after.get("remark"));
        assertThrows(IllegalArgumentException.class,()->service.update(id,Map.of("version",after.get("version"),"issueStatus","打开","issueType","需规问题"),actor));
    }
    @Test void protectsSameDayBatchWhenImportedOnALaterSystemDate() {
        service.importFile(file(true,"DB20261012-01",3,0,0),null,actor);
        var row=dao.findByIssueId("koba_off_sheet_acct-ccbs");
        long id=((Number)row.get("id")).longValue();
        service.update(id,Map.of("version",row.get("version"),"issueStatus","修复待验证","issueType","代码问题"),actor);

        var laterSystemService=new ReplayDatabaseIssueService(parser,dao,users,
                Clock.fixed(Instant.parse("2026-10-15T02:00:00Z"),ZoneId.of("Asia/Shanghai")));
        laterSystemService.importFile(file(true,"DB20261012-02",2,0,0),null,actor);
        var sameDay=dao.findByIssueId("koba_off_sheet_acct-ccbs");
        assertEquals("修复待验证",sameDay.get("issueStatus"));
        assertEquals("2026-10-15 10:00:00",sameDay.get("importedAt"));
        assertEquals("2026-10-12 10:00:00",sameDay.get("lastManualSaveAt"));

        laterSystemService.importFile(file(true,"DB20261013-01",1,0,0),null,actor);
        var nextDay=dao.findByIssueId("koba_off_sheet_acct-ccbs");
        assertEquals("重新打开",nextDay.get("issueStatus"));
        assertEquals("DB20261013-01",nextDay.get("batchNo"));
        assertEquals("2026-10-12 10:00:00",nextDay.get("lastManualSaveAt"));
    }
    @Test void disappearsAsFixedAndReappearsAsNewWithInheritedContent() {
        service.importFile(file(true,"DB20261012-01",3,0,0),null,actor);
        var row=dao.findByIssueId("koba_off_sheet_acct-ccbs");long id=((Number)row.get("id")).longValue();
        service.update(id,Map.of("version",row.get("version"),"issueStatus","打开","issueType","代码问题","remark","保留"),actor);
        service.importFile(file(true,"DB20261013-01",0,0,0),null,actor);
        var fixed=dao.findByIssueId("koba_off_sheet_acct-ccbs");
        assertEquals("已修复",fixed.get("issueStatus"));assertEquals("2026-10-13",fixed.get("defectRepairDate"));
        assertEquals(1L,dao.list(Map.of("defectRepairDates",List.of("2026-10-13"))).get("total"));
        assertThrows(IllegalArgumentException.class,()->service.update(id,Map.of("version",fixed.get("version"),"issueType","代码问题"),actor));
        service.importFile(file(true,"DB20261014-01",1,0,0),null,actor);
        var after=dao.findByIssueId("koba_off_sheet_acct-ccbs");
        assertEquals("新建",after.get("issueStatus"));assertEquals("保留",after.get("remark"));
        assertEquals("",after.get("defectRepairDate"));
        assertEquals(0L,dao.list(Map.of("defectRepairDates",List.of("2026-10-13"))).get("total"));
        assertEquals(1L,dao.list(Map.of("defectRepairDates",List.of(""))).get("total"));
    }
    @Test void rejectsStaleVersionAndEarlierBatchWithoutChangingCurrentOrHistory() {
        service.importFile(file(true,"DB20261012-01",3,0,0),null,actor);
        var row=dao.findByIssueId("koba_off_sheet_acct-ccbs");long id=((Number)row.get("id")).longValue();
        service.update(id,Map.of("version",row.get("version"),"issueType","代码问题"),actor);
        assertThrows(ReplayDatabaseIssueConflictException.class,()->service.update(id,Map.of("version",row.get("version"),"issueType","参数问题"),actor));
        assertThrows(IllegalArgumentException.class,()->service.importFile(file(true,"DB20261011-01",0,0,0),null,actor));
        assertEquals(3L,dao.findByIssueId("koba_off_sheet_acct-ccbs").get("differenceCount"));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_database_issue_history",Integer.class));
    }
    @Test void repeatBatchUpdatesOriginalSnapshotWithoutDuplicatingHistory() {
        service.importFile(file(true,"DB20261012-01",3,0,0),null,actor);
        service.importFile(file(true,"DB20261012-01",2,0,0),null,actor);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM dii_replay_database_issue_history",Integer.class));
        assertEquals(2L,dao.findByIssueId("koba_off_sheet_acct-ccbs").get("differenceCount"));
    }
    @Test void zeroDifferenceTableStillRejectsAnEarlierBatch() {
        service.importFile(file(true,"DB20261013-01",0,0,0),null,actor);
        assertThrows(IllegalArgumentException.class,()->service.importFile(file(true,"DB20261012-01",3,0,0),null,actor));
        assertNull(dao.findByIssueId("koba_off_sheet_acct-ccbs"));
    }
    @Test void batchAfterAutoRepairCannotBeRolledBackToAnEarlierOccurrence() {
        service.importFile(file(true,"DB20261012-01",3,0,0),null,actor);
        service.importFile(file(true,"DB20261013-01",0,0,0),null,actor);
        assertThrows(IllegalArgumentException.class,()->service.importFile(file(true,"DB20261012-02",3,0,0),null,actor));
        assertEquals("已修复",dao.findByIssueId("koba_off_sheet_acct-ccbs").get("issueStatus"));
    }
    @Test void importsOfOtherSuccessfulTablesDoNotRepairUncoveredTables() throws Exception {
        service.importFile(file(true,"DB20261012-01",3,0,0),null,actor);
        try(var workbook=new XSSFWorkbook(file(true,"DB20261013-01",0,0,0).getInputStream());var out=new ByteArrayOutputStream()) {
            workbook.getSheet("表明细").getRow(1).getCell(1).setCellValue("other_table");workbook.write(out);
            service.importFile(new MockMultipartFile("file","other.xlsx","",out.toByteArray()),null,actor);
        }
        assertEquals("新建",dao.findByIssueId("koba_off_sheet_acct-ccbs").get("issueStatus"));
    }
    @Test void noActionReviewRequiresPermissionAndPreservesApprovalOnLaterImports() {
        service.importFile(file(true,"DB20261012-01",3,0,0),null,actor);
        var row=dao.findByIssueId("koba_off_sheet_acct-ccbs");long id=((Number)row.get("id")).longValue();
        row=service.update(id,Map.of("version",row.get("version"),"issueStatus","无需处理","issueType","合理差异"),actor);
        var version=row.get("version");
        assertThrows(SecurityException.class,()->service.approve(id,Map.of("version",version,"reason","核实"),actor));
        registerTechnologyOwner();
        service.approve(id,Map.of("version",version,"reason","业务允许"),actor);
        service.importFile(file(true,"DB20261013-01",1,0,0),null,actor);
        row=dao.findByIssueId("koba_off_sheet_acct-ccbs");
        assertEquals("无需处理",row.get("issueStatus"));assertEquals("已审核",row.get("reviewStatus"));
        assertEquals("业务允许",row.get("reviewReason"));assertEquals("2026-10-12",row.get("defectRepairDate"));
        service.importFile(file(true,"DB20261014-01",0,0,0),null,actor);
        assertEquals("无需处理",dao.findByIssueId("koba_off_sheet_acct-ccbs").get("issueStatus"));
        row=dao.find(id);
        service.update(id,Map.of("version",row.get("version"),"issueStatus","修复待验证","issueType","代码问题"),actor);
        assertEquals(0L,dao.list(Map.of("defectRepairDates",List.of("2026-10-12"))).get("total"));
        service.importFile(file(true,"DB20261015-01",1,0,0),null,actor);
        assertEquals("重新打开",dao.find(id).get("issueStatus"));
        assertEquals(1L,dao.list(Map.of("defectRepairDates",List.of(""))).get("total"));
    }
    @Test void duplicateIdsOrIncompleteFieldsRejectTheWholeWorkbook() throws Exception {
        try(var workbook=new XSSFWorkbook(file(true,"DB20261012-01",3,0,2).getInputStream());var out=new ByteArrayOutputStream()) {
            workbook.getSheet("表字段明细").removeRow(workbook.getSheet("表字段明细").getRow(1));workbook.write(out);
            assertThrows(IllegalArgumentException.class,()->service.importFile(new MockMultipartFile("file","partial.xlsx","",out.toByteArray()),null,actor));
        }
        assertTrue(dao.current().isEmpty());
    }
    @Test void changingAndClearingCollaboratorKeepsDisplayProjectionInSyncWithSelectedUser(){
        jdbc.update("INSERT INTO ccbs_ai_sys_user(username,real_name,emp_no,status) VALUES ('other','协同人','E002',1)");
        service.importFile(file(true,"DB20261012-01",3,0,0),null,actor);
        var row=service.update(1,Map.of("version",0,"issueType","代码问题","cooperationPersonUsername","editor"),actor);
        assertEquals(1L,dao.list(Map.of("cooperationPersons",List.of("编辑人(editor)"))).get("total"));
        row=service.update(1,Map.of("version",row.get("version"),"issueType","代码问题","cooperationPersonUsername","other"),actor);
        assertEquals("other",dao.find(1).get("cooperationPersonUsername"));assertEquals("协同人",dao.find(1).get("cooperationPersonName"));
        assertEquals(0L,dao.list(Map.of("cooperationPersons",List.of("编辑人(editor)"))).get("total"));
        assertEquals(1L,dao.list(Map.of("cooperationPersons",List.of("协同人(other)"))).get("total"));
        service.update(1,Map.of("version",row.get("version"),"issueType","代码问题","cooperationPersonUsername",""),actor);
        assertEquals("",dao.find(1).get("cooperationPersonUsername"));assertEquals("",dao.find(1).get("cooperationPersonName"));
        assertEquals(0L,dao.list(Map.of("cooperationPersons",List.of("协同人(other)"))).get("total"));
        assertEquals(1L,dao.list(Map.of("cooperationPersons",List.of(""))).get("total"));
    }
    private void registerTechnologyOwner(){jdbc.update("INSERT INTO dii_replay_db_compare_registration(schema_name,table_name,domain_name,group_owner_emp_no,group_owner_name,registered_date,created_by,created_name,created_at,updated_by,updated_name,updated_at) VALUES ('public','koba_off_sheet_acct','贷款组','E001','编辑人','2026-10-12','editor','编辑人',CURRENT_TIMESTAMP,'editor','编辑人',CURRENT_TIMESTAMP)");com.axonlink.ai.replay.ReplayDatabaseTablePersonTestFixtures.sync(jdbc,"koba_off_sheet_acct");}
    static MockMultipartFile file(boolean owner,String batch,long ccbs,long other,long field) {
        try(var workbook=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
            var tables=workbook.createSheet("表明细");
            var tableHeaders=new ArrayList<>(List.of("领域","表"));if(owner)tableHeaders.add("负责人");
            tableHeaders.addAll(List.of("仅ccbs存在","仅528存在","主键相同、字段不同","批次号"));
            var header=tables.createRow(0);for(int i=0;i<tableHeaders.size();i++)header.createCell(i).setCellValue(tableHeaders.get(i));
            var row=tables.createRow(1);int col=0;row.createCell(col++).setCellValue("贷款组");row.createCell(col++).setCellValue("koba_off_sheet_acct");
            if(owner)row.createCell(col++).setCellValue("张三(c-zhangs3)");
            row.createCell(col++).setCellValue(ccbs);row.createCell(col++).setCellValue(other);row.createCell(col++).setCellValue(field);row.createCell(col).setCellValue(batch);
            var fields=workbook.createSheet("表字段明细");
            var fieldHeaders=new ArrayList<>(List.of("领域","表"));if(owner)fieldHeaders.add("负责人");fieldHeaders.addAll(List.of("字段名称","差异数量","批次号"));
            var fh=fields.createRow(0);for(int i=0;i<fieldHeaders.size();i++)fh.createCell(i).setCellValue(fieldHeaders.get(i));
            if(field!=0){var fr=fields.createRow(1);col=0;fr.createCell(col++).setCellValue("贷款组");fr.createCell(col++).setCellValue("koba_off_sheet_acct");if(owner)fr.createCell(col++).setCellValue("张三(c-zhangs3)");fr.createCell(col++).setCellValue("dtl_sn");fr.createCell(col++).setCellValue(field);fr.createCell(col).setCellValue(batch);}
            workbook.write(out);return new MockMultipartFile("file","每日核对结果.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",out.toByteArray());
        }catch(Exception e){throw new RuntimeException(e);}
    }
}
