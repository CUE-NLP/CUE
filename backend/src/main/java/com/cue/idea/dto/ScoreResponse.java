package com.cue.idea.dto;

import com.cue.score.entity.ScoreType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/**
 * weightPercent: 현재 적용 중인 가중치(%). pendingWeightPercent: 다음 턴부터 적용될 예약 가중치(%, 없으면 null).
 * latestDifference: 가장 최근 평가의 변화량 (없으면 null). 화면에서 점수 옆에 작게 표시한다.
 */
@Schema(description = "평가 항목 하나의 점수·가중치")
public record ScoreResponse(
        @Schema(description = "평가 항목", example = "MARKET_DEMAND") ScoreType type,
        @Schema(description = "현재 적용 중인 가중치(%)", example = "25") Integer weightPercent,
        @Schema(description = "다음 평가 턴부터 적용될 예약 가중치(%). 예약이 없으면 null", example = "30", nullable = true) Integer pendingWeightPercent,
        @Schema(description = "항목 점수 (0~100)", example = "60.00") BigDecimal score,
        @Schema(description = "가장 최근 평가에서의 변화량. 평가 이력이 없으면 null", example = "2.50", nullable = true) BigDecimal latestDifference) {}
