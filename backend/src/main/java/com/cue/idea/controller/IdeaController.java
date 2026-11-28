package com.cue.idea.controller;

import com.cue.global.docs.ApiErrors;
import com.cue.global.error.ErrorCode;
import com.cue.global.response.CommonResponse;
import com.cue.global.response.ListData;
import com.cue.global.security.UserPrincipal;
import com.cue.idea.dto.CreateIdeaRequest;
import com.cue.idea.dto.EvaluateIdeaResponse;
import com.cue.idea.dto.EvaluationResponse;
import com.cue.idea.dto.IdeaHistoryResponse;
import com.cue.idea.dto.IdeaResponse;
import com.cue.idea.dto.StructureIdeaRequest;
import com.cue.idea.dto.StructureIdeaResponse;
import com.cue.idea.dto.UpdateIdeaRequest;
import com.cue.idea.dto.UpdateWeightsRequest;
import com.cue.idea.entity.IdeaSort;
import com.cue.idea.entity.IdeaStatus;
import com.cue.idea.service.IdeaEvaluationService;
import com.cue.idea.service.IdeaService;
import com.cue.idea.service.IdeaStructuringService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import lombok.RequiredArgsConstructor;

@Tag(name = "아이디어", description = "아이디어 저장·수정·삭제·조회, 리더보드, 가중치, 점수·평가 이력. 모두 액세스 토큰이 필요하며 본인의 아이디어만 접근할 수 있다(타인 아이디어는 `I002`).")
@RestController
@RequestMapping("/api/ideas")
@RequiredArgsConstructor
public class IdeaController {
    private final IdeaService ideaService;
    private final IdeaEvaluationService ideaEvaluationService;
    private final IdeaStructuringService structuringService;

    @Operation(summary = "아이디어 구조화(미리보기)",
            description = "자유롭게 쓴 원문(최대 5000자)을 AI로 제목과 타깃·문제·해결·기대효과로 정리해 돌려준다. **저장하지 않고 원문도 남기지 않는다.** "
                    + "사용자가 결과를 확인·수정한 뒤 `POST /api/ideas`로 저장한다. "
                    + "원문에 근거가 없는 항목은 AI가 채우지 않고 빈 문자열로 두며, 하나라도 비면 `complete=false`(그대로 저장하면 HOLD)이고 `missingFields`에 비어 있는 항목이 담긴다. "
                    + "`complete=true`이면 IT 세부 종류(`itCategory`)와 영어 키워드 후보(`keywords`)도 함께 담기고, **내용을 고치지 않고 저장하면 서버가 이 결과를 다시 써서 저장 때 AI를 한 번 덜 호출한다**(고치면 평소처럼 다시 추출). "
                    + "같은 원문을 다시 보내면 AI 호출 없이 직전 결과를 돌려주고, 같은 사용자는 3초(설정값) 안에 새 원문을 다시 보낼 수 없다(`I006`). "
                    + "AI가 바쁘거나 실패하면(`E006`·`E001`·`E002`·`G002`) 프론트는 사용자가 4칸을 직접 채워 저장하게 안내한다. "
                    + "외부 AI 호출이 실패한 응답(`E006`·`E002`·`G002`)의 `data`에는 `{location, api, reason}`(에러가 난 위치, 외부 API, 원인)이 담긴다.")
    @ApiErrors({ErrorCode.AI_NOT_CONFIGURED, ErrorCode.AI_RESPONSE_INVALID, ErrorCode.AI_BUSY,
            ErrorCode.STRUCTURING_TOO_FAST, ErrorCode.EXTERNAL_SERVER_ERROR})
    @PostMapping("/structure")
    public CommonResponse<StructureIdeaResponse> structure(@AuthenticationPrincipal UserPrincipal principal,
                                                           @Valid @RequestBody StructureIdeaRequest req) {
        return CommonResponse.onSuccess(structuringService.structure(principal.id(), req.text()));
    }

