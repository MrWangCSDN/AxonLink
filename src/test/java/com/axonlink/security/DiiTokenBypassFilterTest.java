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
}
