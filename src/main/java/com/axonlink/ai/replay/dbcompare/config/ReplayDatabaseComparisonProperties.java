package com.axonlink.ai.replay.dbcompare.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Configuration
@ConfigurationProperties(prefix = "replay-database-comparison")
public class ReplayDatabaseComparisonProperties {

    private BaseDatasource baseDatasource = new BaseDatasource();
    private List<String> adminEmployeeNos = new ArrayList<>();
    private List<String> groupOptions = new ArrayList<>(List.of("公共组", "贷款组", "结算组", "存款组"));
    private boolean importEnabled = true;

    public BaseDatasource getBaseDatasource() {
        return baseDatasource;
    }

    public void setBaseDatasource(BaseDatasource baseDatasource) {
        this.baseDatasource = baseDatasource == null ? new BaseDatasource() : baseDatasource;
    }

    public List<String> getAdminEmployeeNos() {
        return adminEmployeeNos;
    }

    public void setAdminEmployeeNos(List<String> adminEmployeeNos) {
        this.adminEmployeeNos = adminEmployeeNos == null ? new ArrayList<>() : adminEmployeeNos;
    }

    public List<String> getGroupOptions() {
        return groupOptions;
    }

    public void setGroupOptions(List<String> groupOptions) {
        this.groupOptions = groupOptions == null ? new ArrayList<>() : groupOptions;
    }

    public boolean isImportEnabled() {
        return importEnabled;
    }

    public void setImportEnabled(boolean importEnabled) {
        this.importEnabled = importEnabled;
    }

    public static class BaseDatasource {
        private String url = "";
        private String username = "";
        private String password = "";
        private String driverClassName = "";
        private String schema = "";
        private int maximumPoolSize = 3;
        private long connectionTimeoutMs = 5000;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getDriverClassName() {
            return driverClassName;
        }

        public void setDriverClassName(String driverClassName) {
            this.driverClassName = driverClassName;
        }

        public String getSchema() {
            return schema;
        }

        public void setSchema(String schema) {
            this.schema = schema;
        }

        public int getMaximumPoolSize() {
            return maximumPoolSize;
        }

        public void setMaximumPoolSize(int maximumPoolSize) {
            this.maximumPoolSize = maximumPoolSize;
        }

        public long getConnectionTimeoutMs() {
            return connectionTimeoutMs;
        }

        public void setConnectionTimeoutMs(long connectionTimeoutMs) {
            this.connectionTimeoutMs = connectionTimeoutMs;
        }
    }
}