    @Operation(summary = "아이디어 저장",
            description = "사용자가 확인한 제목(선택, 최대 60자)과 구조화 항목을 저장한다. 문제·타깃·해결·기대효과가 **모두 채워지면 ACTIVE**(평가 대상이 되고 외부 자료 검색 설정을 만든다), "
                    + "**하나라도 비면 HOLD**(보류, AI 호출·평가 없음)로 서버가 정한다. "
                    + "**키워드(`keywords`, 최대 2개·각 1~50자)를 보내면 AI 추출 없이 그 값을 쓰고(`keywordOrigin=USER`), 비어 있을 때만 AI가 추출한다(`keywordOrigin=AI`).** "
                    + "IT 세부 종류(`itCategory`: AI_ML·DEV_TOOLS_INFRA·WEB_APP_SERVICE·DATA_ANALYTICS)는 키워드와 무관하게 항상 AI가 판단하고, 대분류 `type`은 항상 IT다. "
                    + "점수는 첫 평가 전까지 0, 가중치는 5개 기준(문제 중요성·시장 수요·시장 차별성·기술적 실현 가능성·트렌디함) 각 20%. "
                    + "**ACTIVE로 저장되면 서버가 커밋 뒤 첫 평가를 비동기로 자동 시작한다**(`evaluationStatus`가 QUEUED→RUNNING→IDLE; 폴링은 `GET /api/ideas/{id}`). 키워드가 3개 이상이거나 빈 문자열·50자 초과면 `G004`.")
    @ApiErrors(ErrorCode.USER_NOT_FOUND)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CommonResponse<IdeaResponse> create(@AuthenticationPrincipal UserPrincipal principal,
                                               @Valid @RequestBody CreateIdeaRequest req) {
        return CommonResponse.onSuccess(ideaService.create(principal.id(), req));
    }

    @Operation(summary = "아이디어 상세 조회",
            description = "제목·상태·구조화 항목·총점·항목별 점수/가중치(현재·예약)·최근 변화량·`hasNewChange`를 반환한다. "
                    + "평가 진행 상황 폴링용으로 `evaluationStatus`(IDLE/QUEUED/RUNNING/FAILED)와 실패 시 `lastEvaluationErrorCode`를 함께 돌려준다.")
    @ApiErrors({ErrorCode.IDEA_NOT_FOUND, ErrorCode.IDEA_ACCESS_DENIED})
    @GetMapping("/{id}")
    public CommonResponse<IdeaResponse> get(@AuthenticationPrincipal UserPrincipal principal, @Parameter(description = "아이디어 ID", example = "1") @PathVariable Long id) {
        return CommonResponse.onSuccess(ideaService.get(principal.id(), id));
    }

    /** 항목별 가중치(%)를 변경한다. 합이 100이어야 하며 다음 평가 턴부터 적용된다. */
    @Operation(summary = "가중치 변경 예약",
            description = "5개 평가 항목(TREND 포함)의 가중치(%)를 모두 0~100 정수로 보내며 합은 정확히 100이어야 한다. 4개짜리 예전 형식은 `I003`. "
                    + "현재 점수·순위에는 바로 반영되지 않고 `pendingWeightPercent`에 예약되어 다음 평가 턴부터 적용된다(그래서 평가 중에도 호출할 수 있다). "
                    + "예: `{\"weights\": {\"PROBLEM_IMPORTANCE\": 30, \"MARKET_DEMAND\": 25, \"MARKET_DIFFERENTIATION\": 20, \"TECH_FEASIBILITY\": 15, \"TREND\": 10}}`")
    @ApiErrors({ErrorCode.INVALID_WEIGHT, ErrorCode.IDEA_NOT_FOUND, ErrorCode.IDEA_ACCESS_DENIED})
    @PatchMapping("/{id}/weights")
    public CommonResponse<IdeaResponse> reserveWeights(@AuthenticationPrincipal UserPrincipal principal, @Parameter(description = "아이디어 ID", example = "1") @PathVariable Long id,
                                                       @Valid @RequestBody UpdateWeightsRequest req) {
        return CommonResponse.onSuccess(ideaService.reserveWeights(principal.id(), id, req));
    }

