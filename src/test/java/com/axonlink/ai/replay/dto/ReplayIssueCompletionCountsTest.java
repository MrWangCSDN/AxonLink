package com.axonlink.ai.replay.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class ReplayIssueCompletionCountsTest {

    @Test
    void calculatesCompletedRateFromOnTimeAndLateFixedCounts() {
        ReplayIssueCompletionCounts counts = ReplayIssueCompletionCounts.of(13, 4, 10, 5);

        assertEquals(32, counts.plannedTotal());
        assertEquals(new BigDecimal("53.13"), counts.completionRate());
    }

    @Test
    void returnsNullRateWhenThereAreNoPlannedIssues() {
        assertNull(ReplayIssueCompletionCounts.of(0, 0, 0, 0).completionRate());
    }

    @Test
    void groupAndDeveloperJsonExposeCountsAtTheDocumentedLevel() throws Exception {
        ReplayIssueCompletionCounts counts = ReplayIssueCompletionCounts.of(1, 2, 3, 4);
        ReplayIssueCompletionDeveloperRow developer =
                new ReplayIssueCompletionDeveloperRow("张三、李四", counts);
        ReplayIssueCompletionGroupRow group =
                new ReplayIssueCompletionGroupRow("公共组", counts, List.of(developer));

        var json = new ObjectMapper().findAndRegisterModules().readTree(
                new ObjectMapper().findAndRegisterModules().writeValueAsString(group));

        assertEquals("公共组", json.path("groupName").asText());
        assertEquals(10, json.path("plannedTotal").asLong());
        assertEquals(1, json.path("onTimeFixedCount").asLong());
        assertFalse(json.has("counts"));
        assertEquals("张三、李四", json.path("developers").get(0).path("matchedDeveloper").asText());
        assertEquals(2, json.path("developers").get(0).path("lateFixedCount").asLong());
    }
}
