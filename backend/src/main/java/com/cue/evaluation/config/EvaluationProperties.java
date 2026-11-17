package com.cue.evaluation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 일일 재평가 설정 (prefix {@code cue.evaluation}).
 *
 * @param enabled 스케줄러 사용 여부 (테스트에서는 false)
 * @param cron    실행 시각 (Spring 6필드 cron, 기본 매일 03:00)
 * @param zone    cron 기준 시간대
 * @param autoOnSave 아이디어가 ACTIVE로 저장·수정(내용 변경)되면 첫 평가를 자동으로 큐에 넣을지 (테스트에서는 false)
 * @param queueCapacity 평가 대기열 상한. 가득 차면 새 요청은 E004로 거절한다 (평가는 한 번에 하나씩만 실행)
 * @param shutdownWaitSeconds 앱 종료 시 진행 중인 평가를 기다리는 시간(초). 넘으면 인터럽트하고 남은 상태는 다음 부팅에서 정리한다
 * @param delayMs 아이디어 사이 대기(ms). Gemini 무료 티어의 분당 호출 한도를 넘지 않게 한다.
 */
@ConfigurationProperties(prefix = "cue.evaluation")
public record EvaluationProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("0 0 3 * * *") String cron,
        @DefaultValue("Asia/Seoul") String zone,
        @DefaultValue("15000") long delayMs,
        @DefaultValue("true") boolean autoOnSave,
        @DefaultValue("50") int queueCapacity,
        @DefaultValue("30") int shutdownWaitSeconds) {}
