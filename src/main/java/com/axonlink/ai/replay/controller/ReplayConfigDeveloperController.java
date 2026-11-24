package com.axonlink.ai.replay.controller;

import com.axonlink.ai.replay.service.ReplayConfigPersonResolver;
import com.axonlink.common.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/ai/parallel-replay/config/developers")
public class ReplayConfigDeveloperController {
    private final ReplayConfigPersonResolver personResolver;

    public ReplayConfigDeveloperController(ReplayConfigPersonResolver personResolver) {
        this.personResolver = personResolver;
    }

    @GetMapping
    public R<List<String>> list() {
        return R.ok(personResolver.listDevelopers());
    }
}
