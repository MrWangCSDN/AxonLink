# 接口领域与批次历史统计验证记录

## 本次相关回归

| 测试类 | 数量 | 失败/错误/跳过 |
|---|---:|---|
| ReplayIssueBatchStatisticsDaoTest | 9 | 0/0/0 |
| ReplayIssueBatchStatisticsMigrationTest | 1 | 0/0/0 |
| ReplayInterfaceDomainAttributionTest | 4 | 0/0/0 |
| ReplayIssueControllerTest | 98 | 0/0/0 |
| ReplayIssueDaoTest | 36 | 0/0/0 |
| ReplayDailyReportCalculatorTest | 10 | 0/0/0 |
| ReplayFieldDifferenceAcceptanceTest | 8 | 0/0/0 |
| ReplayIssueDailyReportServiceTest | 26 | 0/0/0 |
| ReplayWeeklyReportServiceTest | 10 | 0/0/0 |

直接相关共 202 项通过。包含真实数据库到日报/周报 Excel 和汇总 JSON、前批次9笔后批次25笔隔离、接口领域归属、审核冻结、双连接冻结互斥、缺失/坏快照拒绝、旧导入文字笔数兼容及V88迁移。

RED：接口域/历史数量/HTTP缺口提示6项均失败且无运行错误；严格空交易码/非法级别回归也先失败，再加入校验。

## 全量验证及既有失败

执行 Java17 `mvn -q test`，共1170项，9项failure、33项error、6项skip。42项未通过中：

- 2项 SecurityIntegrationTest 因隔离目录缺少被Git忽略的已构建前端资源；复制原工作区现有233个静态文件后，完整该类复验9项：0 failure、0 error、2 skip。未修改产品代码。
- 剩余40项属于两类既有数据库问题清单测试，已在独立临时目录导出基线 `f632dc5` 并复现：ReplayDatabaseIssueTest 为4 failure、9 error；ReplayDatabaseIssueControllerTest 为5 failure、22 error。与本次逐条核对方法名、failure/error种类、异常类型和message均一致。本次未修改相应生产模块。

本次不声称全量测试全部通过。两类基线失败如下：

### ReplayDatabaseIssueTest

- `parsesRealBdExportIncludingDirectionTitleAndAppendedBatch`（failure）
- `noActionReviewRequiresPermissionAndPreservesApprovalOnLaterImports`（error）
- `firstImportCreatesNewIssuesAndUsesExistingRegistrationMetadataForOldFiles`（failure）
- `disappearsAsFixedAndReappearsAsNewWithInheritedContent`（error）
- `importsIgnoreMissingOrConflictingReportDomainAndOwnerAndSnapshotCurrentRegistration`（error）
- `importsOfOtherSuccessfulTablesDoNotRepairUncoveredTables`（error）
- `preservesHumanContentAndReopensOnlyWhenBatchDateIsAfterManualSaveDate`（error）
- `acceptsReorderedColumnsAndRepeatedHeadersButRejectsDuplicateTables`（failure）
- `batchAfterAutoRepairCannotBeRolledBackToAnEarlierOccurrence`（error）
- `rejectsStaleVersionAndEarlierBatchWithoutChangingCurrentOrHistory`（error）
- `repeatBatchUpdatesOriginalSnapshotWithoutDuplicatingHistory`（error）
- `protectsSameDayBatchWhenImportedOnALaterSystemDate`（error）
- `parsesBothDirectionsAndFieldIdsIgnoringReportOwnerAndKeepingZeroCountScope`（failure）

### ReplayDatabaseIssueControllerTest

- `importListEditAndTrackUseSeparateLevelsAndTypes`（failure）
- `reviewFacetAndExportUseTheThreeDisplayStatusesWithoutChangingRawValues`（error）
- `exportUsesTheCurrentVisibleColumnsAndMergedTableNames`（error）
- `exportRejectsInvalidRepeatedCriteriaAsBadRequest`（error）
- `occurrenceBatchesIncludeEveryActualImportNewestFirstAndExportThem`（error）
- `oldReleaseProtectsPendingVerificationDespiteNewBatchDate`（error）
- `exportShowsCurrentRegisteredTechnologyOwnerAndReviserSeparately`（error）
- `facetsUseAllScopedRowsExcludeTheirOwnFilterAndKeepBlankCandidates`（error）
- `facetsReportTruncationAndCountsWithoutScanningOnlyTheCurrentPage`（error）
- `rejectsBlankNumericFilterValues`（error）
- `occurrenceBatchesFallBackToCurrentBatchWhenHistoryIsMissing`（error）
- `historicalBatchFiltersAndFacetsCountDistinctIssuesAcrossAllOccurrences`（failure）
- `saveBeforeReleaseTimeCanBeReopened`（error）
- `mergedTableFacetSearchesChineseNameAndIncludesDeterministicDisplayLabel`（error）
- `candidateSearchEscapesWildcardsAndRejectsInvalidFieldAndCounts`（error）
- `listPermissionsResolveTechnologyOwnersOncePerPageAndDoNotUseNamesAsIdentifiers`（failure）
- `currentRegistrationDrivesDomainOwnerFilteringOptionsExportAndMutationResponsesWithoutReimport`（failure）
- `repeatedFiltersApplyOrWithinColumnsAndAndBetweenColumnsBeforePagingAndExport`（error）
- `exactRepeatedValuesPreserveCommasAndOtherColumns`（error）
- `repairDateFiltersAndCandidatesUseAllScopedRowsAndShareSortedExportCriteria`（error）
- `protectedPendingIssuesStillAutoFixWhenAbsentAsRequested`（error）
- `postFilteringAndExportSupportSelectionsLargerThanNormalUrlLimits`（error）
- `anActualAppearanceSurvivesAnAbsentOverwriteOfTheSameBatchAndLaterAppearance`（error）
- `tableTechnologyOwnerControlsReviewAndReturnedRowPermissions`（failure）
- `collaboratorDisplayFiltersCombineWithOtherColumnsAndSharedPagingSortingAndExport`（error）
- `saveAtReleaseTimeCanBeReopened`（error）
- `differenceCountSortingIsNumericStableFilteredAndSharedByPagedListsAndExports`（error）
