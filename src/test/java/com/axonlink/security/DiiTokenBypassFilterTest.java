package com.axonlink.security;

import com.axonlink.ai.daoindex.config.DaoIndexAnalysisProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class DiiTokenBypassFilterTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void matchingTokenDoesNotReplaceAnExistingHumanLogin() throws Exception {
        DaoIndexAnalysisProperties properties = new DaoIndexAnalysisProperties();
        properties.getBatchTrigger().setToken("secret");
        DiiTokenBypassFilter filter = new DiiTokenBypassFilter(properties);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("sunhy1", "N/A", List.of()));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(DiiTokenBypassFilter.HEADER, "secret");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertEquals("sunhy1", SecurityContextHolder.getContext().getAuthentication().getName());
    }
    @Test
    void operationTokenDoesNotPolluteTheSessionContextOrTheFollowingRequest() throws Exception {
        DaoIndexAnalysisProperties properties = new DaoIndexAnalysisProperties();
        properties.getBatchTrigger().setToken("secret");
        DiiTokenBypassFilter filter = new DiiTokenBypassFilter(properties);
        var sessionContext = SecurityContextHolder.createEmptyContext();
        var request = new MockHttpServletRequest();
        request.getSession().setAttribute("SPRING_SECURITY_CONTEXT", sessionContext);
        SecurityContextHolder.setContext(sessionContext);
        request.addHeader(DiiTokenBypassFilter.HEADER, "secret");

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            assertEquals("dii-token", SecurityContextHolder.getContext().getAuthentication().getName());
            assertNull(sessionContext.getAuthentication(), "临时口令身份不能写入会话共享对象");
        });

        assertSame(sessionContext, SecurityContextHolder.getContext());
        assertNull(sessionContext.getAuthentication());
    }

    @Test
    void passwordLoginIdentitySurvivesATokenRequestAndTheNextRequest() throws Exception {
        DaoIndexAnalysisProperties properties = new DaoIndexAnalysisProperties();
        properties.getBatchTrigger().setToken("secret");
        DiiTokenBypassFilter filter = new DiiTokenBypassFilter(properties);
        var sessionContext = SecurityContextHolder.createEmptyContext();
        sessionContext.setAuthentication(new UsernamePasswordAuthenticationToken("c-wangsh8", "N/A", List.of()));
        var request = new MockHttpServletRequest();
        request.getSession().setAttribute("SPRING_SECURITY_CONTEXT", sessionContext);
        request.addHeader(DiiTokenBypassFilter.HEADER, "secret");
        SecurityContextHolder.setContext(sessionContext);
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) ->
                assertEquals("c-wangsh8", SecurityContextHolder.getContext().getAuthentication().getName()));
        SecurityContextHolder.clearContext();
        SecurityContextHolder.setContext(sessionContext);
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), (req, res) ->
                assertEquals("c-wangsh8", SecurityContextHolder.getContext().getAuthentication().getName()));
        assertEquals("c-wangsh8", sessionContext.getAuthentication().getName());
    }

    @Test
    void operationTokenDoesNotOverrideHumanLoginStoredInSessionBeforeContextLoading() throws Exception {
        DaoIndexAnalysisProperties properties = new DaoIndexAnalysisProperties();
        properties.getBatchTrigger().setToken("secret");
        DiiTokenBypassFilter filter = new DiiTokenBypassFilter(properties);
        var sessionContext = SecurityContextHolder.createEmptyContext();
        sessionContext.setAuthentication(
                new UsernamePasswordAuthenticationToken("c-wangsh8", "N/A", List.of()));
        var request = new MockHttpServletRequest();
        request.getSession().setAttribute("SPRING_SECURITY_CONTEXT", sessionContext);
        request.addHeader(DiiTokenBypassFilter.HEADER, "secret");
        SecurityContextHolder.clearContext();

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null) {
                assertNotEquals(DiiTokenBypassFilter.DII_PRINCIPAL, authentication.getName());
            }
        });

        assertEquals("c-wangsh8", sessionContext.getAuthentication().getName());
    }

}
