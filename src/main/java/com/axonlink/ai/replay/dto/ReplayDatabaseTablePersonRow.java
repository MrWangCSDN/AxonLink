package com.axonlink.ai.replay.dto;

import java.time.LocalDateTime;

public record ReplayDatabaseTablePersonRow(Long id, String domain, String tableName, String tableComment,
        String developer, String developerUsernames, String developerEmpNos, String technologyOwner,
        String technologyOwnerUsernames, String technologyOwnerEmpNos, LocalDateTime importedAt) {}
