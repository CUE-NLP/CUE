package com.cue.global.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cue.claude-gateway")
public record ClaudeGatewayProperties(
        boolean enabled, String apiKey, String baseUrl, String model, int timeoutSeconds, int maxTokens) {}
