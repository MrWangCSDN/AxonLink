package com.axonlink.ai.replay.controller;

import com.axonlink.ai.replay.service.ReplayConfigPersonResolver;
import com.axonlink.common.R;
import com.axonlink.security.UserPrincipalResolver;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 忽略清单筛选用「领域」选项接口：领域取自全量交易人员清单。 */
@RestController
@RequestMapping("/api/ai/parallel-replay/config/domains")
public class ReplayConfigDomainController extends AbstractReplayConfigController {

    private final ReplayConfigPersonResolver personResolver;

    public ReplayConfigDomainController(ReplayConfigPersonResolver personResolver,
                                        UserPrincipalResolver userResolver) {
        super(userResolver);
        this.personResolver = personResolver;
    }

    @GetMapping
    public R<List<String>> list() {
        return R.ok(personResolver.listDomains());
    }
}
