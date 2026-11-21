package com.axonlink.ai.replay.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayIssueCompletionCountsTest {

    @Test
    void calculatesCompletedRateFromOnTimeAndLateFixedCounts() {
        ReplayIssueCompletionCounts counts = ReplayIssueCompletionCounts.of(13, 4, 10, 5, 6);

        assertEquals(32, counts.plannedTotal());
        assertEquals(new BigDecimal("53.13"), counts.completionRate());
        assertEquals(6, counts.pendingVerificationCount());
    }

    @Test
    void returnsNullRateWhenThereAreNoPlannedIssues() {
        assertNull(ReplayIssueCompletionCounts.of(0, 0, 0, 0).completionRate());
    }

    @Test
    void exposesNullAverageDaysWhenThereAreNoDelayedIssues() {
        var json = new ObjectMapper().valueToTree(ReplayIssueCompletionCounts.of(3, 0, 2, 0));

        assertTrue(json.has("averageLateFixedDays"));
        assertTrue(json.path("averageLateFixedDays").isNull());
        assertTrue(json.has("averageOverdueUnfinishedDays"));
        assertTrue(json.path("averageOverdueUnfinishedDays").isNull());
    }

    @Test
    void roundsEachAverageToTwoDecimalPlacesHalfUp() {
        var counts = ReplayIssueCompletionCounts.of(1, 40, 3, 8, 0, 107, 11);

        assertEquals(new BigDecimal("2.68"), counts.averageLateFixedDays());
        assertEquals(new BigDecimal("1.38"), counts.averageOverdueUnfinishedDays());
    }

    @Test
    void groupAndDeveloperJsonExposeCountsAtTheDocumentedLevel() throws Exception {
        ReplayIssueCompletionCounts counts = ReplayIssueCompletionCounts.of(1, 2, 3, 4, 5, 7, 19);
        ReplayIssueCompletionDeveloperRow developer =
                new ReplayIssueCompletionDeveloperRow("张三、李四", counts);
        ReplayIssueCompletionGroupRow group =
                new ReplayIssueCompletionGroupRow("公共组", counts, List.of(developer));

        var json = new ObjectMapper().findAndRegisterModules().readTree(
                new ObjectMapper().findAndRegisterModules().writeValueAsString(group));

        assertEquals("公共组", json.path("groupName").asText());
        assertEquals(10, json.path("plannedTotal").asLong());
        assertEquals(1, json.path("onTimeFixedCount").asLong());
        assertEquals(5, json.path("pendingVerificationCount").asLong());
        assertFalse(json.has("counts"));
        assertFalse(json.has("lateFixedDaysTotal"));
        assertFalse(json.has("overdueUnfinishedDaysTotal"));
        assertEquals(3.5, json.path("averageLateFixedDays").asDouble(-1));
        assertEquals(4.75, json.path("averageOverdueUnfinishedDays").asDouble(-1));
        assertEquals("张三、李四", json.path("developers").get(0).path("matchedDeveloper").asText());
        assertEquals(2, json.path("developers").get(0).path("lateFixedCount").asLong());
        assertEquals(5, json.path("developers").get(0).path("pendingVerificationCount").asLong());
        assertEquals(3.5, json.path("developers").get(0).path("averageLateFixedDays").asDouble(-1));
        assertEquals(4.75, json.path("developers").get(0).path("averageOverdueUnfinishedDays").asDouble(-1));
        assertFalse(json.path("developers").get(0).has("lateFixedDaysTotal"));
    }
}
