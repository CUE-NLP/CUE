package com.cue.auth.controller;

import com.cue.auth.dto.LoginRequest;
import com.cue.auth.dto.RefreshRequest;
import com.cue.auth.dto.TokenResponse;
import com.cue.auth.service.AuthService;
import com.cue.global.docs.ApiErrors;
import com.cue.global.docs.PublicApi;
import com.cue.global.error.ErrorCode;
import com.cue.global.response.CommonResponse;
import com.cue.global.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import lombok.RequiredArgsConstructor;

@Tag(name = "인증", description = "로그인, 액세스 토큰 재발급(리프레시 토큰 회전)은 액세스 토큰 없이, 로그아웃은 액세스 토큰이 필요하다.")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;

    @Operation(summary = "로그인",
            description = "이메일·비밀번호로 로그인해 액세스 토큰(JWT)과 리프레시 토큰을 발급한다. "
                    + "이메일 존재 여부를 노출하지 않으려고 이메일 오류·비밀번호 오류 모두 `A007` 하나로 응답한다.")
    @PublicApi
    @ApiErrors(ErrorCode.INVALID_CREDENTIALS)
    @PostMapping("/login")
    public CommonResponse<TokenResponse> login(@Valid @RequestBody LoginRequest req) {
        return CommonResponse.onSuccess(authService.login(req));
    }

    @Operation(summary = "액세스 토큰 재발급",
            description = "액세스 토큰이 만료(`A004`)되면 리프레시 토큰으로 새 액세스·리프레시 토큰을 받는다. "
                    + "리프레시 토큰은 **1회용(회전)** 이라 사용한 토큰은 폐기되고, 응답의 새 값으로 교체해야 한다. "
                    + "이미 폐기된 토큰을 다시 보내면 탈취로 보고 그 사용자의 모든 리프레시 토큰을 폐기한다(`A010`). "
                    + "`A008`·`A009`·`A010`이면 재로그인한다. 액세스 토큰은 필요 없다.")
    @PublicApi
    @ApiErrors({ErrorCode.REFRESH_TOKEN_INVALID, ErrorCode.REFRESH_TOKEN_EXPIRED, ErrorCode.REFRESH_TOKEN_REUSED})
    @PostMapping("/refresh")
    public CommonResponse<TokenResponse> refresh(@Valid @RequestBody RefreshRequest req) {
        return CommonResponse.onSuccess(authService.refresh(req.refreshToken()));
    }

    @Operation(summary = "로그아웃",
            description = "**현재 액세스 토큰**(`Authorization` 헤더)을 즉시 무효화하고(이후 같은 토큰은 `A005`), "
                    + "요청 본문의 리프레시 토큰을 폐기한다(현재 기기만, 다른 기기의 로그인은 유지). "
                    + "리프레시 토큰이 없거나 이미 폐기됐거나 본인 것이 아니어도 성공(멱등)이며, 본인 것이 아닌 토큰은 폐기하지 않는다. "
                    + "액세스 토큰이 이미 만료됐다면(`A004`) 먼저 `/api/auth/refresh`로 재발급한 뒤 로그아웃한다. "
                    + "성공 후 클라이언트는 두 토큰을 모두 지운다.")
    @PostMapping("/logout")
    public CommonResponse<Void> logout(@AuthenticationPrincipal UserPrincipal principal, Authentication authentication,
                                       @Valid @RequestBody RefreshRequest req) {
        Jwt jwt = (Jwt) authentication.getDetails();
        authService.logout(principal.id(), jwt.getId(), jwt.getExpiresAt(), req.refreshToken());
        return CommonResponse.onSuccess();
    }
}
