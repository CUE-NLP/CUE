package com.cue.auth.controller;

import com.cue.auth.dto.LoginRequest;
import com.cue.auth.dto.TokenResponse;
import com.cue.auth.service.AuthService;
import com.cue.global.response.CommonResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;

    @PostMapping("/login")
    public CommonResponse<TokenResponse> login(@Valid @RequestBody LoginRequest req) {
        return CommonResponse.onSuccess(authService.login(req));
    }
}
