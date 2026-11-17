package com.axonlink.ai.replay.dbcompare.dto;

public enum ReplayDbCompareMetadataStatus {
    VALID,
    MISSING_FIELDS,
    MISSING_CONDITION_FIELDS,
    DOMAIN_MAPPING_MISSING,
    CUSTOM_SQL_INVALID,
    ORDERING_PRIMARY_KEY_CHANGED,
    TABLE_MISSING,
    UNAVAILABLE
}
