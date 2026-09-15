package com.axonlink.ai.replay.controller;

import com.axonlink.ai.replay.ReplayConfigTestFixtures;
import com.axonlink.ai.replay.persistence.ReplayUnconditionalIgnoreDao;
import com.axonlink.ai.replay.service.ReplayConfigServiceCodeResolver;
import com.axonlink.ai.replay.service.ReplayUnconditionalIgnoreService;
import com.axonlink.security.UserPrincipalResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReplayConfigControllerTest {

    private static final String PREFIX = "/api/ai/parallel-replay/config/unconditional-ignores";

    private JdbcTemplate jdbc;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        jdbc = ReplayConfigTestFixtures.newJdbc();
        ReplayConfigTestFixtures.createSchema(jdbc);
        ReplayUnconditionalIgnoreService service = new ReplayUnconditionalIgnoreService(
                new ReplayUnconditionalIgnoreDao(jdbc), new ReplayConfigServiceCodeResolver(jdbc));
        ReplayUnconditionalIgnoreController controller =
                new ReplayUnconditionalIgnoreController(service, anonymousResolver());
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private static UserPrincipalResolver anonymousResolver() {
        return new UserPrincipalResolver() {
            @Override
            public Resolved resolve(HttpServletRequest request) {
                return new Resolved("ANONYMOUS", null, null);
            }
        };
    }

    @Test
    void invalidPageSizeReturns400() throws Exception {
        mvc.perform(get(PREFIX).param("limit", "25"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void listReturnsEmptyPage() throws Exception {
        mvc.perform(get(PREFIX))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void createConflictUpdateConflictDeleteAndOperations() throws Exception {
        String body = "{\"tranCode\":\"S1&sop\",\"fieldName\":\"accountNo\"}";
        mvc.perform(post(PREFIX).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(0))
                .andExpect(jsonPath("$.data.enableFlag").value(1));

        long id = jdbc.queryForObject(
                "SELECT MAX(id) FROM dii_replay_unconditional_ignore", Long.class);

        mvc.perform(post(PREFIX).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));

        mvc.perform(patch(PREFIX + "/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tranCode\":\"S1&sop\",\"fieldName\":\"accountNumber\",\"version\":99}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));

        mvc.perform(patch(PREFIX + "/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tranCode\":\"S1&sop\",\"fieldName\":\"accountNumber\",\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(1));

        mvc.perform(get(PREFIX + "/" + id + "/operations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items[0].operationType").value("UPDATE"));

        mvc.perform(delete(PREFIX + "/" + id))
                .andExpect(status().isBadRequest());

        mvc.perform(delete(PREFIX + "/" + id).param("version", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mvc.perform(get(PREFIX + "/" + id + "/operations"))
                .andExpect(status().isNotFound());
    }

    @Test
    void batchDeleteRejectsEmptyItems() throws Exception {
        mvc.perform(post(PREFIX + "/batch-delete").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }
}
