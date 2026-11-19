package com.cue.idea.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 점수 이력 한 점: 평가 시각의 총점과 그때의 총점 변화량. */
@Schema(description = "총점 이력 한 점 (점수 이력 차트용)")
public record IdeaHistoryResponse(
        @Schema(description = "평가 시각", example = "2026-11-05T15:00:00") LocalDateTime evaluatedAt,
        @Schema(description = "그 시점의 총점 스냅샷", example = "62.50") BigDecimal totalScore,
        @Schema(description = "그 평가에서의 총점 변화량", example = "1.25") BigDecimal totalDifference) {}
