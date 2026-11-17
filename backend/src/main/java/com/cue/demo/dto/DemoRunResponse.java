package com.cue.demo.dto;

import java.util.List;

/**
 * 시연 실행 접수 결과 (HTTP 202).
 *
 * @param queued          평가 큐에 넣은 아이디어 id (진행은 {@code GET /api/ideas/{id}}의 evaluationStatus를 폴링)
 * @param skipped         건너뛴 아이디어와 이유: ALREADY_QUEUED, ALREADY_RUNNING, QUEUE_FULL
 * @param trendRefreshed  글로벌 트렌드 수집·요약 작업을 큐에 넣었는지 (force이거나 오늘 트렌드가 아직 없을 때 true; 끝나는 시점은 큐 순서상 이후 평가보다 앞선다)
 */
public record DemoRunResponse(List<Long> queued, List<Skipped> skipped, boolean trendRefreshed) {
    public record Skipped(Long ideaId, String reason) {}
}
