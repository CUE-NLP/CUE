package com.cue.idea.dto;

import com.cue.idea.entity.IdeaStatus;
import com.cue.idea.entity.IdeaType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * lastDifference: 가장 최근 평가의 총점 변화량(없으면 null). hasNewChange: 아직 확인하지 않은 새 평가가 있는지.
 */
@Schema(description = "아이디어 상세/리더보드 항목")
public record IdeaResponse(
        @Schema(description = "아이디어 ID", example = "1") Long id,
        @Schema(description = "아이디어 종류", example = "IT") IdeaType type,
        @Schema(description = "상태. ACTIVE=평가 대상, HOLD=구조화 항목이 비어 보류", example = "ACTIVE") IdeaStatus status,
        @Schema(description = "문제 (비어 있으면 null)", example = "아이디어가 재평가되지 않고 잊힌다.") String problem,
        @Schema(description = "타깃 (비어 있으면 null)", example = "사이드 프로젝트를 구상하는 개발자") String target,
        @Schema(description = "해결 (비어 있으면 null)", example = "외부 자료 변화로 매일 점수를 재평가한다.") String solution,
        @Schema(description = "기대효과 (비어 있으면 null)", example = "경쟁력 변화를 보고 우선순위를 정한다.") String expectation,
        @Schema(description = "총점 = Σ(항목 점수 × 가중치), 소수 2자리. 첫 평가 전에는 0", example = "62.50") BigDecimal totalScore,
        @Schema(description = "가장 최근 평가의 총점 변화량. 평가 이력이 없으면 null", example = "1.25", nullable = true) BigDecimal lastDifference,
        @Schema(description = "가장 최근 평가 시각. 없으면 null", example = "2026-11-05T15:00:00", nullable = true) LocalDateTime lastEvaluatedAt,
        @Schema(description = "확인하지 않은 새 평가가 있는지. POST /api/ideas/{id}/check 로 해제", example = "true") boolean hasNewChange,
        @Schema(description = "등록 시각", example = "2026-11-01T10:30:00") LocalDateTime createdAt,
        @Schema(description = "평가 항목 4개의 점수·가중치") List<ScoreResponse> scores) {}
