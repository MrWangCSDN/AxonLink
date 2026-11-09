package com.axonlink.ai.replay.dbissue;

import com.axonlink.ai.daoindex.config.DaoIndexAnalysisProperties;
import com.axonlink.security.UserPrincipalResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Map;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ReplayDatabaseIssueImportTokenTest {
    private final ReplayDatabaseIssueService service=mock(ReplayDatabaseIssueService.class);
    private final DaoIndexAnalysisProperties properties=new DaoIndexAnalysisProperties();
    private final MockMultipartFile file=new MockMultipartFile("file","bd.xlsx","",new byte[]{1});
    private MockMvc mvc;
    @BeforeEach void setup(){
        var resolver=mock(UserPrincipalResolver.class);
        when(resolver.resolve(any())).thenReturn(new UserPrincipalResolver.Resolved("LDAP","editor",null));
        when(service.importFile(any(),any(),any(),any(),any())).thenReturn(Map.of("createdRows",1));
        properties.getBatchTrigger().setToken("expected-token");
        mvc=MockMvcBuilders.standaloneSetup(new ReplayDatabaseIssueController(mock(ReplayDatabaseIssueDao.class),service,resolver,properties)).build();
    }
    @Test void rejectsMissingAndWrongTokenBeforeImport() throws Exception {
        mvc.perform(multipart("/api/ai/parallel-replay/database-issues/import").file(file))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("口令错误"));
        mvc.perform(multipart("/api/ai/parallel-replay/database-issues/import").file(file).header("X-DII-Trigger-Token","wrong"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("口令错误"));
        verifyNoInteractions(service);
    }
    @Test void acceptsConfiguredToken() throws Exception {
        mvc.perform(multipart("/api/ai/parallel-replay/database-issues/import").file(file).header("X-DII-Trigger-Token","expected-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        verify(service).importFile(any(),isNull(),any(),isNull(),isNull());
    }
    @Test void emptyConfigurationMatchesReplayIssueImportBehavior() throws Exception {
        properties.getBatchTrigger().setToken("");
        mvc.perform(multipart("/api/ai/parallel-replay/database-issues/import").file(file)).andExpect(status().isOk());
        verify(service).importFile(any(),isNull(),any(),isNull(),isNull());
    }
    @Test void migrationEndpointsAreRemoved() throws Exception {
        // The existing PATCH /{id} mapping reserves the single-segment path.
        mvc.perform(multipart("/api/ai/parallel-replay/database-issues/migrate-to-v2").file(file)).andExpect(status().isMethodNotAllowed());
        mvc.perform(multipart("/api/ai/parallel-replay/database-issues/migrate-to-v2/preview").file(file)).andExpect(status().isNotFound());
        verifyNoInteractions(service);
    }
}
