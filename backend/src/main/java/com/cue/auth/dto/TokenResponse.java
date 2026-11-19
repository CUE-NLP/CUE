package com.cue.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 로그인·재발급 응답. 기존 필드(accessToken, tokenType)는 유지하고 refreshToken을 추가했다. */
@Schema(description = "로그인·재발급 응답 (토큰 한 쌍)")
public record TokenResponse(
        @Schema(description = "API 호출에 쓰는 JWT 액세스 토큰. `Authorization: Bearer {값}`. 기본 60분", example = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.sig")
        String accessToken,
        @Schema(description = "토큰 종류. 항상 Bearer", example = "Bearer") String tokenType,
        @Schema(description = "액세스 토큰 재발급용 불투명 토큰. 1회용(회전)이라 재발급 때마다 응답의 새 값으로 교체해야 한다. 기본 14일", example = "k3Jx9Qm0v1b7d2n8R5tYw4uZ6aLhPcEoS1gFiXyVrBA")
        String refreshToken) {
    public static TokenResponse bearer(String accessToken, String refreshToken) {
        return new TokenResponse(accessToken, "Bearer", refreshToken);
    }
}