    @Operation(summary = "아이디어 수정",
            description = "보낸 항목만 수정한다(생략/null=유지). 제목만 수정하면 Gemini 호출·자료 수집 설정 변경·재평가 없이 제목을 저장한다. "
                    + "구조화 항목은 빈 문자열로 비울 수 있고, 4개 항목이 모두 채워지면 ACTIVE, 하나라도 비면 HOLD가 된다. "
                    + "키워드: `keywords` 생략=유지, **빈 배열=사용자 키워드를 지우고 AI가 다시 추출**, "
                    + "1개 이상=그 값으로 교체(AI 추출 없음, `keywordOrigin=USER`). 키워드 검증 실패(3개 이상·빈 문자열·50자 초과)는 `G004`. "
                    + "내용이나 검색 키워드가 실제로 바뀐 ACTIVE 아이디어는 자료 수집 설정을 갱신하고, 자동 평가 설정이 켜져 있으면 커밋 뒤 **첫 평가를 다시 시작**한다. "
                    + "**평가가 대기(QUEUED)·진행(RUNNING) 중이면 수정할 수 없고 `I005`(409)**를 돌려준다(IDLE·FAILED면 가능).")
    @ApiErrors({ErrorCode.IDEA_NOT_FOUND, ErrorCode.IDEA_ACCESS_DENIED, ErrorCode.IDEA_EVALUATING})
    @PatchMapping("/{id}")
    public CommonResponse<IdeaResponse> update(@AuthenticationPrincipal UserPrincipal principal, @Parameter(description = "아이디어 ID", example = "1") @PathVariable Long id,
                                               @Valid @RequestBody UpdateIdeaRequest req) {
        return CommonResponse.onSuccess(ideaService.update(principal.id(), id, req));
    }

    /** 아이디어와 그 평가 데이터를 삭제한다. 본인 것만(I002), 평가 대기·진행 중이면 I005. */
    @Operation(summary = "아이디어 삭제",
            description = "아이디어와 그 점수·평가 이력·근거 자료·총점 이력·수집 설정을 **영구 삭제**한다(되돌릴 수 없음). 성공 응답의 `data`는 null이다. "
                    + "본인 아이디어만(`I002`), 없거나 이미 삭제했으면 `I001`. 평가가 대기·진행 중이면 `I005`로 거절하므로 끝난 뒤 다시 요청한다(보류·실패 상태는 삭제 가능).")
    @ApiErrors({ErrorCode.IDEA_NOT_FOUND, ErrorCode.IDEA_ACCESS_DENIED, ErrorCode.IDEA_EVALUATING})
    @DeleteMapping("/{id}")
    public CommonResponse<Void> delete(@AuthenticationPrincipal UserPrincipal principal,
                                       @Parameter(description = "아이디어 ID", example = "1") @PathVariable Long id) {
        ideaService.delete(principal.id(), id);
        return CommonResponse.onSuccess();
    }

    /** 최신 점수를 확인했음을 기록한다 ("새로운 변화" 알림 해제). */
    @Operation(summary = "새 변화 확인 처리",
            description = "사용자가 최신 점수를 확인했음을 기록해 `hasNewChange`(새로운 변화 알림)를 해제한다. 이후 새 평가가 생기면 다시 true가 된다.")
    @ApiErrors({ErrorCode.IDEA_NOT_FOUND, ErrorCode.IDEA_ACCESS_DENIED})
    @PostMapping("/{id}/check")
    public CommonResponse<IdeaResponse> check(@AuthenticationPrincipal UserPrincipal principal, @Parameter(description = "아이디어 ID", example = "1") @PathVariable Long id) {
        return CommonResponse.onSuccess(ideaService.markChecked(principal.id(), id));
    }

