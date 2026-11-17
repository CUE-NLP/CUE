package com.cue.score.entity;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/**
 * 평가 기준 5개. 앞 4개는 아이디어 키워드로 모은 "근거 자료"로 판단하고, {@link #TREND}는 아이디어와 무관한
 * 글로벌 트렌드(하루 1회 수집)로 판단한다.
 */
public enum ScoreType {
    PROBLEM_IMPORTANCE,
    MARKET_DEMAND,
    MARKET_DIFFERENTIATION,
    TECH_FEASIBILITY,
    /** 트렌디함: 글로벌 트렌드(Hacker News·GitHub·Hugging Face 급상승)와 아이디어의 맞닿음. */
    TREND;

    /** 키워드 근거 자료로 평가하는 기준(= TREND 제외 4개). */
    public static final List<ScoreType> EVIDENCE_TYPES =
            Arrays.stream(values()).filter(t -> t != TREND).toList();

    /** 5개 기준 균등 가중치(20%, 합 100). */
    public BigDecimal defaultWeight() {
        return new BigDecimal("0.200");
    }
}
