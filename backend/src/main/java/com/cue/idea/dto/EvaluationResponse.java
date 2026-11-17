package com.cue.idea.dto;

import com.cue.score.entity.ScoreType;
import com.cue.source.entity.ApiProvider;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 항목별 평가 이력 한 건: 변화량, 변경 후 점수, 당시 가중치(%), 근거 요약, 출처. */
public record EvaluationResponse(
        Long id, ScoreType scoreType, BigDecimal difference, BigDecimal scoreAfter, Integer weightPercent,
        String evidenceSummary, LocalDateTime evaluatedAt, List<ReferenceResponse> references) {

    public record ReferenceResponse(String url, String title, ApiProvider provider) {}
}
