package com.cue.evaluation.dto;

import com.cue.score.entity.ScoreType;
import java.math.BigDecimal;
import java.util.Map;

/** AI 호출에 넘길 아이디어 내용과 현재 점수 (트랜잭션 밖에서 쓰려고 값만 복사한 것). */
public record IdeaSnapshot(
        Long id, String problem, String target, String solution, String expectation,
        Map<ScoreType, BigDecimal> scores, boolean needsFullEvaluation) {}
