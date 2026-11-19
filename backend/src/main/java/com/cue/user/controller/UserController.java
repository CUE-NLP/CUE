package com.cue.user.controller;

import com.cue.global.docs.ApiErrors;
import com.cue.global.docs.PublicApi;
import com.cue.global.error.ErrorCode;
import com.cue.global.response.CommonResponse;
import com.cue.global.security.UserPrincipal;
import com.cue.user.dto.SignUpRequest;
import com.cue.user.dto.UserResponse;
import com.cue.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import lombok.RequiredArgsConstructor;

@Tag(name = "사용자", description = "회원가입과 내 정보 조회")
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;

    @Operation(summary = "회원가입", description = "닉네임·이메일·비밀번호로 가입한다. 이메일은 중복될 수 없다. 액세스 토큰은 필요 없다.")
    @PublicApi
    @ApiErrors(ErrorCode.EMAIL_ALREADY_EXISTS)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CommonResponse<UserResponse> signUp(@Valid @RequestBody SignUpRequest req) {
        return CommonResponse.onSuccess(userService.signUp(req));
    }

    /** 로그인한 사용자 본인 정보 (온보딩 등에서 사용). */
    @Operation(summary = "내 정보 조회", description = "액세스 토큰의 사용자 본인 정보(ID·닉네임·이메일)를 조회한다.")
    @ApiErrors(ErrorCode.USER_NOT_FOUND)
    @GetMapping("/me")
    public CommonResponse<UserResponse> me(@AuthenticationPrincipal UserPrincipal principal) {
        return CommonResponse.onSuccess(userService.get(principal.id()));
    }
}
