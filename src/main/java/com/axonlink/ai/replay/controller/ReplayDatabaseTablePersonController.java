package com.axonlink.ai.replay.controller;

import com.axonlink.ai.daoindex.config.DaoIndexAnalysisProperties;
import com.axonlink.ai.replay.dto.ReplayTransactionPersonImportResult;
import com.axonlink.ai.replay.persistence.ReplayDatabaseTablePersonDao;
import com.axonlink.ai.replay.service.ReplayDatabaseTablePersonImportService;
import com.axonlink.common.R;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/api/ai/parallel-replay/database-table-persons")
public class ReplayDatabaseTablePersonController {
    private final ReplayDatabaseTablePersonDao dao;
    private final ReplayDatabaseTablePersonImportService importer;
    private final DaoIndexAnalysisProperties properties;
    public ReplayDatabaseTablePersonController(ReplayDatabaseTablePersonDao dao,ReplayDatabaseTablePersonImportService importer,DaoIndexAnalysisProperties properties){this.dao=dao;this.importer=importer;this.properties=properties;}

    @GetMapping
    public R<Map<String,Object>> list(@RequestParam(defaultValue="50") int limit,@RequestParam(defaultValue="0") int offset,@RequestParam(required=false) String keyword){
        return R.ok(Map.of("total",dao.count(keyword),"items",dao.list(keyword,limit,offset)));
    }
    @GetMapping("/by-table")
    public R<?> byTable(@RequestParam String tableName){return R.ok(dao.findByTableName(tableName));}
    @PostMapping(value="/import",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<R<ReplayTransactionPersonImportResult>> importFile(@RequestPart("file") MultipartFile file,@RequestHeader(value="X-DII-Trigger-Token",required=false) String token){
        String expected=properties.getBatchTrigger().getToken();
        if(expected!=null&&!expected.isBlank()&&!expected.equals(token))return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(R.fail("口令错误"));
        if(file==null||file.isEmpty())return ResponseEntity.badRequest().body(R.fail("文件为空"));
        try{
            var result=importer.importFile(file);
            if(!result.imported())return ResponseEntity.badRequest().body(R.fail(400,"导入校验失败",result));
            return ResponseEntity.ok(R.ok(result));
        }catch(IllegalArgumentException e){return ResponseEntity.badRequest().body(R.fail(e.getMessage()));}
         catch(Exception e){return ResponseEntity.internalServerError().body(R.fail("数据库表人员清单导入失败"));}
    }
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(required=false) String keyword)throws Exception{
        try(Workbook book=new XSSFWorkbook();ByteArrayOutputStream out=new ByteArrayOutputStream()){
            Sheet sheet=book.createSheet("数据库表人员清单");
            String[] headers={"领域","表英文名称","表中文名称","开发负责人","科技负责人"};
            Row header=sheet.createRow(0);for(int i=0;i<headers.length;i++)header.createCell(i).setCellValue(headers[i]);
            int index=1;
            for(var item:dao.listAll(keyword)){
                Row row=sheet.createRow(index++);
                row.createCell(0).setCellValue(value(item.domain()));row.createCell(1).setCellValue(value(item.tableName()));
                row.createCell(2).setCellValue(value(item.tableComment()));row.createCell(3).setCellValue(value(item.developer()));
                row.createCell(4).setCellValue(value(item.technologyOwner()));
            }
            for(int i=0;i<headers.length;i++)sheet.autoSizeColumn(i);
            book.write(out);
            return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename*=UTF-8''"+URLEncoder.encode("数据库表人员清单.xlsx",StandardCharsets.UTF_8)).body(out.toByteArray());
        }
    }
    private String value(String text){return text==null?"":text;}
}
