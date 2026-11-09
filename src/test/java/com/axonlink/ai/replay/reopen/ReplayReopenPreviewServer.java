package com.axonlink.ai.replay.reopen;

import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.replay.persistence.ReplayIssueDao;
import com.axonlink.ai.replay.persistence.ReplayTransactionPersonDao;
import com.axonlink.ai.user.persistence.SysUserDao;
import com.axonlink.security.UserPrincipalResolver;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;

/** Local-only, test-classpath preview. Never included in the production artifact. */
public class ReplayReopenPreviewServer {
    public static void main(String[] args) throws Exception {
        Tomcat tomcat=new Tomcat();
        tomcat.setBaseDir(Files.createTempDirectory("reopen-preview-tomcat").toString());
        tomcat.setHostname("127.0.0.1");tomcat.setPort(18768);tomcat.getConnector().setProperty("address","127.0.0.1");
        Context context=tomcat.addContext("",Files.createTempDirectory("reopen-preview-docroot").toString());
        AnnotationConfigWebApplicationContext spring=new AnnotationConfigWebApplicationContext();
        spring.register(PreviewConfig.class);spring.setServletContext(context.getServletContext());spring.refresh();
        Tomcat.addServlet(context,"dispatcher",new DispatcherServlet(spring));context.addServletMappingDecoded("/","dispatcher");
        org.apache.tomcat.util.descriptor.web.FilterDef filter=new org.apache.tomcat.util.descriptor.web.FilterDef();
        filter.setFilterName("local-test-identity");filter.setFilter(new LocalIdentityFilter());context.addFilterDef(filter);
        org.apache.tomcat.util.descriptor.web.FilterMap mapping=new org.apache.tomcat.util.descriptor.web.FilterMap();
        mapping.setFilterName("local-test-identity");mapping.addURLPattern("/*");context.addFilterMap(mapping);
        tomcat.start();System.out.println("REOPEN_PREVIEW_READY http://127.0.0.1:18768");tomcat.getServer().await();
    }

    @Configuration @EnableWebMvc
    static class PreviewConfig implements WebMvcConfigurer {
        @Override public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
            converters.stream().filter(MappingJackson2HttpMessageConverter.class::isInstance)
                .map(MappingJackson2HttpMessageConverter.class::cast)
                .forEach(converter -> converter.getObjectMapper().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
        }
        @Bean JdbcTemplate jdbc(){
            JdbcTemplate jdbc=ReplayReopenTestSupport.database();
            ReplayReopenTestSupport.event(jdbc,1,"ISS-A","RPT20261021-01","deva","开发甲","公共组");
            ReplayReopenTestSupport.event(jdbc,2,"ISS-A","RPT20261022-01","deva","开发甲","公共组");
            ReplayReopenTestSupport.event(jdbc,3,"ISS-B","RPT20261022-01","devb","开发乙","公共组");
            ReplayReopenTestSupport.event(jdbc,4,"ISS-C","RPT20261021-01",null,null,"公共组");
            jdbc.update("UPDATE dii_replay_reopen_event SET issue_description=CASE id WHEN 1 THEN '开户回放：客户号字段不一致' WHEN 2 THEN '开户回放：同一问题再次重开' WHEN 3 THEN '共用组件金额精度不一致' ELSE '多人映射，待领导确认责任' END");
            jdbc.update("UPDATE dii_replay_reopen_event SET issue_key_hash=? WHERE id=4","c".repeat(64));
            jdbc.update("UPDATE dii_replay_reopen_event SET occurrence_no=1 WHERE id>2");
            return jdbc;
        }
        @Bean SysUserDao users(JdbcTemplate jdbc){return new SysUserDao(jdbc);}
        @Bean ReplayReopenProperties properties(){ReplayReopenProperties p=new ReplayReopenProperties();p.setEnabled(true);p.setAdminEmpNos(List.of("900"));return p;}
        @Bean ReplayReopenDao events(JdbcTemplate jdbc){return new ReplayReopenDao(jdbc);}
        @Bean ReplayReopenWorkflowDao workflow(ReplayReopenDao events){return new ReplayReopenWorkflowDao(events);}
        @Bean ReplayTransactionPersonDao persons(JdbcTemplate jdbc){return new ReplayTransactionPersonDao(jdbc);}
        @Bean ReplayReopenPermissionService permission(ReplayReopenProperties p,SysUserDao u,ReplayReopenWorkflowDao s,ReplayTransactionPersonDao t){return new ReplayReopenPermissionService(p,u,s,t);}
        @Bean ReplayReopenRecorder recorder(JdbcTemplate jdbc,ReplayTransactionPersonDao t,SysUserDao u,ReplayReopenDao d,ReplayReopenProperties p){return new ReplayReopenRecorder(new ReplayIssueDao(jdbc),t,u,d,p);}
        @Bean ReplayReopenService service(ReplayReopenDao d,ReplayReopenWorkflowDao s,ReplayReopenPermissionService a,ReplayReopenRecorder r,ReplayReopenProperties p){
            ReplayReopenService service=new ReplayReopenService(d,s,a,r,p);
            service.savePolicy("公共组",new ReplayReopenWorkflowDtos.PolicyRequest(0L,List.of("leader","backup"),"leader","本地联调种子策略"),new ReplayIssueOperator("admin","权限管理员"));return service;
        }
        @Bean UserPrincipalResolver resolver(){return new UserPrincipalResolver();}
        @Bean ReplayReopenController controller(ReplayReopenService s,UserPrincipalResolver r){return new ReplayReopenController(s,r);}
    }
    static final class LocalIdentityFilter implements Filter {
        public void doFilter(ServletRequest request,ServletResponse response,FilterChain chain) throws IOException,ServletException {
            HttpServletRequest http=(HttpServletRequest)request;String user="deva";
            if(http.getCookies()!=null)for(Cookie cookie:http.getCookies())if(cookie.getName().equals("reopenPreviewUser"))user=cookie.getValue();
            if(Set.of("deva","devb","devc","leader","backup","admin","inactive").contains(user))
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,"test-only",List.of()));
            else SecurityContextHolder.clearContext();
            try{chain.doFilter(request,response);}finally{SecurityContextHolder.clearContext();}
        }
    }
}
