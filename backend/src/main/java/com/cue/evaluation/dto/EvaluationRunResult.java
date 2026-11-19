package com.cue.evaluation.dto;

/**
 * 아이디어 하나의 평가 결과.
 * NO_CHANGE: 자료 변화도 전체 평가 요청도 없어 아무것도 평가·기록하지 않음.
 * EVALUATED: AI 평가를 수행함. {@code changed}는 점수가 실제로 바뀌었는지(바뀌었을 때만 평가·이력·날짜가 기록된다).
 */
public record EvaluationRunResult(Status status, boolean changed) {
    public enum Status { NO_CHANGE, EVALUATED }

    public static EvaluationRunResult noChange() {
        return new EvaluationRunResult(Status.NO_CHANGE, false);
    }

    public static EvaluationRunResult evaluated(boolean changed) {
        return new EvaluationRunResult(Status.EVALUATED, changed);
    }
}
