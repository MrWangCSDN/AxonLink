package com.axonlink.ai.replay.dbcompare.service;

import com.axonlink.ai.replay.dbcompare.config.ReplayDatabaseComparisonProperties;
import com.axonlink.ai.replay.dbcompare.dto.ReplayBaseColumnOption;
import com.axonlink.ai.replay.dbcompare.dto.ReplayDbCompareSqlColumn;
import com.axonlink.ai.replay.dbcompare.persistence.ReplayBaseDataSourceRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** Exercises catalog filtering against real JDBC rows, including case-distinct relation and column names. */
public class ReplayDatabaseComparisonCustomSqlCatalogTest {
    @Test
    void exactSourceLookupDoesNotMixCaseDistinctSiblingRelationsOrFoldPhysicalColumns() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:sql_catalog_" + UUID.randomUUID(), "sa", "")) {
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            String owner = ReplayDatabaseComparisonCustomSqlCatalogTest.class.getName();
            jdbc.execute("CREATE ALIAS obj_description FOR '" + owner + ".tableComment'");
            jdbc.execute("CREATE ALIAS col_description FOR '" + owner + ".columnComment'");
            jdbc.execute("CREATE ALIAS format_type FOR '" + owner + ".typeName'");
            jdbc.execute("CREATE ALIAS array_to_string FOR '" + owner + ".arrayText'");
            jdbc.execute("CREATE TABLE pg_namespace(oid INT, nspname VARCHAR)");
            jdbc.execute("CREATE TABLE pg_class(oid INT, relname VARCHAR, relnamespace INT, relkind CHAR)");
            jdbc.execute("CREATE TABLE pg_attribute(attrelid INT,attname VARCHAR,atttypid INT,atttypmod INT,attnum INT,attisdropped BOOLEAN)");
            jdbc.execute("CREATE TABLE pg_constraint(conrelid INT,contype CHAR,conkey VARCHAR)");
            jdbc.execute("CREATE TABLE pg_index(indrelid INT,indexrelid INT,indkey VARCHAR,indnatts INT,indisprimary BOOLEAN,"
                    + "indisunique BOOLEAN,indisvalid BOOLEAN,indisready BOOLEAN,indexprs VARCHAR,indpred VARCHAR)");
            jdbc.execute("INSERT INTO pg_namespace VALUES(1,'base_schema')");
            jdbc.execute("INSERT INTO pg_class VALUES(10,'acct_main',1,'r'),(20,'ACCT_MAIN',1,'r'),"
                    + "(11,'acct_pk',1,'i'),(21,'upper_unique',1,'i')");
            jdbc.execute("INSERT INTO pg_attribute VALUES(10,'id',23,-1,1,FALSE),(10,'ID',23,-1,2,FALSE),"
                    + "(10,'record_id',23,-1,3,FALSE),(20,'foreign_key',23,-1,1,FALSE)");
            jdbc.execute("INSERT INTO pg_constraint VALUES(10,'p','3')");
            jdbc.execute("INSERT INTO pg_index VALUES(10,11,'3',1,TRUE,TRUE,TRUE,TRUE,NULL,NULL),"
                    + "(20,21,'1',1,FALSE,TRUE,TRUE,TRUE,NULL,NULL)");
            var properties = new ReplayDatabaseComparisonProperties();
            properties.setBaseSchema("base_schema");
            var registry = mock(ReplayBaseDataSourceRegistry.class);
            var metadata = new ReplayBaseMetadataService(registry, properties);

            var source = metadata.inspectTables(connection, "base_schema", List.of("acct_main"), 5).get("acct_main");

            assertThat(source.columns()).extracting(ReplayBaseColumnOption::columnName)
                    .containsExactly("id", "ID", "record_id");
            assertThat(source.keyOptions()).extracting("indexName").containsExactly("acct_pk");
            assertThat(source.keyOptions().get(0).columnNames()).containsExactly("record_id");
            var resultColumns = List.of(new ReplayDbCompareSqlColumn("record_id", "record_id", "integer", 1),
                    new ReplayDbCompareSqlColumn("id", "id", "integer", 2),
                    new ReplayDbCompareSqlColumn("upper_id", "upper_id", "integer", 3));
            assertThat(ReplayDatabaseComparisonCustomSqlKeys.availableKeys(resultColumns, source.keyOptions()))
                    .extracting("columnNames").containsExactly(List.of("record_id"));
            assertThat(connection.isClosed()).isFalse();
            verifyNoInteractions(registry);
        }
    }

    public static String tableComment(int oid) { return null; }
    public static String columnComment(int oid, int attnum) { return null; }
    public static String typeName(int oid, int typmod) { return "integer"; }
    public static String arrayText(String value, String delimiter) { return value; }
}
