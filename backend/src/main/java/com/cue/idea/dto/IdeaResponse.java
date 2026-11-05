package com.cue.idea.dto;

import com.cue.idea.entity.IdeaType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record IdeaResponse(
        Long id, IdeaType type, String problem, String target, String solution, String expectation,
        BigDecimal totalScore, LocalDateTime createdAt, List<ScoreResponse> scores) {}
