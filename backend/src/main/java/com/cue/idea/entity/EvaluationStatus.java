package com.cue.idea.entity;

/**
 * 아이디어의 평가 실행 상태 (프론트가 {@code GET /api/ideas/{id}}를 폴링해 본다).
 * IDLE → (요청) QUEUED → (실행기가 집음) RUNNING → 끝나면 IDLE, 실패하면 FAILED. FAILED는 다시 요청하면 QUEUED가 된다.
 * 서버가 재시작되면 남은 QUEUED/RUNNING은 FAILED(E005)로 정리한다.
 */
@io.swagger.v3.oas.annotations.media.Schema(description = "평가 실행 상태(폴링용). IDLE=대기·완료, QUEUED=대기열에 있음, RUNNING=평가 중, FAILED=마지막 평가 실패(lastEvaluationErrorCode 참고, 다시 요청하면 재시도)")
public enum EvaluationStatus {
    IDLE, QUEUED, RUNNING, FAILED
}
