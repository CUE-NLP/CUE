package com.cue.user.dto;

import jakarta.validation.constraints.*;

public record SignUpRequest(
        @NotBlank @Size(max = 50) String nickname,
        @NotBlank @Email String email,
        @NotBlank @Size(min = 8, max = 72) String password) {}
