package com.cue.idea.controller;

import com.cue.global.security.UserPrincipal;
import com.cue.idea.dto.CreateIdeaRequest;
import com.cue.idea.dto.IdeaResponse;
import com.cue.idea.service.IdeaService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ideas")
public class IdeaController {
    private final IdeaService ideaService;

    public IdeaController(IdeaService ideaService) {
        this.ideaService = ideaService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public IdeaResponse create(@AuthenticationPrincipal UserPrincipal principal,
                               @Valid @RequestBody CreateIdeaRequest req) {
        return ideaService.create(principal.id(), req);
    }

    @GetMapping("/{id}")
    public IdeaResponse get(@AuthenticationPrincipal UserPrincipal principal, @PathVariable Long id) {
        return ideaService.get(principal.id(), id);
    }

    @GetMapping
    public List<IdeaResponse> leaderboard(@AuthenticationPrincipal UserPrincipal principal) {
        return ideaService.leaderboard(principal.id());
    }
}
