package com.cue.demo;

import com.cue.demo.dto.DemoRunRequest;
import com.cue.demo.dto.DemoRunResponse;
import com.cue.global.response.CommonResponse;
import com.cue.global.security.UserPrincipal;
import com.cue.global.docs.ApiErrors;
import com.cue.global.error.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "시연", description = "시연용 수동 실행 API. `DEMO_API_ENABLED=true`일 때만 동작하며 꺼져 있으면 `D001`. Gemini 무료 한도를 소진시킬 수 있다.")
/** 시연용 API. {@code cue.demo.enabled=true}일 때만 동작한다(꺼져 있으면 D001). 로그인 필수. */
@RestController
@RequestMapping("/api/demo")
@RequiredArgsConstructor
public class DemoController {
    private final DemoEvaluationService demo;

    /**
     * "지금 조회·평가": 글로벌 트렌드 수집과 호출한 사용자의 ACTIVE 아이디어 평가를 비동기 큐에 넣고 즉시 202를 돌려준다.
     * 진행은 {@code GET /api/ideas/{id}}의 evaluationStatus로 폴링한다. 본문은 선택이며 {@code {"force": true}}면 캐시·해시 규칙을 무시한다.
     */
    @Operation(summary = "지금 조회·평가 (시연용)",
            description = "화면 버튼으로 \"지금 조회·평가\"를 수동 실행한다. 글로벌 트렌드 수집(오늘 것이 없거나 `force=true`일 때)과 **호출한 사용자의 ACTIVE 아이디어 전부**의 평가를 서버 큐에 넣고 "
                    + "기다리지 않고 즉시 `202`를 돌려준다. 보류(HOLD)·다른 사용자의 아이디어는 건드리지 않고, 이미 대기·진행 중인 아이디어는 `skipped`로 건너뛴다(멱등). "
                    + "`force=true`면 트렌드 하루 1회·해시 캐시를 무시하고 다시 수집하며 각 아이디어의 자료를 다시 읽고 평가해 변화가 보이게 한다(호출이 많아 무료 한도를 빨리 소진한다). "
                    + "진행은 각 아이디어의 `GET /api/ideas/{id}`를 폴링해 `evaluationStatus`로 확인한다. 큐에 들어간 아이디어는 평가가 끝날 때까지 수정할 수 없다(`I005`). "
                    + "같은 사용자가 쿨다운(기본 30초) 안에 다시 누르면 `D002`.")
    @ApiErrors({ErrorCode.DEMO_API_DISABLED, ErrorCode.DEMO_COOLDOWN, ErrorCode.AI_NOT_CONFIGURED, ErrorCode.EVALUATION_QUEUE_FULL})
    @PostMapping("/evaluations/run")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CommonResponse<DemoRunResponse> run(@AuthenticationPrincipal UserPrincipal principal,
                                               @RequestBody(required = false) DemoRunRequest request) {
        boolean force = request != null && request.forceOrDefault();
        return CommonResponse.onSuccess(demo.run(principal.id(), force));
    }
}
