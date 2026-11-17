package com.cue.auth.dto;

/** 로그인·재발급 응답. 기존 필드(accessToken, tokenType)는 유지하고 refreshToken을 추가했다. */
public record TokenResponse(String accessToken, String tokenType, String refreshToken) {
    public static TokenResponse bearer(String accessToken, String refreshToken) {
        return new TokenResponse(accessToken, "Bearer", refreshToken);
    }
}
