package com.cue.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 재발급·로그아웃 요청 바디. */
@Schema(description = "리프레시 토큰 요청 (재발급·로그아웃 공용)")
public record RefreshRequest(
        @Schema(description = "로그인/재발급 응답으로 받은 가장 최근의 리프레시 토큰", example = "k3Jx9Qm0v1b7d2n8R5tYw4uZ6aLhPcEoS1gFiXyVrBA")
        @NotBlank @Size(max = 200) String refreshToken) {}
