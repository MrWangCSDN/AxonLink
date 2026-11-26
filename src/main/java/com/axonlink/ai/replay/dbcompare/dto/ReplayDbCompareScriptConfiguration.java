package com.axonlink.ai.replay.dbcompare.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Named, independently generated table comparison scripts and their editable inputs. */
public final class ReplayDbCompareScriptConfiguration {
    private ReplayDbCompareScriptConfiguration() {}

    public record TableRequest(String tableName, String tableComment, String domainName,
                               String ownerName, BigDecimal maxPartitionNum, List<String> ignoredFields) {}

    public record SaveRequest(String name, Long revision, List<String> globalIgnoredFields,
                              List<TableRequest> tables, ConnectionRequest connection) {
        public SaveRequest(String name, Long revision, List<String> globalIgnoredFields, List<TableRequest> tables) {
            this(name, revision, globalIgnoredFields, tables, null);
        }

        @JsonCreator
        static SaveRequest fromJson(@JsonProperty("name") String name, @JsonProperty("revision") BigDecimal revision,
                                    @JsonProperty("globalIgnoredFields") List<String> globalIgnoredFields,
                                    @JsonProperty("tables") List<TableRequest> tables,
                                    @JsonProperty("connection") ConnectionRequest connection) {
            return new SaveRequest(name, revision == null ? null : revision.longValueExact(),
                    globalIgnoredFields, tables, connection);
        }
    }

    public record ConnectionRequest(String mode, String host, BigDecimal port, String database, String schema,
                                    String username,
                                    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String password) {
        @Override public String toString() { return "ConnectionRequest[redacted]"; }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ConnectionDetails(String mode, String host, Integer port, String database, String schema,
                                    String username, Boolean passwordConfigured) {}

    public record PreviewRequest(BigDecimal configurationId, BigDecimal revision, ConnectionRequest connection,
                                 List<TableRequest> tables) {}

    public record Table(String tableName, String tableComment, String domainName, String ownerName,
                        int maxPartitionNum, List<String> ignoredFields, List<ReplayBaseColumnOption> fields) {
        public Table {
            ignoredFields = ignoredFields == null ? List.of() : List.copyOf(ignoredFields);
            fields = fields == null ? List.of() : List.copyOf(fields);
        }
    }

    public record Content(List<String> globalIgnoredFields, List<Table> tables) {
        public Content {
            globalIgnoredFields = globalIgnoredFields == null ? List.of() : List.copyOf(globalIgnoredFields);
            tables = tables == null ? List.of() : List.copyOf(tables);
        }
    }

    public record Summary(long id, String name, long revision, int tableCount, int fieldCount,
                          String updatedBy, LocalDateTime updatedAt) {}

    public record Page(List<Summary> items) {}

    public record Detail(long id, String name, long revision, List<String> globalIgnoredFields,
                         List<Table> tables, int tableCount, int fieldCount,
                         String updatedBy, LocalDateTime updatedAt, ConnectionDetails connection) {}

    public record TableImportResult(List<Table> tables) {}

    public record ValidationError(Integer row, String tableName, String fieldName, String reason) {}
}
