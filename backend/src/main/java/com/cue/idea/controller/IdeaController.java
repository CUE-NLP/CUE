package com.cue.idea.controller;

import com.cue.global.response.CommonResponse;
import com.cue.global.security.UserPrincipal;
import com.cue.idea.dto.CreateIdeaRequest;
import com.cue.idea.dto.IdeaResponse;
import com.cue.idea.dto.UpdateWeightsRequest;
import com.cue.idea.service.IdeaService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/ideas")
@RequiredArgsConstructor
public class IdeaController {
    private final IdeaService ideaService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CommonResponse<IdeaResponse> create(@AuthenticationPrincipal UserPrincipal principal,
                                               @Valid @RequestBody CreateIdeaRequest req) {
        return CommonResponse.onSuccess(ideaService.create(principal.id(), req));
    }

    @GetMapping("/{id}")
    public CommonResponse<IdeaResponse> get(@AuthenticationPrincipal UserPrincipal principal, @PathVariable Long id) {
        return CommonResponse.onSuccess(ideaService.get(principal.id(), id));
    }

    /** 항목별 가중치(%)를 변경한다. 합이 100이어야 하며 다음 평가 턴부터 적용된다. */
    @PatchMapping("/{id}/weights")
    public CommonResponse<IdeaResponse> reserveWeights(@AuthenticationPrincipal UserPrincipal principal, @PathVariable Long id,
                                                       @Valid @RequestBody UpdateWeightsRequest req) {
        return CommonResponse.onSuccess(ideaService.reserveWeights(principal.id(), id, req));
    }

    @GetMapping
    public CommonResponse<List<IdeaResponse>> leaderboard(@AuthenticationPrincipal UserPrincipal principal) {
        return CommonResponse.onSuccess(ideaService.leaderboard(principal.id()));
    }
}
