package com.cue.source.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 외부 자료 API 설정 (prefix {@code cue.source}). 토큰·키는 환경변수로만 주입하고 커밋하지 않는다. 모두 선택 값. */
@ConfigurationProperties(prefix = "cue.source")
public record SourceApiProperties(
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("5s") Duration readTimeout,
        @DefaultValue Api github,
        @DefaultValue Api huggingFace,
        @DefaultValue Api stackExchange) {

    public record Api(@DefaultValue("") String baseUrl, @DefaultValue("") String token) {}
}
