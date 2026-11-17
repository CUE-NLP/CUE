package com.cue.evaluation.dto;

import com.cue.score.entity.ScoreType;
import java.math.BigDecimal;
import java.util.List;

/**
 * AI 평가 결과 중 점수가 바뀐 항목 하나. 변화가 없는 항목은 만들지 않는다.
 *
 * @param trendId TREND 평가가 근거로 쓴 글로벌 트렌드 id ({@code evaluations.trend_id}). 키워드 근거 4개 기준 평가는 null
 */
public record ScoreChange(ScoreType type, BigDecimal difference, String evidenceSummary,
                          List<ReferenceInput> references, Long trendId) {
    public ScoreChange(ScoreType type, BigDecimal difference, String evidenceSummary, List<ReferenceInput> references) {
        this(type, difference, evidenceSummary, references, null);
    }

    public ScoreChange withTrendId(Long id) {
        return new ScoreChange(type, difference, evidenceSummary, references, id);
    }
}
