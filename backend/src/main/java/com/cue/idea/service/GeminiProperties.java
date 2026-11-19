package com.cue.idea.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Gemini 연동 설정. apiKey는 환경변수(GEMINI_API_KEY)로만 주입한다 (커밋 금지). */
@ConfigurationProperties(prefix = "cue.gemini")
public record GeminiProperties(
        boolean enabled, String apiKey, String model, String baseUrl, int timeoutSeconds) {}
