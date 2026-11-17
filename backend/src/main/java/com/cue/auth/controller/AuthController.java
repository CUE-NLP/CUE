package com.cue.auth.controller;

import com.cue.auth.dto.LoginRequest;
import com.cue.auth.dto.RefreshRequest;
import com.cue.auth.dto.TokenResponse;
import com.cue.auth.service.AuthService;
import com.cue.global.docs.ApiErrors;
import com.cue.global.docs.PublicApi;
import com.cue.global.error.ErrorCode;
import com.cue.global.response.CommonResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import lombok.RequiredArgsConstructor;

@Tag(name = "인증", description = "로그인, 액세스 토큰 재발급(리프레시 토큰 회전), 로그아웃. 세 API 모두 액세스 토큰 없이 호출한다.")
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
            description = "리프레시 토큰을 폐기한다. 없거나 이미 폐기된 토큰이어도 성공(멱등)으로 응답한다. "
                    + "리프레시 토큰을 가진 것이 곧 자격이므로 액세스 토큰 없이 호출할 수 있다(액세스 토큰이 이미 만료돼도 로그아웃 가능). "
                    + "액세스 토큰은 서버에서 무효화하지 않으므로 만료(기본 60분)까지 유효하다 — 클라이언트가 두 토큰을 모두 지운다.")
    @PublicApi
    @PostMapping("/logout")
    public CommonResponse<Void> logout(@Valid @RequestBody RefreshRequest req) {
        authService.logout(req.refreshToken());
        return CommonResponse.onSuccess();
    }
}
