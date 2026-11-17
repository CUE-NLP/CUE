package com.cue.evaluation.dto;

import com.cue.score.entity.ScoreType;
import java.math.BigDecimal;
import java.util.List;

/** AI 평가 결과 중 점수가 바뀐 항목 하나. 변화가 없는 항목은 만들지 않는다. */
public record ScoreChange(ScoreType type, BigDecimal difference, String evidenceSummary, List<ReferenceInput> references) {}
