package com.cue.idea.controller;

import com.cue.global.docs.ApiErrors;
import com.cue.global.error.ErrorCode;
import com.cue.global.response.CommonResponse;
import com.cue.global.security.UserPrincipal;
import com.cue.idea.dto.CreateIdeaRequest;
import com.cue.idea.dto.EvaluationResponse;
import com.cue.idea.dto.IdeaHistoryResponse;
import com.cue.idea.dto.IdeaResponse;
import com.cue.idea.dto.UpdateIdeaRequest;
import com.cue.idea.dto.UpdateWeightsRequest;
import com.cue.idea.entity.IdeaSort;
import com.cue.idea.entity.IdeaStatus;
import com.cue.idea.service.IdeaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import lombok.RequiredArgsConstructor;

@Tag(name = "아이디어", description = "아이디어 저장·수정·조회, 리더보드, 가중치, 점수·평가 이력. 모두 액세스 토큰이 필요하며 본인의 아이디어만 접근할 수 있다(타인 아이디어는 `I002`).")
@RestController
@RequestMapping("/api/ideas")
@RequiredArgsConstructor
public class IdeaController {
    private final IdeaService ideaService;

    @Operation(summary = "아이디어 저장",
            description = "프론트가 구조화하고 사용자가 확인한 아이디어를 저장한다. 문제·타깃·해결·기대효과가 **모두 채워지면 ACTIVE**(AI로 종류·검색 키워드를 추출해 평가 대상이 됨), "
                    + "**하나라도 비면 HOLD**(보류, AI 호출·평가 없음)로 서버가 정한다. 점수는 첫 평가 전까지 0, 가중치는 25/25/25/25.")
    @ApiErrors(ErrorCode.USER_NOT_FOUND)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CommonResponse<IdeaResponse> create(@AuthenticationPrincipal UserPrincipal principal,
                                               @Valid @RequestBody CreateIdeaRequest req) {
        return CommonResponse.onSuccess(ideaService.create(principal.id(), req));
    }

    @Operation(summary = "아이디어 상세 조회",
            description = "상태·구조화 항목·총점·항목별 점수/가중치(현재·예약)·최근 변화량·`hasNewChange`를 반환한다.")
    @ApiErrors({ErrorCode.IDEA_NOT_FOUND, ErrorCode.IDEA_ACCESS_DENIED})
    @GetMapping("/{id}")
    public CommonResponse<IdeaResponse> get(@AuthenticationPrincipal UserPrincipal principal, @Parameter(description = "아이디어 ID", example = "1") @PathVariable Long id) {
        return CommonResponse.onSuccess(ideaService.get(principal.id(), id));
    }

    /** 항목별 가중치(%)를 변경한다. 합이 100이어야 하며 다음 평가 턴부터 적용된다. */
    @Operation(summary = "가중치 변경 예약",
            description = "4개 평가 항목의 가중치(%)를 모두 0~100 정수로 보내며 합은 정확히 100이어야 한다. "
                    + "현재 점수·순위에는 바로 반영되지 않고 `pendingWeightPercent`에 예약되어 다음 평가 턴부터 적용된다. "
                    + "예: `{\"weights\": {\"PROBLEM_IMPORTANCE\": 30, \"MARKET_DEMAND\": 30, \"MARKET_DIFFERENTIATION\": 20, \"TECH_FEASIBILITY\": 20}}`")
    @ApiErrors({ErrorCode.INVALID_WEIGHT, ErrorCode.IDEA_NOT_FOUND, ErrorCode.IDEA_ACCESS_DENIED})
    @PatchMapping("/{id}/weights")
    public CommonResponse<IdeaResponse> reserveWeights(@AuthenticationPrincipal UserPrincipal principal, @Parameter(description = "아이디어 ID", example = "1") @PathVariable Long id,
                                                       @Valid @RequestBody UpdateWeightsRequest req) {
        return CommonResponse.onSuccess(ideaService.reserveWeights(principal.id(), id, req));
    }

