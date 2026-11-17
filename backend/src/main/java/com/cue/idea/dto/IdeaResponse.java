package com.cue.idea.dto;

import com.cue.idea.entity.IdeaStatus;
import com.cue.idea.entity.IdeaType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * lastDifference: 가장 최근 평가의 총점 변화량(없으면 null). hasNewChange: 아직 확인하지 않은 새 평가가 있는지.
 */
public record IdeaResponse(
        Long id, IdeaType type, IdeaStatus status, String problem, String target, String solution, String expectation,
        BigDecimal totalScore, BigDecimal lastDifference, LocalDateTime lastEvaluatedAt, boolean hasNewChange,
        LocalDateTime createdAt, List<ScoreResponse> scores) {}
