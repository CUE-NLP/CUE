package com.cue.score.entity;

import java.math.BigDecimal;

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
