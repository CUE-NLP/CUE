package com.cue.evaluation.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 매일 정해진 시각(기본 03:00 Asia/Seoul)에 일일 재평가를 시작한다. {@code cue.evaluation.enabled=false}면 빈이 만들어지지 않는다. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "cue.evaluation", name = "enabled", havingValue = "true", matchIfMissing = true)
public class EvaluationScheduler {
    private final EvaluationBatchService batch;

    @Scheduled(cron = "${cue.evaluation.cron:0 0 3 * * *}", zone = "${cue.evaluation.zone:Asia/Seoul}")
    public void runDaily() {
        log.info("일일 재평가 시작");
        try {
            batch.runAll();
        } catch (RuntimeException e) {
            log.error("일일 재평가 배치가 중단되었습니다", e);
        }
    }
}
