package com.cue.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

@Schema(description = "회원가입 요청")
public record SignUpRequest(
        @Schema(description = "닉네임 (최대 50자)", example = "큐") @NotBlank @Size(max = 50) String nickname,
        @Schema(description = "이메일 (로그인 ID, 중복 불가)", example = "cue@example.com") @NotBlank @Email @Size(max = 255) String email,
        @Schema(description = "비밀번호 (8~72자)", example = "password123") @NotBlank @Size(min = 8, max = 72) String password) {}
