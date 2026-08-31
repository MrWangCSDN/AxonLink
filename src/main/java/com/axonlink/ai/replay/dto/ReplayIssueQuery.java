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
        List<String> occurrenceBatches,
        Boolean weeklyTask,
        String reviewStatus,
        List<String> reviewStatuses,
        String issueId,
        List<String> groupNames,
        List<String> sandboxes,
        List<String> plannedCompletionDates,
        List<String> issueIds,
        List<String> serialNos,
        List<String> globalSerialNos,
        List<String> defectRepairDates,
        List<String> transactionNames,
        List<String> fieldNames,
        List<String> issueDescriptions,
        List<String> issueKeys,
        List<String> issueDomains) {

    public ReplayIssueQuery(int limit, int offset, String groupName, Boolean sandbox, String issueLevel,
                            String issueType, String keyword, String issueStatus, String developer, String bankOwner,
                            String cooperationPerson, String serialNo, String globalSerialNo,
                            String defectRepairDate, String coverageRound,
                            List<String> transactionCodes, List<String> issueLevels, List<String> developers,
                            List<String> bankOwners, List<String> issueStatuses, List<String> issueTypes,
                            List<String> cooperationPersons, List<String> occurrenceBatches, Boolean weeklyTask,
                            String reviewStatus, List<String> reviewStatuses, String issueId,
                            List<String> groupNames, List<String> sandboxes, List<String> plannedCompletionDates,
                            List<String> issueIds, List<String> serialNos, List<String> globalSerialNos,
                            List<String> defectRepairDates, List<String> transactionNames, List<String> fieldNames,
                            List<String> issueDescriptions, List<String> issueKeys) {
        this(limit, offset, groupName, sandbox, issueLevel, issueType, keyword, issueStatus, developer, bankOwner,
                cooperationPerson, serialNo, globalSerialNo, defectRepairDate, coverageRound, transactionCodes,
                issueLevels, developers, bankOwners, issueStatuses, issueTypes, cooperationPersons,
                occurrenceBatches, weeklyTask, reviewStatus, reviewStatuses, issueId, groupNames, sandboxes,
                plannedCompletionDates, issueIds, serialNos, globalSerialNos, defectRepairDates,
                transactionNames, fieldNames, issueDescriptions, issueKeys, List.of());
    }

    public ReplayIssueQuery(int limit, int offset, String groupName, Boolean sandbox, String issueLevel,
                            String issueType, String keyword, String issueStatus, String developer, String bankOwner,
                            String cooperationPerson, String serialNo, String globalSerialNo,
                            String defectRepairDate, String coverageRound,
                            List<String> transactionCodes, List<String> issueLevels, List<String> developers,
                            List<String> bankOwners, List<String> issueStatuses, List<String> issueTypes,
                            List<String> cooperationPersons, List<String> occurrenceBatches, Boolean weeklyTask,
                            String reviewStatus, List<String> reviewStatuses, String issueId,
                            List<String> groupNames, List<String> sandboxes, List<String> plannedCompletionDates,
                            List<String> issueIds, List<String> serialNos, List<String> globalSerialNos,
                            List<String> defectRepairDates) {
        this(limit, offset, groupName, sandbox, issueLevel, issueType, keyword, issueStatus, developer, bankOwner,
                cooperationPerson, serialNo, globalSerialNo, defectRepairDate, coverageRound, transactionCodes,
                issueLevels, developers, bankOwners, issueStatuses, issueTypes, cooperationPersons,
                occurrenceBatches, weeklyTask, reviewStatus, reviewStatuses, issueId, groupNames, sandboxes,
                plannedCompletionDates, issueIds, serialNos, globalSerialNos, defectRepairDates,
                List.of(), List.of(), List.of(), List.of());
    }

    public ReplayIssueQuery(int limit, int offset, String groupName, Boolean sandbox, String issueLevel,
                            String issueType, String keyword, String issueStatus, String developer, String bankOwner,
                            String cooperationPerson, String serialNo, String globalSerialNo,
                            String defectRepairDate, String coverageRound,
                            List<String> transactionCodes, List<String> issueLevels, List<String> developers,
                            List<String> bankOwners, List<String> issueStatuses, List<String> issueTypes,
                            List<String> cooperationPersons, List<String> occurrenceBatches, Boolean weeklyTask,
                            String reviewStatus, List<String> reviewStatuses, String issueId,
                            List<String> groupNames, List<String> sandboxes, List<String> plannedCompletionDates) {
        this(limit, offset, groupName, sandbox, issueLevel, issueType, keyword, issueStatus, developer, bankOwner,
                cooperationPerson, serialNo, globalSerialNo, defectRepairDate, coverageRound, transactionCodes,
                issueLevels, developers, bankOwners, issueStatuses, issueTypes, cooperationPersons,
                occurrenceBatches, weeklyTask, reviewStatus, reviewStatuses, issueId, groupNames, sandboxes,
                plannedCompletionDates, List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of());
    }

    public ReplayIssueQuery(int limit, int offset, String groupName, Boolean sandbox, String issueLevel,
                            String issueType, String keyword, String issueStatus, String developer, String bankOwner,
                            String cooperationPerson, String serialNo, String globalSerialNo,
                            String defectRepairDate, String coverageRound,
                            List<String> transactionCodes, List<String> issueLevels, List<String> developers,
                            List<String> bankOwners, List<String> issueStatuses, List<String> issueTypes,
                            List<String> cooperationPersons, List<String> occurrenceBatches, Boolean weeklyTask,
                            String reviewStatus, List<String> reviewStatuses, String issueId,
                            List<String> groupNames, List<String> sandboxes) {
        this(limit, offset, groupName, sandbox, issueLevel, issueType, keyword, issueStatus, developer, bankOwner,
                cooperationPerson, serialNo, globalSerialNo, defectRepairDate, coverageRound, transactionCodes,
                issueLevels, developers, bankOwners, issueStatuses, issueTypes, cooperationPersons,
                occurrenceBatches, weeklyTask, reviewStatus, reviewStatuses, issueId, groupNames, sandboxes,
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of());
    }

    public ReplayIssueQuery(int limit, int offset, String groupName, Boolean sandbox, String issueLevel,
                            String issueType, String keyword, String issueStatus, String developer, String bankOwner,
                            String cooperationPerson, String serialNo, String globalSerialNo,
                            String defectRepairDate, String coverageRound,
                            List<String> transactionCodes, List<String> issueLevels, List<String> developers,
                            List<String> bankOwners, List<String> issueStatuses, List<String> issueTypes,
                            List<String> cooperationPersons, List<String> occurrenceBatches, Boolean weeklyTask,
                            String reviewStatus, List<String> reviewStatuses) {
        this(limit, offset, groupName, sandbox, issueLevel, issueType, keyword, issueStatus, developer, bankOwner,
                cooperationPerson, serialNo, globalSerialNo, defectRepairDate, coverageRound, transactionCodes,
                issueLevels, developers, bankOwners, issueStatuses, issueTypes, cooperationPersons,
                occurrenceBatches, weeklyTask, reviewStatus, reviewStatuses, null, List.of(), List.of(), List.of());
    }

    public ReplayIssueQuery(int limit, int offset, String groupName, Boolean sandbox, String issueLevel,
                            String issueType, String keyword, String issueStatus, String developer, String bankOwner,
                            String cooperationPerson, String serialNo, String globalSerialNo,
                            String defectRepairDate, String coverageRound,
                            List<String> transactionCodes, List<String> issueLevels, List<String> developers,
                            List<String> bankOwners, List<String> issueStatuses, List<String> issueTypes,
                            List<String> cooperationPersons, List<String> occurrenceBatches, Boolean weeklyTask) {
        this(limit, offset, groupName, sandbox, issueLevel, issueType, keyword, issueStatus, developer, bankOwner,
                cooperationPerson, serialNo, globalSerialNo, defectRepairDate, coverageRound, transactionCodes,
                issueLevels, developers, bankOwners, issueStatuses, issueTypes, cooperationPersons,
                occurrenceBatches, weeklyTask, null, List.of(), null, List.of(), List.of(), List.of());
    }

    public ReplayIssueQuery(int limit, int offset, String groupName, Boolean sandbox, String issueLevel,
                            String issueType, String keyword, String issueStatus, String developer, String bankOwner,
                            String cooperationPerson, String serialNo, String globalSerialNo,
                            String defectRepairDate, String coverageRound,
                            List<String> transactionCodes, List<String> issueLevels, List<String> developers,
                            List<String> bankOwners, List<String> issueStatuses, List<String> issueTypes,
                            List<String> cooperationPersons, List<String> occurrenceBatches) {
        this(limit, offset, groupName, sandbox, issueLevel, issueType, keyword, issueStatus, developer, bankOwner,
                cooperationPerson, serialNo, globalSerialNo, defectRepairDate, coverageRound,
                transactionCodes, issueLevels, developers, bankOwners, issueStatuses, issueTypes,
                cooperationPersons, occurrenceBatches, null);
    }

    public ReplayIssueQuery(int limit, int offset, String groupName, Boolean sandbox, String issueLevel,
                            String issueType, String keyword, String issueStatus, String developer, String bankOwner,
                            String cooperationPerson, String serialNo, String globalSerialNo,
                            String defectRepairDate, String coverageRound) {
        this(limit, offset, groupName, sandbox, issueLevel, issueType, keyword, issueStatus, developer, bankOwner,
                cooperationPerson, serialNo, globalSerialNo, defectRepairDate, coverageRound,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null);
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
                transactionCodes, issueLevels, developers, bankOwners, issueStatuses, issueTypes, cooperationPersons, List.of(), null);
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
