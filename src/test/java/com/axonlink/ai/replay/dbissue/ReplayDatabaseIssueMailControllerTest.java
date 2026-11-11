package com.axonlink.ai.replay.dbissue;

import com.axonlink.ai.daoindex.config.DaoIndexAnalysisProperties;
import com.axonlink.ai.replay.dto.ReplayIssueMailRecipientStatus;
import com.axonlink.ai.replay.dto.ReplayIssueMailStatus;
import com.axonlink.security.UserPrincipalResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ReplayDatabaseIssueMailControllerTest {
    private static final String API = "/api/ai/parallel-replay/database-issues/7";
    private ReplayDatabaseIssueMailService mailService;
    private UserPrincipalResolver resolver;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mailService = mock(ReplayDatabaseIssueMailService.class);
        resolver = mock(UserPrincipalResolver.class);
        var controller = new ReplayDatabaseIssueController(mock(ReplayDatabaseIssueDao.class),
                mock(ReplayDatabaseIssueService.class), resolver, new DaoIndexAnalysisProperties());
        controller.setMailService(mailService);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void anonymousStatusAndSendRequestsRequireLogin() throws Exception {
        mvc.perform(get(API + "/mail-status")).andExpect(status().isUnauthorized());
        mvc.perform(post(API + "/mail-send").contentType(MediaType.APPLICATION_JSON)
                .content("{\"recipientEmails\":[\"dev@example.com\"]}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(mailService);
    }

    @Test
    void statusAndSendUseTheExistingMailResponseAndRecipientSelection() throws Exception {
        login();
        var result = new ReplayIssueMailStatus("SENDING", null, "dev@example.com", null, List.of(
                new ReplayIssueMailRecipientStatus("开发甲", "dev", "dev@example.com", "协同人", "SENDING", null, null)));
        when(mailService.status(eq(7L), any())).thenReturn(result);
        when(mailService.requestSend(eq(7L), anyList(), any())).thenReturn(result);

        mvc.perform(get(API + "/mail-status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recipients[0].role").value("协同人"));
        mvc.perform(post(API + "/mail-send").contentType(MediaType.APPLICATION_JSON)
                .content("{\"recipientEmails\":[\"dev@example.com\",\"dev@example.com\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("SENDING"));
        verify(mailService).requestSend(eq(7L), eq(List.of("dev@example.com")), argThat(actor -> "editor".equals(actor.username())));
    }

    @Test
    void missingProblemsAndInvalidRequestsRetainTheirHttpStatus() throws Exception {
        login();
        when(mailService.status(eq(7L), any())).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "数据库回放问题不存在"));
        mvc.perform(get(API + "/mail-status")).andExpect(status().isNotFound());
        when(mailService.requestSend(eq(7L), anyList(), any())).thenThrow(new IllegalArgumentException("系统发件邮箱未配置"));
        mvc.perform(post(API + "/mail-send").contentType(MediaType.APPLICATION_JSON).content("{\"recipientEmails\":[]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("系统发件邮箱未配置"));
    }

    @Test
    void absentBodyKeepsTheReferencesSendAllContract() throws Exception {
        login();
        mvc.perform(post(API + "/mail-send")).andExpect(status().isOk());
        verify(mailService).requestSend(eq(7L), isNull(), any());
    }

    private void login() {
        when(resolver.resolve(any())).thenReturn(new UserPrincipalResolver.Resolved("LDAP", "editor", null));
    }
}
