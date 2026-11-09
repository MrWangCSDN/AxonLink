package com.axonlink.ai.replay.reopen;

import com.axonlink.ai.replay.dto.ReplayIssueOperator;
import com.axonlink.ai.replay.persistence.ReplayTransactionPersonDao;
import com.axonlink.ai.user.entity.SysUser;
import com.axonlink.ai.user.persistence.SysUserDao;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.axonlink.ai.replay.reopen.ReplayReopenDtos.*;

/** Resolves current database identities; token display names never grant capabilities. */
@Service
public class ReplayReopenPermissionService {
    private final ReplayReopenProperties properties;
    private final SysUserDao users;
    private final ReplayReopenWorkflowDao store;
    private final ReplayTransactionPersonDao transactionPersons;

    public ReplayReopenPermissionService(ReplayReopenProperties properties, SysUserDao userDao,
                                        ReplayReopenWorkflowDao store, ReplayTransactionPersonDao transactionPersons) {
        this.properties=properties; this.users=userDao; this.store=store; this.transactionPersons=transactionPersons;
    }

    public SysUser active(ReplayIssueOperator operator) {
        if (operator == null || operator.username() == null || operator.username().isBlank()) return null;
        SysUser user = users.findActiveByUsername(operator.username());
        return user != null && operator.username().equals(user.getUsername()) ? user : null;
    }
    public SysUser requireActive(ReplayIssueOperator operator) {
        SysUser user = active(operator);
        if (user == null) throw ReplayReopenException.unauthenticated();
        return user;
    }
    public boolean canManage(ReplayIssueOperator operator) { return configuredAdmin(active(operator)); }
    public SysUser requireAdmin(ReplayIssueOperator operator) {
        SysUser user = requireActive(operator);
        if (!configuredAdmin(user)) throw ReplayReopenException.forbidden("仅配置的权限管理员可操作");
        return user;
    }
    private boolean configuredAdmin(SysUser user) {
        return user != null && user.getEmpNo() != null && !user.getEmpNo().isBlank()
                && properties.getAdminEmpNos().contains(user.getEmpNo());
    }

    public boolean isLeader(String domain, ReplayIssueOperator operator) {
        SysUser user = active(operator);
        Policy policy = store.findPolicy(domain,false);
        return authorized(policy,user);
    }
    public SysUser requireLeader(String domain, ReplayIssueOperator operator) {
        SysUser user = requireActive(operator);
        if (!authorized(store.findPolicy(domain,true),user)) throw ReplayReopenException.forbidden("无该领域调整审批权限");
        return user;
    }
    public SysUser primaryLeader(String domain) {
        Policy policy = store.findPolicy(domain,true);
        SysUser user = policy == null ? null : exactActive(policy.primaryLeaderUsername());
        if (!authorized(policy,user)) throw ReplayReopenException.conflict("该领域尚未配置有效的主审批领导");
        return user;
    }
    private static boolean authorized(Policy policy,SysUser user) {
        return policy != null && user != null && policy.leaderUsernames().contains(user.getUsername());
    }

    public SysUser requireDeveloper(String username) {
        SysUser user = exactActive(username);
        if (user == null || !developerUsernames().contains(username)) throw ReplayReopenException.invalid("请选择当前有效的开发负责人账号");
        return user;
    }
    private SysUser exactActive(String username) {
        if (username == null || username.isBlank()) return null;
        SysUser user = users.findActiveByUsername(username);
        return user != null && username.equals(user.getUsername()) ? user : null;
    }

    public List<UserOption> developers(String keyword,int limit) {
        List<String> identities = new ArrayList<>(developerUsernames());
        List<SysUser> candidates = new ArrayList<>();
        // Batch by actual mapping identities first; a generic user LIMIT would omit valid developers.
        for (int start=0;start<identities.size();start+=200) {
            List<String> batch=identities.subList(start,Math.min(identities.size(),start+200));
            Set<String> exact=Set.copyOf(batch);
            users.findByUsernamesOrEmpNos(batch).stream()
                    .filter(u -> Integer.valueOf(1).equals(u.getStatus()) && exact.contains(u.getUsername()))
                    .forEach(candidates::add);
        }
        String query = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        return candidates.stream()
                .filter(user -> user.getUsername().toLowerCase(Locale.ROOT).contains(query)
                        || (user.getRealName()!=null && user.getRealName().toLowerCase(Locale.ROOT).contains(query)))
                .sorted(Comparator.comparing(SysUser::getRealName,Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(SysUser::getUsername))
                .limit(Math.min(50,Math.max(1,limit))).map(this::option).toList();
    }
    private Set<String> developerUsernames() {
        Set<String> names = new LinkedHashSet<>();
        transactionPersons.listAll(null).forEach(row -> {
            if (row.developerUsernames()!=null) Arrays.stream(row.developerUsernames().split("[、,，;；]"))
                    .map(String::trim).filter(value -> !value.isEmpty()).forEach(names::add);
        });
        return names;
    }
    public UserOption option(SysUser user) {
        if (user == null) return null;
        String name = user.getRealName();
        return new UserOption(user.getUsername(),name,name == null || name.isBlank() ? user.getUsername() : name+"("+user.getUsername()+")");
    }
}
