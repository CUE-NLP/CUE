package com.cue.user.controller;

import com.cue.global.response.CommonResponse;
import com.cue.global.security.UserPrincipal;
import com.cue.user.dto.SignUpRequest;
import com.cue.user.dto.UserResponse;
import com.cue.user.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CommonResponse<UserResponse> signUp(@Valid @RequestBody SignUpRequest req) {
        return CommonResponse.onSuccess(userService.signUp(req));
    }

    /** 로그인한 사용자 본인 정보 (온보딩 등에서 사용). */
    @GetMapping("/me")
    public CommonResponse<UserResponse> me(@AuthenticationPrincipal UserPrincipal principal) {
        return CommonResponse.onSuccess(userService.get(principal.id()));
    }
}
