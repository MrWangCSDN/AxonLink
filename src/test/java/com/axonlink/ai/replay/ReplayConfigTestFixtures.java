package com.axonlink.ai.replay;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import javax.sql.DataSource;

/** 回放配置管理测试用 H2 建表（MODE=MySQL，去除 MySQL 专属字符集与引擎子句）。 */
public final class ReplayConfigTestFixtures {

    private ReplayConfigTestFixtures() {
    }

    public static JdbcTemplate newJdbc() {
        DataSource dataSource = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName("replay_config_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1")
                .build();
        return new JdbcTemplate(dataSource);
    }

    public static void createSchema(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE znzx_service ("
                + "application_name VARCHAR(128) NOT NULL,"
                + "esf_service_code VARCHAR(256) NOT NULL,"
                + "flow_id VARCHAR(128) NOT NULL,"
                + "tran_code VARCHAR(128) NOT NULL,"
                + "function_desc VARCHAR(512),"
                + "group_name VARCHAR(128))");
        jdbc.execute("CREATE INDEX idx_znzx_service_tran_code ON znzx_service(tran_code)");

        jdbc.execute("CREATE TABLE dii_replay_unconditional_ignore ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "tran_code VARCHAR(192) NOT NULL,"
                + "field_name VARCHAR(256) NOT NULL,"
                + "enable_flag TINYINT NOT NULL DEFAULT 1,"
                + "created_at DATETIME NOT NULL,"
                + "updated_at DATETIME NOT NULL,"
                + "version INT NOT NULL DEFAULT 0,"
                + "UNIQUE (tran_code, field_name))");
        jdbc.execute("CREATE TABLE dii_replay_unconditional_ignore_operation ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "config_id BIGINT NOT NULL,"
                + "operation_type VARCHAR(16) NOT NULL,"
                + "tran_code VARCHAR(192),"
                + "field_name VARCHAR(256),"
                + "new_tran_code VARCHAR(192),"
                + "new_field_name VARCHAR(256),"
                + "operator_username VARCHAR(128),"
                + "operator_real_name VARCHAR(128),"
                + "operation_source VARCHAR(16) NOT NULL DEFAULT 'MANUAL',"
                + "created_at DATETIME NOT NULL)");

        jdbc.execute("CREATE TABLE dii_replay_conditional_rmove ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "orig_trcd VARCHAR(192) NOT NULL,"
                + "field_rmove_name VARCHAR(256) NOT NULL,"
                + "field_fiel_state TINYINT NOT NULL DEFAULT 1,"
                + "field_file_indx INT NOT NULL,"
                + "field_file_flag TINYINT NOT NULL,"
                + "orig_field_cond TEXT,"
                + "dest_field_cond TEXT,"
                + "created_at DATETIME NOT NULL,"
                + "updated_at DATETIME NOT NULL,"
                + "version INT NOT NULL DEFAULT 0,"
                + "UNIQUE (orig_trcd, field_rmove_name, field_file_indx),"
                + "UNIQUE (orig_trcd, field_file_indx))");
        jdbc.execute("CREATE TABLE dii_replay_conditional_rmove_operation ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "config_id BIGINT NOT NULL,"
                + "operation_type VARCHAR(16) NOT NULL,"
                + "orig_trcd VARCHAR(192),"
                + "field_rmove_name VARCHAR(256),"
                + "field_file_indx INT,"
                + "field_file_flag TINYINT,"
                + "orig_field_cond TEXT,"
                + "dest_field_cond TEXT,"
                + "new_orig_trcd VARCHAR(192),"
                + "new_field_rmove_name VARCHAR(256),"
                + "new_field_file_indx INT,"
                + "new_field_file_flag TINYINT,"
                + "new_orig_field_cond TEXT,"
                + "new_dest_field_cond TEXT,"
                + "operator_username VARCHAR(128),"
                + "operator_real_name VARCHAR(128),"
                + "operation_source VARCHAR(16) NOT NULL DEFAULT 'MANUAL',"
                + "created_at DATETIME NOT NULL)");

        jdbc.execute("CREATE TABLE dii_replay_error_code_ignore_config ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "service_code VARCHAR(192) NOT NULL,"
                + "old_resp_code VARCHAR(128),"
                + "new_resp_code VARCHAR(128),"
                + "enabled TINYINT NOT NULL DEFAULT 1,"
                + "created_at DATETIME NOT NULL,"
                + "updated_at DATETIME NOT NULL,"
                + "version INT NOT NULL DEFAULT 0,"
                + "UNIQUE (service_code, old_resp_code, new_resp_code))");
        jdbc.execute("CREATE TABLE dii_replay_error_code_ignore_config_operation ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "config_id BIGINT NOT NULL,"
                + "operation_type VARCHAR(16) NOT NULL,"
                + "service_code VARCHAR(192),"
                + "old_resp_code VARCHAR(128),"
                + "new_resp_code VARCHAR(128),"
                + "new_service_code VARCHAR(192),"
                + "new_old_resp_code VARCHAR(128),"
                + "new_new_resp_code VARCHAR(128),"
                + "operator_username VARCHAR(128),"
                + "operator_real_name VARCHAR(128),"
                + "operation_source VARCHAR(16) NOT NULL DEFAULT 'MANUAL',"
                + "created_at DATETIME NOT NULL)");

        jdbc.execute("CREATE TABLE dii_replay_sort_field ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "orig_trcd VARCHAR(192) NOT NULL,"
                + "orig_arry_name VARCHAR(256) NOT NULL,"
                + "orig_field_name VARCHAR(256) NOT NULL,"
                + "tran_mode TINYINT NOT NULL DEFAULT 1,"
                + "created_at DATETIME NOT NULL,"
                + "updated_at DATETIME NOT NULL,"
                + "version INT NOT NULL DEFAULT 0,"
                + "UNIQUE (orig_trcd, orig_arry_name, orig_field_name))");
        jdbc.execute("CREATE TABLE dii_replay_sort_field_operation ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                + "config_id BIGINT NOT NULL,"
                + "operation_type VARCHAR(16) NOT NULL,"
                + "orig_trcd VARCHAR(192),"
                + "orig_arry_name VARCHAR(256),"
                + "orig_field_name VARCHAR(256),"
                + "new_orig_trcd VARCHAR(192),"
                + "new_orig_arry_name VARCHAR(256),"
                + "new_orig_field_name VARCHAR(256),"
                + "operator_username VARCHAR(128),"
                + "operator_real_name VARCHAR(128),"
                + "operation_source VARCHAR(16) NOT NULL DEFAULT 'MANUAL',"
                + "created_at DATETIME NOT NULL)");
    }
}
