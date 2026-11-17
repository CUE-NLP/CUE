package com.cue.demo;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Configuration;

/**
 * 시연용 수동 실행 API 설정 (prefix {@code cue.demo}).
 *
 * @param enabled         false(기본)면 {@code POST /api/demo/evaluations/run}은 D001로 거절한다. 수동 호출은 Gemini 무료 한도를 소진시킬 수 있으니 시연 때만 켠다
 * @param cooldownSeconds 같은 사용자가 연달아 누르는 것을 막는 최소 간격(초). 안에 다시 누르면 D002
 */
@ConfigurationProperties(prefix = "cue.demo")
public record DemoProperties(@DefaultValue("false") boolean enabled, @DefaultValue("30") int cooldownSeconds) {

    @Configuration
    @EnableConfigurationProperties(DemoProperties.class)
    static class Registration {}
}
