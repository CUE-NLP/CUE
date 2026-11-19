package com.cue.score.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

@Schema(description = "평가 항목. PROBLEM_IMPORTANCE=문제 중요성, MARKET_DEMAND=시장 수요, MARKET_DIFFERENTIATION=시장 차별성, TECH_FEASIBILITY=기술적 실현 가능성")
public enum ScoreType {
    PROBLEM_IMPORTANCE,
    MARKET_DEMAND,
    MARKET_DIFFERENTIATION,
    TECH_FEASIBILITY;

    /** 4개 기준 균등 가중치. */
    public BigDecimal defaultWeight() {
        return new BigDecimal("0.250");
    }
}
