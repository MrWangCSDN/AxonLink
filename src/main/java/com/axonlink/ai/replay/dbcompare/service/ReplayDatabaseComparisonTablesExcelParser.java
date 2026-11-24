package com.axonlink.ai.replay.dbcompare.service;

import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareScriptConfiguration.TableRequest;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareScriptConfiguration.ValidationError;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Standalone table-list import; never changes the registration or historical version tables. */
@Component
public class ReplayDatabaseComparisonTablesExcelParser {
    static final int MAX_TABLES = 5000;
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_$#]*");
    private static final Map<String, Set<String>> HEADERS = Map.of(
            "tableName", Set.of("表名", "表英文名", "英文表名", "tablename"),
            "tableComment", Set.of("表中文名", "中文表名", "表名称", "tablecomment", "tablechinesename", "tablecnname"),
            "domainName", Set.of("分组", "领域", "所属分组", "业务组", "group", "groupname", "domain", "domainname"),
            "ownerName", Set.of("负责人", "开发负责人", "修订人", "owner", "ownername", "responsibleperson"),
            "maxPartitionNum", Set.of("最大hash分区数", "最大哈希分区数", "maxpartitionnum", "maxhashpartitionnum",
                    "maxhashpartitions", "bcompmaxpartitionnum"));

    public List<ParsedTable> parse(InputStream input) {
        if (input == null) throw new IllegalArgumentException("Excel 文件不能为空");
        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        try (Workbook workbook = WorkbookFactory.create(input)) {
            Header header = findHeader(workbook, formatter);
            if (header == null) {
                throw invalid(List.of(new ValidationError(null,null,null,"未找到包含表名、分组的有效表头")));
            }
            List<ValidationError> errors = new ArrayList<>();
            List<ParsedTable> tables = new ArrayList<>();
            Map<String,Integer> seen = new LinkedHashMap<>();
            int nonemptyRows = 0;
            for (Row row : header.sheet()) {
                if (row.getRowNum() <= header.row()) continue;
                String tableName = value(row, header, "tableName", formatter).trim().toLowerCase(Locale.ROOT);
                String rawComment = value(row, header, "tableComment", formatter);
                String comment = rawComment.trim();
                String domain = value(row, header, "domainName", formatter).trim();
                String rawOwner = value(row, header, "ownerName", formatter);
                String owner = rawOwner.trim();
                String maximum = maximumValue(row,header,formatter).trim();
                if (tableName.isEmpty() && comment.isEmpty() && domain.isEmpty() && owner.isEmpty() && maximum.isEmpty()
                        && rawComment.indexOf('\0')<0 && rawOwner.indexOf('\0')<0) continue;
                int excelRow = row.getRowNum()+1;
                if (++nonemptyRows > MAX_TABLES) {
                    errors.add(new ValidationError(excelRow,tableName,null,"表清单最多允许5000张表"));
                    break;
                }
                int before = errors.size();
                if (!IDENTIFIER.matcher(tableName).matches()) {
                    errors.add(new ValidationError(excelRow,tableName,"表名","表英文名不能为空且必须是合法数据库标识符"));
                } else {
                    Integer first = seen.putIfAbsent(tableName,excelRow);
                    if (first != null) errors.add(new ValidationError(excelRow,tableName,"表名","表名重复，首次出现于第"+first+"行"));
                }
                String domainError = ReplayDatabaseComparisonConfigScriptGenerator.domainMappingError(domain);
                if (domainError != null) errors.add(new ValidationError(excelRow,tableName,"分组",domainError));
                if (owner.length() > 100) errors.add(new ValidationError(excelRow,tableName,"负责人","负责人不能超过100个字符"));
                if (comment.length() > 1000) errors.add(new ValidationError(excelRow,tableName,"表中文名","表中文名不能超过1000个字符"));
                if (rawOwner.indexOf('\0')>=0) errors.add(new ValidationError(excelRow,tableName,"负责人","负责人不能包含NUL字符"));
                if (rawComment.indexOf('\0')>=0) errors.add(new ValidationError(excelRow,tableName,"表中文名","表中文名不能包含NUL字符"));
                int max = 64;
                if (!maximum.isEmpty()) {
                    try {
                        max = new BigDecimal(maximum).intValueExact();
                        if (max < 1 || max > 256) throw new ArithmeticException();
                    } catch (NumberFormatException | ArithmeticException exception) {
                        errors.add(new ValidationError(excelRow,tableName,"最大hash分区数","最大hash分区数必须为1到256的整数"));
                    }
                }
                if (errors.size() == before) {
                    tables.add(new ParsedTable(excelRow,new TableRequest(tableName,comment,domain,owner,
                            BigDecimal.valueOf(max),List.of())));
                }
            }
            if (nonemptyRows == 0) errors.add(new ValidationError(null,null,null,"Excel表清单不能为空"));
            if (!errors.isEmpty()) throw invalid(errors);
            return List.copyOf(tables);
        } catch (ReplayDatabaseComparisonGenerationException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new IllegalArgumentException("无法读取Excel表清单，请使用有效的xls或xlsx文件",exception);
        }
    }

    private Header findHeader(Workbook workbook, DataFormatter formatter) {
        List<Sheet> preferred = new ArrayList<>();
        List<Sheet> others = new ArrayList<>();
        for (Sheet sheet : workbook) {
            ("tables".equalsIgnoreCase(sheet.getSheetName().trim()) ? preferred : others).add(sheet);
        }
        preferred.addAll(others);
        for (Sheet sheet : preferred) {
            for (int index=0; index<20; index++) {
                Row row = sheet.getRow(index);
                if (row == null) continue;
                Map<String,Integer> columns = new LinkedHashMap<>();
                boolean duplicate = false;
                for (var cell : row) {
                    String value = normalizeHeader(formatter.formatCellValue(cell));
                    for (var entry : HEADERS.entrySet()) {
                        if (entry.getValue().contains(value)) {
                            duplicate |= columns.putIfAbsent(entry.getKey(),cell.getColumnIndex()) != null;
                        }
                    }
                }
                if (columns.containsKey("tableName") && columns.containsKey("domainName")) {
                    if (duplicate) throw invalid(List.of(new ValidationError(index+1,null,null,"表头包含重复的配置列")));
                    return new Header(sheet,index,Map.copyOf(columns));
                }
            }
        }
        return null;
    }

    private String normalizeHeader(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s_-]","");
    }

    private String value(Row row, Header header, String field, DataFormatter formatter) {
        Integer column = header.columns().get(field);
        return column == null ? "" : formatter.formatCellValue(row.getCell(column));
    }

    private String maximumValue(Row row,Header header,DataFormatter formatter) {
        Integer column=header.columns().get("maxPartitionNum");
        var cell=column==null?null:row.getCell(column);
        // Excel display formats may round 64.5 to 65; validate the stored numeric value instead.
        return cell!=null && cell.getCellType()==CellType.NUMERIC
                ? Double.toString(cell.getNumericCellValue()) : value(row,header,"maxPartitionNum",formatter);
    }

    private ReplayDatabaseComparisonGenerationException invalid(List<ValidationError> errors) {
        return new ReplayDatabaseComparisonGenerationException(HttpStatus.UNPROCESSABLE_ENTITY,
                "SCRIPT_CONFIG_VALIDATION_FAILED","表清单校验失败",Map.of("errors",List.copyOf(errors)));
    }

    public record ParsedTable(int row, TableRequest table) {}
    private record Header(Sheet sheet, int row, Map<String,Integer> columns) {}
}
