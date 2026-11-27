package com.cue.evaluation.dto;

import com.cue.score.entity.ScoreType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 재평가 프롬프트에 넣는 기존 평가 내역 한 건 (트랜잭션 밖에서 쓰려고 값만 복사한 것). */
public record PastEvaluation(ScoreType type, LocalDateTime evaluatedAt, BigDecimal difference, BigDecimal scoreAfter,
                             String evidenceSummary, List<ReferenceInput> references) {}
