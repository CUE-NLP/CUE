package com.cue.user.controller;

import com.cue.global.security.UserPrincipal;
import com.cue.user.dto.SignUpRequest;
import com.cue.user.dto.UserResponse;
import com.cue.user.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
public class UserController {
    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse signUp(@Valid @RequestBody SignUpRequest req) {
        return userService.signUp(req);
    }

    /** 로그인한 사용자 본인 정보 (온보딩 등에서 사용). */
    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal UserPrincipal principal) {
        return userService.get(principal.id());
    }
}