    /**
     * 평가를 비동기로 요청한다: 즉시 <b>202 Accepted</b>로 돌아오고(본문 code는 다른 성공 응답처럼 "200"), 평가는 서버 큐에서 돈다.
     * 본인 아이디어만(I002), 보류는 I004, 오늘 이미 평가했으면 E007, Gemini 불가는 E001, 대기열이 가득 차면 E004를 요청 시점에 바로 돌려준다.
     * 이미 평가 대기·진행 중이면 에러 없이 현재 상태(QUEUED/RUNNING)를 돌려준다. 결과는 GET /api/ideas/{id}의 evaluationStatus를 폴링해 확인한다.
     */
    @Operation(summary = "평가 비동기 요청",
            description = "이 아이디어의 평가(키워드 근거 4개 기준 + 글로벌 트렌드 TREND)를 서버 큐에 넣고 **기다리지 않고 즉시 `202 Accepted`**를 돌려준다"
                    + "(본문 `code`는 다른 성공 응답처럼 `\"200\"`). `data.status`는 방금 큐에 넣었으면 `QUEUED`, 이미 대기·진행 중이면 에러 없이 현재 상태(`QUEUED`/`RUNNING`)를 돌려준다(멱등). "
                    + "**평가는 아이디어마다 하루에 한 번**이라 오늘 이미 평가했으면 `E007`이다(내용·키워드를 수정하면 같은 날 다시 가능, 실패한 평가는 다시 요청 가능). "
                    + "본인 아이디어만(`I002`), 없음 `I001`, 보류(HOLD) `I004`, Gemini 사용 불가 `E001`, 대기열이 가득 차면 `E004`는 요청 시점에 바로 반환한다. "
                    + "평가는 서버에서 한 번에 하나씩 순서대로 돈다. 결과는 `GET /api/ideas/{id}`를 몇 초 간격으로 폴링해 `evaluationStatus`가 `IDLE`이면 갱신된 점수·`hasNewChange`로, `FAILED`면 `lastEvaluationErrorCode`와 `lastEvaluationError`(위치·외부 API·원인)로 확인한다. "
                    + "평가가 끝날 때까지 이 아이디어는 수정할 수 없다(`PATCH`가 `I005`).")
    @ApiErrors({ErrorCode.IDEA_NOT_FOUND, ErrorCode.IDEA_ACCESS_DENIED, ErrorCode.IDEA_NOT_ACTIVE,
            ErrorCode.EVALUATION_ALREADY_DONE_TODAY, ErrorCode.AI_NOT_CONFIGURED, ErrorCode.EVALUATION_QUEUE_FULL})
    @PostMapping("/{id}/evaluate")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CommonResponse<EvaluateIdeaResponse> evaluate(@AuthenticationPrincipal UserPrincipal principal,
                                                         @Parameter(description = "아이디어 ID", example = "1") @PathVariable Long id) {
        return CommonResponse.onSuccess(ideaEvaluationService.request(principal.id(), id));
    }

    /** 점수 이력 차트용 총점 이력 (오래된 순). */
    @Operation(summary = "총점 이력 조회",
            description = "점수 이력 차트용. 평가로 점수가 바뀐 시점마다의 총점 스냅샷과 총점 변화량을 오래된 순으로 반환한다.")
    @ApiErrors({ErrorCode.IDEA_NOT_FOUND, ErrorCode.IDEA_ACCESS_DENIED})
    @GetMapping("/{id}/history")
    public CommonResponse<ListData<IdeaHistoryResponse>> history(@AuthenticationPrincipal UserPrincipal principal,
                                                             @Parameter(description = "아이디어 ID", example = "1") @PathVariable Long id) {
        return CommonResponse.onSuccessList(ideaService.history(principal.id(), id));
    }

    /** 항목별 평가 이력 (최신순): 변화량, 근거 요약, 출처. */
    @Operation(summary = "항목별 평가 이력 조회",
            description = "점수가 바뀐 평가의 항목별 변화량·변경 후 점수·당시 가중치·근거 요약·출처 URL을 최신순으로 반환한다.")
    @ApiErrors({ErrorCode.IDEA_NOT_FOUND, ErrorCode.IDEA_ACCESS_DENIED})
    @GetMapping("/{id}/evaluations")
    public CommonResponse<ListData<EvaluationResponse>> evaluations(@AuthenticationPrincipal UserPrincipal principal,
                                                                @Parameter(description = "아이디어 ID", example = "1") @PathVariable Long id) {
        return CommonResponse.onSuccessList(ideaService.evaluationHistory(principal.id(), id));
    }

    /** 리더보드. status=ACTIVE(기본)는 평가 대상, HOLD는 보류 탭. sort=TOTAL(기본, 총점순) | RECENT_RISE(최근 상승순). */
    @Operation(summary = "리더보드(내 아이디어 목록)",
            description = "로그인한 사용자의 아이디어를 상태별로 정렬해 반환한다. 기본은 평가 대상(ACTIVE) 총점순이며, 보류 탭은 `status=HOLD`.")
    @GetMapping
    public CommonResponse<ListData<IdeaResponse>> leaderboard(
            @AuthenticationPrincipal UserPrincipal principal,
            @Parameter(description = "조회할 상태. ACTIVE=평가 대상(기본), HOLD=보류 탭")
            @RequestParam(defaultValue = "ACTIVE") IdeaStatus status,
            @Parameter(description = "정렬. TOTAL=총점 높은 순(기본), RECENT_RISE=최근 평가의 총점 변화량 큰 순(평가 이력 없으면 뒤)")
            @RequestParam(defaultValue = "TOTAL") IdeaSort sort) {
        return CommonResponse.onSuccessList(ideaService.leaderboard(principal.id(), status, sort));
    }
}
