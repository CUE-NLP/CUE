package com.cue.evaluation.dto;

import com.cue.score.entity.ScoreType;
import java.math.BigDecimal;
import java.util.Map;

/**
 * AI 호출에 넘길 아이디어 내용과 현재 점수 (트랜잭션 밖에서 쓰려고 값만 복사한 것).
 *
 * @param needsFullEvaluation 키워드 근거 4개 기준의 전체(초기) 평가가 필요한지
 * @param lastTrendId         TREND를 마지막으로 평가한 글로벌 트렌드 id (null이면 TREND 첫 평가 전)
 */
public record IdeaSnapshot(
        Long id, String problem, String target, String solution, String expectation,
        Map<ScoreType, BigDecimal> scores, boolean needsFullEvaluation, Long lastTrendId) {}
