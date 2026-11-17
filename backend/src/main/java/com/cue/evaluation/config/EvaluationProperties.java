package com.cue.evaluation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 일일 재평가 설정 (prefix {@code cue.evaluation}).
 *
 * @param enabled 스케줄러 사용 여부 (테스트에서는 false)
 * @param cron    실행 시각 (Spring 6필드 cron, 기본 매일 03:00)
 * @param zone    cron 기준 시간대
 * @param delayMs 아이디어 사이 대기(ms). Gemini 무료 티어의 분당 호출 한도를 넘지 않게 한다.
 */
@ConfigurationProperties(prefix = "cue.evaluation")
public record EvaluationProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("0 0 3 * * *") String cron,
        @DefaultValue("Asia/Seoul") String zone,
        @DefaultValue("15000") long delayMs) {}
