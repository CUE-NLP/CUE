package com.cue.idea.dto;

import com.cue.score.entity.ScoreType;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

/** 항목별 가중치(%). 5개 항목(TREND 포함) 모두 필요하고 합이 100이어야 한다. 예: {"PROBLEM_IMPORTANCE": 30, ...} */
public record UpdateWeightsRequest(@NotNull Map<ScoreType, Integer> weights) {}
