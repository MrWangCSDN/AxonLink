package com.axonlink.ai.replay.reopen;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "dii.replay.issue-reopen")
public class ReplayReopenProperties {
    private boolean enabled;
    private List<String> adminEmpNos = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getAdminEmpNos() {
        return adminEmpNos;
    }

    public void setAdminEmpNos(List<String> value) {
        this.adminEmpNos = value == null ? new ArrayList<>() : new ArrayList<>(value);
    }
}