    /** 아이디어 수정. 보낸 항목만 바꾸고(null은 유지, 빈 문자열은 비움), 모두 채워진 상태면 키워드를 다시 추출한다. */
    @Operation(summary = "아이디어 수정",
            description = "보낸 항목만 수정한다(생략/null=유지, 빈 문자열=비움). 수정 뒤 4개 항목이 모두 채워져 있으면 ACTIVE가 되고 종류·키워드를 다시 추출하며, "
                    + "하나라도 비면 HOLD가 된다.")
    @ApiErrors({ErrorCode.IDEA_NOT_FOUND, ErrorCode.IDEA_ACCESS_DENIED})
    @PatchMapping("/{id}")
    public CommonResponse<IdeaResponse> update(@AuthenticationPrincipal UserPrincipal principal, @Parameter(description = "아이디어 ID", example = "1") @PathVariable Long id,
                                               @Valid @RequestBody UpdateIdeaRequest req) {
        return CommonResponse.onSuccess(ideaService.update(principal.id(), id, req));
    }

    /** 최신 점수를 확인했음을 기록한다 ("새로운 변화" 알림 해제). */
    @Operation(summary = "새 변화 확인 처리",
            description = "사용자가 최신 점수를 확인했음을 기록해 `hasNewChange`(새로운 변화 알림)를 해제한다. 이후 새 평가가 생기면 다시 true가 된다.")
    @ApiErrors({ErrorCode.IDEA_NOT_FOUND, ErrorCode.IDEA_ACCESS_DENIED})
    @PostMapping("/{id}/check")
    public CommonResponse<IdeaResponse> check(@AuthenticationPrincipal UserPrincipal principal, @Parameter(description = "아이디어 ID", example = "1") @PathVariable Long id) {
        return CommonResponse.onSuccess(ideaService.markChecked(principal.id(), id));
    }

    /** 점수 이력 차트용 총점 이력 (오래된 순). */
    @Operation(summary = "총점 이력 조회",
            description = "점수 이력 차트용. 평가로 점수가 바뀐 시점마다의 총점 스냅샷과 총점 변화량을 오래된 순으로 반환한다.")
    @ApiErrors({ErrorCode.IDEA_NOT_FOUND, ErrorCode.IDEA_ACCESS_DENIED})
    @GetMapping("/{id}/history")
    public CommonResponse<List<IdeaHistoryResponse>> history(@AuthenticationPrincipal UserPrincipal principal,
                                                             @Parameter(description = "아이디어 ID", example = "1") @PathVariable Long id) {
        return CommonResponse.onSuccess(ideaService.history(principal.id(), id));
    }

    /** 항목별 평가 이력 (최신순): 변화량, 근거 요약, 출처. */
    @Operation(summary = "항목별 평가 이력 조회",
            description = "점수가 바뀐 평가의 항목별 변화량·변경 후 점수·당시 가중치·근거 요약·출처 URL을 최신순으로 반환한다.")
    @ApiErrors({ErrorCode.IDEA_NOT_FOUND, ErrorCode.IDEA_ACCESS_DENIED})
    @GetMapping("/{id}/evaluations")
    public CommonResponse<List<EvaluationResponse>> evaluations(@AuthenticationPrincipal UserPrincipal principal,
                                                                @Parameter(description = "아이디어 ID", example = "1") @PathVariable Long id) {
        return CommonResponse.onSuccess(ideaService.evaluationHistory(principal.id(), id));
    }

    /** 리더보드. status=ACTIVE(기본)는 평가 대상, HOLD는 보류 탭. sort=TOTAL(기본, 총점순) | RECENT_RISE(최근 상승순). */
    @Operation(summary = "리더보드(내 아이디어 목록)",
            description = "로그인한 사용자의 아이디어를 상태별로 정렬해 반환한다. 기본은 평가 대상(ACTIVE) 총점순이며, 보류 탭은 `status=HOLD`.")
    @GetMapping
    public CommonResponse<List<IdeaResponse>> leaderboard(
            @AuthenticationPrincipal UserPrincipal principal,
            @Parameter(description = "조회할 상태. ACTIVE=평가 대상(기본), HOLD=보류 탭")
            @RequestParam(defaultValue = "ACTIVE") IdeaStatus status,
            @Parameter(description = "정렬. TOTAL=총점 높은 순(기본), RECENT_RISE=최근 평가의 총점 변화량 큰 순(평가 이력 없으면 뒤)")
            @RequestParam(defaultValue = "TOTAL") IdeaSort sort) {
        return CommonResponse.onSuccess(ideaService.leaderboard(principal.id(), status, sort));
    }
}
