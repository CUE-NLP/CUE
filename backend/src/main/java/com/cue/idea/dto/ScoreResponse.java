package com.cue.idea.dto;

import com.cue.score.entity.ScoreType;
import java.math.BigDecimal;

/** latestDifference: 가장 최근 평가의 변화량 (없으면 null). 화면에서 점수 옆에 작게 표시한다. */
public record ScoreResponse(ScoreType type, BigDecimal weight, BigDecimal score, BigDecimal latestDifference) {}
