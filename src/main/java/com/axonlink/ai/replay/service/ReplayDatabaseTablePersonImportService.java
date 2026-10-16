package com.axonlink.ai.replay.service;

import com.axonlink.ai.replay.dto.*;
import com.axonlink.ai.replay.persistence.ReplayDatabaseTablePersonDao;
import com.axonlink.ai.user.entity.SysUser;
import com.axonlink.ai.user.persistence.SysUserDao;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.*;

@Service
public class ReplayDatabaseTablePersonImportService {
    private static final Pattern NAME_WITH_USERNAME=Pattern.compile("^(.+?)\\(([^()]+)\\)$");
    private final ReplayDatabaseTablePersonExcelParser parser;
    private final ReplayDatabaseTablePersonDao dao;
    private final SysUserDao userDao;
    public ReplayDatabaseTablePersonImportService(ReplayDatabaseTablePersonExcelParser parser,ReplayDatabaseTablePersonDao dao,SysUserDao userDao) {
        this.parser=parser;this.dao=dao;this.userDao=userDao;
    }
    public ReplayTransactionPersonImportResult importFile(MultipartFile file)throws IOException {
        var rawRows=parser.parse(file);
        var rows=new ArrayList<ReplayDatabaseTablePersonRow>();
        var errors=new ArrayList<ReplayTransactionPersonImportError>();
        var names=new HashSet<String>();
        var now=LocalDateTime.now();
        for(var raw:rawRows) {
            String table=ReplayDatabaseTablePersonDao.normalize(raw.tableName());
            if(raw.domain().isBlank())error(errors,raw.rowNumber(),"领域",raw.domain(),"领域不能为空");
            if(table.isBlank())error(errors,raw.rowNumber(),"表英文名称",raw.tableName(),"表英文名称不能为空");
            else if(!names.add(table))error(errors,raw.rowNumber(),"表英文名称",raw.tableName(),"Excel 内表英文名称重复（不区分大小写）");
            Match developer=people(raw.developer(),raw.rowNumber(),"开发负责人",false,errors);
            Match technology=people(raw.technologyOwner(),raw.rowNumber(),"科技负责人",true,errors);
            rows.add(new ReplayDatabaseTablePersonRow(null,raw.domain(),table,raw.tableComment(),
                    developer.display(),developer.usernames(),developer.empNos(),technology.display(),technology.usernames(),technology.empNos(),now));
        }
        if(!errors.isEmpty())return new ReplayTransactionPersonImportResult(false,rawRows.size(),0,errors.size(),errors);
        dao.replaceAll(rows);
        return new ReplayTransactionPersonImportResult(true,rows.size(),rows.size(),0,List.of());
    }
    private Match people(String raw,int row,String column,boolean requireEmpNo,List<ReplayTransactionPersonImportError> errors) {
        if(raw==null||raw.isBlank())return new Match(null,null,null);
        var displays=new ArrayList<String>();var usernames=new ArrayList<String>();var empNos=new ArrayList<String>();
        for(String token:raw.split("[、,，;；\\n]+")) {
            token=token.trim();if(token.isBlank())continue;
            Matcher matcher=NAME_WITH_USERNAME.matcher(token);
            String name=matcher.matches()?matcher.group(1).trim():token;
            String username=matcher.matches()?matcher.group(2).trim():null;
            List<SysUser> matches=username==null?userDao.findActiveByExactRealName(name):userDao.findActiveByExactUsernameAndRealName(username,name);
            if(matches.size()!=1){error(errors,row,column,token,matches.isEmpty()?"人员未匹配到有效用户":"人员匹配到多个有效用户");continue;}
            SysUser user=matches.get(0);
            if(requireEmpNo&&(user.getEmpNo()==null||user.getEmpNo().isBlank())){error(errors,row,column,token,"匹配用户缺少 emp_no 工号");continue;}
            displays.add(user.getRealName()+"("+user.getUsername()+")");
            usernames.add(user.getUsername());
            if(user.getEmpNo()!=null&&!user.getEmpNo().isBlank())empNos.add(user.getEmpNo());
        }
        return new Match(join(displays),join(usernames),join(empNos));
    }
    private void error(List<ReplayTransactionPersonImportError> errors,int row,String column,String value,String reason){errors.add(new ReplayTransactionPersonImportError(row,column,value,reason));}
    private String join(List<String> values){return values.isEmpty()?null:String.join("、",values);}
    private record Match(String display,String usernames,String empNos){}
}
