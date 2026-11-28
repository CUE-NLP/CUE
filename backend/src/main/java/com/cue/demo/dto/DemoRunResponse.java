package com.cue.demo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 시연 실행 접수 결과 (HTTP 202).
 *
 * @param queued          평가 큐에 넣은 아이디어 id (진행은 {@code GET /api/ideas/{id}}의 evaluationStatus를 폴링)
 * @param skipped         건너뛴 아이디어와 이유: ALREADY_QUEUED, ALREADY_RUNNING, ALREADY_EVALUATED_TODAY, COOLDOWN, QUEUE_FULL
 * @param trendRefreshed  글로벌 트렌드 수집·요약 작업을 큐에 넣었는지 (오늘 트렌드가 아직 없을 때 true; 끝나는 시점은 큐 순서상 이후 평가보다 앞선다)
 */
@Schema(description = "시연 실행 접수 결과 (HTTP 202)")
public record DemoRunResponse(
        @Schema(description = "평가 큐에 넣은 아이디어 ID. 진행은 각 GET /api/ideas/{id}의 evaluationStatus 폴링", example = "[1, 2]") List<Long> queued,
        @Schema(description = "건너뛴 아이디어와 이유") List<Skipped> skipped,
        @Schema(description = "글로벌 트렌드 수집·요약 작업을 큐에 넣었는지 (오늘 트렌드가 아직 없을 때 true)", example = "true") boolean trendRefreshed) {

    @Schema(description = "건너뛴 아이디어")
    public record Skipped(
            @Schema(description = "아이디어 ID", example = "3") Long ideaId,
            @Schema(description = "이유. ALREADY_QUEUED=이미 대기 중, ALREADY_RUNNING=이미 평가 중, ALREADY_EVALUATED_TODAY=오늘 이미 평가함, COOLDOWN=재평가 대기 시간 중(하루 제한을 끈 경우), QUEUE_FULL=대기열이 가득 참",
                    example = "ALREADY_RUNNING", allowableValues = {"ALREADY_QUEUED", "ALREADY_RUNNING", "ALREADY_EVALUATED_TODAY", "COOLDOWN", "QUEUE_FULL"}) String reason) {}
}
