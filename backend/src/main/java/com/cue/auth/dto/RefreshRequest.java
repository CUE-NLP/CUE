package com.cue.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** 재발급·로그아웃 요청 바디. */
public record RefreshRequest(@NotBlank String refreshToken) {}
