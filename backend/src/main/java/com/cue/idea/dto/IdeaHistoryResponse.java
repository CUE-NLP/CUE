package com.cue.idea.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 점수 이력 한 점: 평가 시각의 총점과 그때의 총점 변화량. */
public record IdeaHistoryResponse(LocalDateTime evaluatedAt, BigDecimal totalScore, BigDecimal totalDifference) {}
