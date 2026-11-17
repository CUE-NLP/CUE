package com.cue.idea.dto;

import com.cue.evaluation.dto.EvaluationRunResult;

/**
 * 수동 평가 결과. {@code status}가 NO_CHANGE면 자료 변화가 없어 아무것도 평가하지 않은 것이고,
 * EVALUATED면 AI 평가를 했으며 {@code changed}는 점수가 실제로 바뀌었는지다. {@code idea}는 갱신된 점수가 반영된 최신 상태.
 */
public record EvaluateIdeaResponse(EvaluationRunResult.Status status, boolean changed, IdeaResponse idea) {}
