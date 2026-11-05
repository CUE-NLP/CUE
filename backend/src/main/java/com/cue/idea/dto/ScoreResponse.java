package com.cue.idea.dto;

import com.cue.score.entity.ScoreType;
import java.math.BigDecimal;

/**
 * weightPercent: 현재 적용 중인 가중치(%). pendingWeightPercent: 다음 턴부터 적용될 예약 가중치(%, 없으면 null).
 * latestDifference: 가장 최근 평가의 변화량 (없으면 null). 화면에서 점수 옆에 작게 표시한다.
 */
public record ScoreResponse(
        ScoreType type, Integer weightPercent, Integer pendingWeightPercent,
        BigDecimal score, BigDecimal latestDifference) {}
