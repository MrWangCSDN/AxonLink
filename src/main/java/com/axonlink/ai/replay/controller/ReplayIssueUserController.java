package com.axonlink.ai.replay.controller;

import com.axonlink.ai.replay.dto.ReplayIssueUserOption;
import com.axonlink.ai.user.persistence.SysUserDao;
import com.axonlink.common.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Minimal active-user lookup used by the collaborator autocomplete. */
@RestController
@RequestMapping("/api/ai/parallel-replay/issues/users")
public class ReplayIssueUserController {
    private final SysUserDao userDao;

    public ReplayIssueUserController(SysUserDao userDao) {
        this.userDao = userDao;
    }

    @GetMapping
    public R<List<ReplayIssueUserOption>> search(@RequestParam(defaultValue = "") String keyword,
                                                 @RequestParam(defaultValue = "20") int limit) {
        List<ReplayIssueUserOption> options = userDao.searchByUsernameOrRealName(keyword, limit).stream()
                .map(user -> new ReplayIssueUserOption(user.getUsername(), user.getRealName()))
                .toList();
        return R.ok(options);
    }
}
