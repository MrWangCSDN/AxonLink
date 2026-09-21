package com.axonlink.ai.replay.dbcompare.dto;

import java.time.LocalDate;
import java.util.List;

public record ReplayDbCompareVersionTableItem(
        long sourceRegistrationId,
        long sourceRegistrationVersion,
        String schemaName,
        String tableName,
        String tableComment,
        String domainName,
        String reviserEmpNo,
        String reviserUsername,
        String reviserName,
        String groupOwnerEmpNo,
        String groupOwnerUsername,
        String groupOwnerName,
        ReplayDbCompareConditionTree whereCondition,
        String whereSql,
        Long compareLimit,
        LocalDate registeredDate,
        int fieldCount,
        List<ReplayDbCompareVersionField> fields,
        int partitionNum) {

    public ReplayDbCompareVersionTableItem(
        long sourceRegistrationId,
        long sourceRegistrationVersion,
        String schemaName,
        String tableName,
        String tableComment,
        String domainName,
        String reviserEmpNo,
        String reviserUsername,
        String reviserName,
        String groupOwnerEmpNo,
        String groupOwnerUsername,
        String groupOwnerName,
        ReplayDbCompareConditionTree whereCondition,
        String whereSql,
        Long compareLimit,
        LocalDate registeredDate,
        int fieldCount,
        List<ReplayDbCompareVersionField> fields) {
        this(sourceRegistrationId, sourceRegistrationVersion, schemaName, tableName, tableComment, domainName,
                reviserEmpNo, reviserUsername, reviserName, groupOwnerEmpNo, groupOwnerUsername, groupOwnerName,
                whereCondition, whereSql, compareLimit, registeredDate, fieldCount, fields, 1);
    }

    public ReplayDbCompareVersionTableItem withPartitionNum(int partitionNum) {
        return new ReplayDbCompareVersionTableItem(sourceRegistrationId, sourceRegistrationVersion, schemaName,
                tableName, tableComment, domainName, reviserEmpNo, reviserUsername, reviserName, groupOwnerEmpNo,
                groupOwnerUsername, groupOwnerName, whereCondition, whereSql, compareLimit, registeredDate,
                fieldCount, fields, partitionNum);
    }

    public ReplayDbCompareVersionTableItem {
        fields = fields == null ? List.of() : List.copyOf(fields);
    }

    public ReplayDbCompareVersionTableItem(
            long sourceRegistrationId, long sourceRegistrationVersion, String schemaName,
            String tableName, String tableComment, String domainName, String reviserEmpNo,
            String reviserUsername, String reviserName, String groupOwnerEmpNo,
            String groupOwnerName, LocalDate registeredDate, int fieldCount,
            List<ReplayDbCompareVersionField> fields) {
        this(sourceRegistrationId, sourceRegistrationVersion, schemaName, tableName, tableComment,
                domainName, reviserEmpNo, reviserUsername, reviserName, groupOwnerEmpNo, null,
                groupOwnerName, null, null, null, registeredDate, fieldCount, fields);
    }

    public ReplayDbCompareVersionTableItem(
            long sourceRegistrationId, long sourceRegistrationVersion, String schemaName,
            String tableName, String tableComment, String domainName, String reviserEmpNo,
            String reviserUsername, String reviserName, String groupOwnerEmpNo,
            String groupOwnerUsername, String groupOwnerName, LocalDate registeredDate,
            int fieldCount, List<ReplayDbCompareVersionField> fields) {
        this(sourceRegistrationId, sourceRegistrationVersion, schemaName, tableName, tableComment,
                domainName, reviserEmpNo, reviserUsername, reviserName, groupOwnerEmpNo,
                groupOwnerUsername, groupOwnerName, null, null, null,
                registeredDate, fieldCount, fields);
    }
}
