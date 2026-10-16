package com.axonlink.ai.replay.dbissue;

import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

/** Parses BD's successful table and exact-field projections before any database write. */
@Component
public class ReplayDatabaseIssueExcelParser {
    private static final Pattern BATCH=Pattern.compile("^DB(\\d{8})-[A-Za-z0-9_-]+$");
    private static final int MAX_ROWS=200000;
    public record IssueInput(String issueId,String tableName,String domain,String owner,String issueLevel,
                             String fieldName,long differenceCount,String tableComment) {}
    public record ParsedWorkbook(List<IssueInput> issues,Set<String> tables,String batchNo,LocalDate batchDate) {}
    private record TableRow(String comment,long different) {}

    public ParsedWorkbook parse(MultipartFile file,String suppliedBatch) {
        if(file==null||file.isEmpty())throw new IllegalArgumentException("请选择非空Excel文件");
        String name=Objects.toString(file.getOriginalFilename(),"").toLowerCase(Locale.ROOT);
        if(!name.endsWith(".xlsx")&&!name.endsWith(".xls"))throw new IllegalArgumentException("仅支持.xlsx或.xls文件");
        if(file.getSize()>50*1024*1024)throw new IllegalArgumentException("Excel文件不能超过50MB");
        var issues=new LinkedHashMap<String,IssueInput>();
        var tables=new LinkedHashMap<String,TableRow>();var batches=new LinkedHashSet<String>();
        try(var workbook=WorkbookFactory.create(file.getInputStream())) {
            var formatter=new DataFormatter(Locale.ROOT);
            var tableSheet=workbook.getSheet("表明细");var fieldSheet=workbook.getSheet("表字段明细");
            if(tableSheet==null||fieldSheet==null)throw new IllegalArgumentException("Excel必须包含“表明细”和“表字段明细”两个Sheet");
            if(tableSheet.getLastRowNum()+fieldSheet.getLastRowNum()>MAX_ROWS)throw new IllegalArgumentException("工作簿最多支持200000行明细");
            scan(tableSheet,formatter,false,(row,headers)->{
                String table=identifier(text(row,headers,"表",formatter),row,"表英文名");
                String comment=optional(text(row,headers,"表中文名称",formatter),1000,row,"表中文名称");
                long ccbs=count(row,headers,"仅ccbs存在",formatter),other=count(row,headers,"仅528存在",formatter);
                long different=count(row,headers,"主键相同、字段不同",formatter);
                if(tables.putIfAbsent(table,new TableRow(comment,different))!=null)
                    throw invalid(row,"同名表重复，当前issue_id规则无法区分多配置或多连接对："+table);
                addBatch(row,headers,formatter,batches);
                if(ccbs>0)add(issues,new IssueInput(table+"-ccbs",table,"","","仅ccbs","",ccbs,comment),row);
                if(other>0)add(issues,new IssueInput(table+"-528",table,"","","仅528","",other,comment),row);
            });
            if(tables.isEmpty())throw new IllegalArgumentException("表明细没有成功核对的表，未更改任何问题状态");
            var fieldTotals=new HashMap<String,BigDecimal>();
            scan(fieldSheet,formatter,true,(row,headers)->{
                String table=identifier(text(row,headers,"表",formatter),row,"表英文名");
                String field=identifier(text(row,headers,"字段名称",formatter),row,"字段英文名");
                long different=count(row,headers,"差异数量",formatter);
                TableRow source=tables.get(table);
                if(source==null)throw invalid(row,"字段明细没有对应的表明细："+table);
                if(different>source.different)throw invalid(row,"字段差异数量超过表的主键相同、字段不同数量："+table);
                addBatch(row,headers,formatter,batches);
                if(different>0){fieldTotals.merge(table,BigDecimal.valueOf(different),BigDecimal::add);add(issues,new IssueInput(table+"-"+field,table,"","","字段级",field,different,source.comment),row);}
            });
            // Every differing row contributes to at least one field count. A smaller sum proves truncation.
            for(var entry:tables.entrySet())if(fieldTotals.getOrDefault(entry.getKey(),BigDecimal.ZERO).compareTo(BigDecimal.valueOf(entry.getValue().different))<0)
                throw new IllegalArgumentException("表存在字段差异但缺少完整字段明细："+entry.getKey());
        }catch(IllegalArgumentException e){throw e;}
        catch(Exception e){throw new IllegalArgumentException("无法读取Excel，请使用BD导出的完整工作簿",e);}
        String supplied=suppliedBatch==null?"":suppliedBatch.trim();
        if(!supplied.isEmpty())batches.add(supplied);
        if(batches.size()!=1)throw new IllegalArgumentException(batches.isEmpty()?"旧报表缺少批次号，请补填，如DB20261012-01":"工作簿与输入批次号不一致，或包含多个批次号");
        String batch=batches.iterator().next();LocalDate date=batchDate(batch);
        return new ParsedWorkbook(List.copyOf(issues.values()),Set.copyOf(tables.keySet()),batch,date);
    }
    public static LocalDate batchDate(String batch) {
        var match=BATCH.matcher(batch==null?"":batch);
        if(!match.matches()||batch.length()>128)throw new IllegalArgumentException("批次号格式应为DByyyyMMdd-序号，如DB20261012-01");
        try{return LocalDate.parse(match.group(1),DateTimeFormatter.BASIC_ISO_DATE);}
        catch(Exception e){throw new IllegalArgumentException("批次号中的日期不合法："+batch);}
    }
    private interface RowConsumer { void accept(Row row,Map<String,Integer> headers); }
    private static void scan(Sheet sheet,DataFormatter f,boolean fields,RowConsumer consumer) {
        Map<String,Integer> headers=null;boolean found=false;
        List<String> required=fields?List.of("表","字段名称","差异数量"):List.of("表","仅ccbs存在","仅528存在","主键相同、字段不同");
        for(Row row:sheet){
            if(headers!=null&&!f.formatCellValue(row.getCell(headers.get("表"))).trim().equals("表")){
                var located=headers;
                boolean empty=required.stream().allMatch(key->f.formatCellValue(row.getCell(located.get(key))).trim().isEmpty());
                if(empty||sheet.getMergedRegions().stream().anyMatch(region->region.getFirstRow()==row.getRowNum()))continue;
                // A data row is identified by its located table cell, never by ignored metadata values.
                consumer.accept(row,headers);continue;
            }
            var candidate=new LinkedHashMap<String,Integer>();
            for(Cell cell:row){String value=f.formatCellValue(cell).trim().replace(" ","").toLowerCase(Locale.ROOT);if(!value.isEmpty())candidate.put(value,cell.getColumnIndex());}
            // Structural columns identify report headers; report domain/person columns are ignored.
            if(required.stream().filter(candidate::containsKey).count()>=2){
                for(String key:required)if(!candidate.containsKey(key))throw invalid(row,"缺少列："+key+"，请确认数据库名称为528和ccbs");
                headers=candidate;found=true;continue;
            }
            if(candidate.isEmpty())continue;
            if(headers==null)continue; // connection-direction title before a repeated header
            if(row.getSheet().getMergedRegions().stream().anyMatch(region->region.getFirstRow()==row.getRowNum()))continue;
            consumer.accept(row,headers);
        }
        if(!found)throw new IllegalArgumentException("Sheet“"+sheet.getSheetName()+"”缺少有效表头");
    }
    private static String text(Row row,Map<String,Integer> h,String name,DataFormatter f){Integer col=h.get(name.toLowerCase(Locale.ROOT));return col==null?"":f.formatCellValue(row.getCell(col)).trim();}
    private static long count(Row row,Map<String,Integer> h,String name,DataFormatter f){
        Cell cell=row.getCell(h.get(name.toLowerCase(Locale.ROOT)));
        String value=cell!=null&&cell.getCellType()==CellType.NUMERIC?BigDecimal.valueOf(cell.getNumericCellValue()).toPlainString():text(row,h,name,f).replace(",","").replace("，","");
        try{long number=new BigDecimal(value).longValueExact();if(number<0||number>9007199254740992L)throw new ArithmeticException();return number;}
        catch(Exception e){throw invalid(row,name+"必须为非负整数且不超过Excel精确整数范围");}
    }
    private static String identifier(String value,Row row,String label){String result=required(value,256,row,label).toLowerCase(Locale.ROOT);if(!result.matches("[a-z_][a-z0-9_$]*"))throw invalid(row,label+"不是合法英文标识符："+value);return result;}
    private static String required(String value,int max,Row row,String label){if(value.isBlank())throw invalid(row,label+"不能为空");return optional(value,max,row,label);}
    private static String optional(String value,int max,Row row,String label){if(value.length()>max)throw invalid(row,label+"长度超过"+max);return value.equals("—")||value.equals("-")?"":value;}
    private static void addBatch(Row row,Map<String,Integer> h,DataFormatter f,Set<String> batches){String batch=text(row,h,"批次号",f);if(!batch.isBlank())batches.add(batch);}
    private static void add(Map<String,IssueInput> rows,IssueInput issue,Row row){if(rows.putIfAbsent(issue.issueId,issue)!=null)throw invalid(row,"存在重复issue_id："+issue.issueId);}
    private static IllegalArgumentException invalid(Row row,String message){return new IllegalArgumentException("Sheet“"+row.getSheet().getSheetName()+"”第"+(row.getRowNum()+1)+"行："+message);}
}
