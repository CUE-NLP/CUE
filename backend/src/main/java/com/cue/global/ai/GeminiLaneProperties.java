package com.cue.global.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 인터랙티브 레인(구조화·키워드 추출) 설정 ({@code cue.gemini.interactive.*}).
 * model이 비어 있으면 {@code cue.gemini.model}을 쓴다. 모델을 다르게 두면 모델별 분당 한도가 따로 잡혀 배치가 사용자 호출의 한도를 먹지 않는다.
 *
 * @param model         인터랙티브 호출에 쓸 가벼운 모델 (비면 기본 모델)
 * @param minIntervalMs 호출 사이 최소 간격(ms)
 * @param maxWaitMs     속도 제한 때문에 기다릴 수 있는 최대 시간(ms). 넘으면 E006
 * @param maxRetries    429/503일 때 재시도 횟수
 * @param timeoutSeconds 인터랙티브 호출의 연결·읽기 타임아웃(초). 0이면 {@code cue.gemini.timeout-seconds}
 */
@ConfigurationProperties(prefix = "cue.gemini.interactive")
public record GeminiLaneProperties(String model, long minIntervalMs, long maxWaitMs, int maxRetries, int timeoutSeconds) {
    public GeminiLaneProperties {
        model = model == null ? "" : model.trim();
        if (minIntervalMs < 0) {
            minIntervalMs = 0;
        }
        if (maxWaitMs < 0) {
            maxWaitMs = 0;
        }
        if (maxRetries < 0) {
            maxRetries = 0;
        }
        if (timeoutSeconds < 0) {
            timeoutSeconds = 0;
        }
    }

    /** 설정이 없을 때(테스트 등)의 기본값: 간격·재시도 없음. */
    public static GeminiLaneProperties none() {
        return new GeminiLaneProperties("", 0, 0, 0, 0);
    }
}
