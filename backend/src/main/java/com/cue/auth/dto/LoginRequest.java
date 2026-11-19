package com.cue.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

@Schema(description = "로그인 요청")
public record LoginRequest(
        @Schema(description = "가입한 이메일", example = "cue@example.com") @NotBlank @Email @Size(max = 255) String email,
        @Schema(description = "비밀번호", example = "password123") @NotBlank @Size(max = 72) String password) {}
