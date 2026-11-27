package com.cue.global.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cue.openai-gateway")
public record OpenAiGatewayProperties(
        boolean enabled, String apiKey, String baseUrl, String model, int timeoutSeconds, int maxOutputTokens) {}
