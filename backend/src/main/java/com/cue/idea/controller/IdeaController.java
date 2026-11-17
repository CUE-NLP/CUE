package com.cue.idea.controller;

import com.cue.global.response.CommonResponse;
import com.cue.global.security.UserPrincipal;
import com.cue.idea.dto.CreateIdeaRequest;
import com.cue.idea.dto.EvaluateIdeaResponse;
import com.cue.idea.dto.EvaluationResponse;
import com.cue.idea.dto.IdeaHistoryResponse;
import com.cue.idea.dto.IdeaResponse;
import com.cue.idea.dto.UpdateIdeaRequest;
import com.cue.idea.dto.UpdateWeightsRequest;
import com.cue.idea.entity.IdeaSort;
import com.cue.idea.entity.IdeaStatus;
import com.cue.idea.service.IdeaEvaluationService;
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
    private final IdeaEvaluationService ideaEvaluationService;

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

    /** 아이디어 수정. 보낸 항목만 바꾸고(null은 유지, 빈 문자열은 비움), 모두 채워진 상태면 키워드를 다시 추출한다. */
    @PatchMapping("/{id}")
    public CommonResponse<IdeaResponse> update(@AuthenticationPrincipal UserPrincipal principal, @PathVariable Long id,
                                               @RequestBody UpdateIdeaRequest req) {
        return CommonResponse.onSuccess(ideaService.update(principal.id(), id, req));
    }

    /** 최신 점수를 확인했음을 기록한다 ("새로운 변화" 알림 해제). */
    @PostMapping("/{id}/check")
    public CommonResponse<IdeaResponse> check(@AuthenticationPrincipal UserPrincipal principal, @PathVariable Long id) {
        return CommonResponse.onSuccess(ideaService.markChecked(principal.id(), id));
    }

    /**
     * 평가를 비동기로 요청한다: 즉시 <b>202 Accepted</b>로 돌아오고(본문 code는 다른 성공 응답처럼 "200"), 평가는 서버 큐에서 돈다.
     * 본인 아이디어만(I002), 보류는 I004, Gemini 불가는 E001, 대기열이 가득 차면 E004를 요청 시점에 바로 돌려준다.
     * 이미 평가 대기·진행 중이면 에러 없이 현재 상태(QUEUED/RUNNING)를 돌려준다. 결과는 GET /api/ideas/{id}의 evaluationStatus를 폴링해 확인한다.
     */
    @PostMapping("/{id}/evaluate")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CommonResponse<EvaluateIdeaResponse> evaluate(@AuthenticationPrincipal UserPrincipal principal,
                                                         @PathVariable Long id) {
        return CommonResponse.onSuccess(ideaEvaluationService.request(principal.id(), id));
    }

    /** 점수 이력 차트용 총점 이력 (오래된 순). */
    @GetMapping("/{id}/history")
    public CommonResponse<List<IdeaHistoryResponse>> history(@AuthenticationPrincipal UserPrincipal principal,
                                                             @PathVariable Long id) {
        return CommonResponse.onSuccess(ideaService.history(principal.id(), id));
    }

    /** 항목별 평가 이력 (최신순): 변화량, 근거 요약, 출처. */
    @GetMapping("/{id}/evaluations")
    public CommonResponse<List<EvaluationResponse>> evaluations(@AuthenticationPrincipal UserPrincipal principal,
                                                                @PathVariable Long id) {
        return CommonResponse.onSuccess(ideaService.evaluationHistory(principal.id(), id));
    }

    /** 리더보드. status=ACTIVE(기본)는 평가 대상, HOLD는 보류 탭. sort=TOTAL(기본, 총점순) | RECENT_RISE(최근 상승순). */
    @GetMapping
    public CommonResponse<List<IdeaResponse>> leaderboard(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "ACTIVE") IdeaStatus status,
            @RequestParam(defaultValue = "TOTAL") IdeaSort sort) {
        return CommonResponse.onSuccess(ideaService.leaderboard(principal.id(), status, sort));
    }
}
