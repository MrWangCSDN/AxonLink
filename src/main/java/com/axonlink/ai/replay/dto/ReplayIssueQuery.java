package com.axonlink.ai.replay.dto;

import java.util.List;

/** Server-side pagination and filters for the active replay issue snapshot. */
public record ReplayIssueQuery(
        int limit,
        int offset,
        String groupName,
        Boolean sandbox,
        String issueLevel,
        String issueType,
        String keyword,
        String issueStatus,
        String developer,
        String bankOwner,
        String cooperationPerson,
        String serialNo,
        String globalSerialNo,
        String defectRepairDate,
        String coverageRound,
        List<String> transactionCodes,
        List<String> issueLevels,
        List<String> developers,
        List<String> bankOwners,
        List<String> issueStatuses,
        List<String> issueTypes,
        List<String> cooperationPersons,
        List<String> occurrenceBatches) {

    public ReplayIssueQuery(int limit, int offset, String groupName, Boolean sandbox, String issueLevel,
                            String issueType, String keyword, String issueStatus, String developer, String bankOwner,
                            String cooperationPerson, String serialNo, String globalSerialNo,
                            String defectRepairDate, String coverageRound) {
        this(limit, offset, groupName, sandbox, issueLevel, issueType, keyword, issueStatus, developer, bankOwner,
                cooperationPerson, serialNo, globalSerialNo, defectRepairDate, coverageRound,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    public ReplayIssueQuery(int limit, int offset, String groupName, Boolean sandbox, String issueLevel,
                            String issueType, String keyword, String issueStatus, String developer, String bankOwner,
                            String cooperationPerson, String serialNo, String globalSerialNo,
                            String defectRepairDate, String coverageRound,
                            List<String> transactionCodes, List<String> issueLevels, List<String> developers,
                            List<String> bankOwners, List<String> issueStatuses, List<String> issueTypes,
                            List<String> cooperationPersons) {
        this(limit, offset, groupName, sandbox, issueLevel, issueType, keyword, issueStatus, developer, bankOwner,
                cooperationPerson, serialNo, globalSerialNo, defectRepairDate, coverageRound,
                transactionCodes, issueLevels, developers, bankOwners, issueStatuses, issueTypes, cooperationPersons, List.of());
    }

    public ReplayIssueQuery(int limit, int offset, String groupName, Boolean sandbox,
                            String issueLevel, String issueType, String keyword, String issueStatus) {
        this(limit, offset, groupName, sandbox, issueLevel, issueType, keyword, issueStatus,
                null, null, null, null, null, null, null);
    }

    public ReplayIssueQuery(int limit, int offset, String groupName, Boolean sandbox,
                            String issueLevel, String issueType, String keyword) {
        this(limit, offset, groupName, sandbox, issueLevel, issueType, keyword, null,
                null, null, null, null, null, null, null);
    }
}
