package com.cue.demo;

import com.cue.demo.dto.DemoRunRequest;
import com.cue.demo.dto.DemoRunResponse;
import com.cue.global.response.CommonResponse;
import com.cue.global.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

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
    @PostMapping("/evaluations/run")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CommonResponse<DemoRunResponse> run(@AuthenticationPrincipal UserPrincipal principal,
                                               @RequestBody(required = false) DemoRunRequest request) {
        boolean force = request != null && request.forceOrDefault();
        return CommonResponse.onSuccess(demo.run(principal.id(), force));
    }
}
