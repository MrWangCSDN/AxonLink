package com.axonlink.ai.replay.dbissue;

import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.common.R;
import com.axonlink.security.UserPrincipalResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.util.MultiValueMap;
import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.axonlink.ai.replay.dbissue.ReplayDatabaseIssueDao.text;

@RestController
@RequestMapping("/api/ai/parallel-replay/database-issues")
public class ReplayDatabaseIssueController {
    private static final Logger log=LoggerFactory.getLogger(ReplayDatabaseIssueController.class);
    private final ReplayDatabaseIssueDao dao;private final ReplayDatabaseIssueService service;private final UserPrincipalResolver resolver;
    public ReplayDatabaseIssueController(ReplayDatabaseIssueDao dao,ReplayDatabaseIssueService service,UserPrincipalResolver resolver){this.dao=dao;this.service=service;this.resolver=resolver;}
    @GetMapping public R<Map<String,Object>> list(@RequestParam MultiValueMap<String,String> criteria,HttpServletRequest request){return R.ok(service.list(criteria,optionalActor(request)));}
    @PostMapping public R<Map<String,Object>> listJson(@RequestBody Map<String,Object> criteria,HttpServletRequest request){return R.ok(service.list(criteria,optionalActor(request)));}
    @GetMapping("/filter-options") public R<Map<String,Object>> filterOptions(@RequestParam String field,@RequestParam MultiValueMap<String,String> criteria){return R.ok(dao.filterOptions(field,criteria));}
    @PostMapping("/filter-options") public R<Map<String,Object>> filterOptionsJson(@RequestBody Map<String,Object> criteria){return R.ok(dao.filterOptions(text(criteria.get("field")),criteria));}
    @GetMapping("/options") public R<Map<String,Object>> options(HttpServletRequest request){return R.ok(service.options(optionalActor(request)));}
    @PostMapping(value="/import",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<Map<String,Object>> importFile(@RequestPart(value="file",required=false)MultipartFile file,@RequestParam(required=false)String batchNo,@RequestParam(required=false)String releaseAt,HttpServletRequest request){return R.ok(service.importFile(file,batchNo,actor(request),releaseAt));}
    @PatchMapping("/{id}") public R<Map<String,Object>> update(@PathVariable long id,@RequestBody Map<String,Object> body,HttpServletRequest request){return R.ok(service.update(id,body,actor(request)));}
    @PostMapping("/{id}/review") public R<Map<String,Object>> review(@PathVariable long id,@RequestBody Map<String,Object> body,HttpServletRequest request){return R.ok(service.approve(id,body,actor(request)));}
    @GetMapping("/{id}/tracking") public R<List<Map<String,Object>>> tracking(@PathVariable long id){return R.ok(service.tracking(id));}
    @GetMapping("/export") public ResponseEntity<byte[]> export(@RequestParam MultiValueMap<String,String> criteria){return exportCriteria(criteria);}
    @PostMapping("/export") public ResponseEntity<byte[]> exportJson(@RequestBody Map<String,Object> criteria){return exportCriteria(criteria);}
    private ResponseEntity<byte[]> exportCriteria(Map<String,?> criteria){
        byte[] body=dao.writing(()->{
            try(var workbook=new XSSFWorkbook();var out=new ByteArrayOutputStream()){
                var sheet=workbook.createSheet("数据库问题清单");
                String[][] columns={{"tableName","表英文名/中文名"},{"domain","领域"},{"owner","开发负责人"},{"technologyOwnerDisplay","科技负责人"},{"issueLevel","问题级别"},{"fieldName","字段英文名"},{"differenceCount","差异笔数"},{"defectRepairDate","缺陷修复日期"},{"issueId","issue_id"},{"issueStatus","问题状态"},{"reviewStatusDisplay","审核状态"},{"issueType","问题类型"},{"cooperationPerson","需协同人"},{"initialAnalysis","初步问题分析"},{"finalSolution","最终处理方案"},{"remark","备注"},{"occurrenceBatches","出现批次"}};
                var header=sheet.createRow(0);for(int i=0;i<columns.length;i++){header.createCell(i).setCellValue(columns[i][1]);sheet.setColumnWidth(i,(i==0||i==8?35:24)*256);}
                var wrap=workbook.createCellStyle();wrap.setWrapText(true);
                var query=new LinkedHashMap<String,Object>(criteria);query.put("limit",200);int offset=0,rowIndex=1;
                while(true){query.put("offset",offset);var page=service.list(query,null);
                    @SuppressWarnings("unchecked")var items=(List<Map<String,Object>>)page.get("items");
                    for(var item:items){var row=sheet.createRow(rowIndex++);for(int i=0;i<columns.length;i++){
                        var cell=row.createCell(i);String key=columns[i][0];Object value=item.get(key);
                        if(key.equals("tableName")){String comment=text(item.get("tableComment"));cell.setCellValue(text(value)+(comment.isEmpty()?"":"\n"+comment));cell.setCellStyle(wrap);if(!comment.isEmpty())row.setHeightInPoints(sheet.getDefaultRowHeightInPoints()*2);}
                        else if(value instanceof Collection<?> values){cell.setCellValue(String.join("、",values.stream().map(ReplayDatabaseIssueDao::text).toList()));cell.setCellStyle(wrap);}
                        else if(value instanceof Number number)cell.setCellValue(number.doubleValue());else cell.setCellValue(text(value));
                    }}
                    offset+=items.size();if(offset>=((Number)page.get("total")).longValue()||items.isEmpty())break;
                }
                sheet.createFreezePane(0,1);sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0,rowIndex-1,0,columns.length-1));workbook.write(out);return out.toByteArray();
            }catch(IllegalArgumentException e){throw e;}catch(Exception e){throw new IllegalStateException("导出数据库问题清单失败",e);}
        });
        String filename=URLEncoder.encode("回放数据库问题清单.xlsx",StandardCharsets.UTF_8).replace("+","%20");
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename*=UTF-8''"+filename).body(body);
    }
    private ReplayIssueOperator actor(HttpServletRequest request){var actor=optionalActor(request);if(actor==null)throw new AuthenticationCredentialsNotFoundException("请先登录");return actor;}
    private ReplayIssueOperator optionalActor(HttpServletRequest request){var resolved=resolver.resolve(request);if(resolved==null||text(resolved.principal).isBlank())return null;String username=resolved.user==null?resolved.principal:text(resolved.user.getUsername());if(username.isBlank())username=resolved.principal;String name=resolved.user==null?username:text(resolved.user.getRealName());return new ReplayIssueOperator(username,name.isBlank()?username:name);}
    @ExceptionHandler(AuthenticationCredentialsNotFoundException.class)public ResponseEntity<R<Object>> unauth(Exception e){return error(HttpStatus.UNAUTHORIZED,e.getMessage());}
    @ExceptionHandler(SecurityException.class)public ResponseEntity<R<Object>> forbidden(Exception e){return error(HttpStatus.FORBIDDEN,e.getMessage());}
    @ExceptionHandler(ReplayDatabaseIssueConflictException.class)public ResponseEntity<R<Object>> conflict(Exception e){return error(HttpStatus.CONFLICT,e.getMessage());}
    @ExceptionHandler({IllegalArgumentException.class,MethodArgumentTypeMismatchException.class,HttpMessageNotReadableException.class})public ResponseEntity<R<Object>> invalid(Exception e){return error(HttpStatus.BAD_REQUEST,e instanceof IllegalArgumentException?e.getMessage():"请求参数格式不正确");}
    @ExceptionHandler(Exception.class)public ResponseEntity<R<Object>> failure(Exception e){log.error("[replay-database-issues] operation failed",e);return error(HttpStatus.INTERNAL_SERVER_ERROR,"操作失败，请联系管理员检查日志");}
    private ResponseEntity<R<Object>> error(HttpStatus status,String message){return ResponseEntity.status(status).body(R.fail(status.value(),message,null));}
}
