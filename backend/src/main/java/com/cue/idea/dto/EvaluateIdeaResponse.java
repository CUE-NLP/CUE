package com.cue.idea.dto;

import com.cue.idea.entity.EvaluationStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 평가 요청 접수 결과 (HTTP 202). 평가는 비동기로 돌며 이 응답은 기다리지 않는다.
 * {@code status}는 QUEUED(방금 큐에 넣음) 또는 RUNNING(이미 진행 중이라 그대로 둠, 멱등). 결과는 {@code GET /api/ideas/{id}}를 폴링해
 * {@code evaluationStatus}가 IDLE이 되면 갱신된 점수·{@code hasNewChange}로, FAILED면 {@code lastEvaluationErrorCode}로 확인한다.
 * {@code idea}는 요청 시점의 아이디어 상태다.
 */
@Schema(description = "평가 비동기 요청 접수 결과 (HTTP 202)")
public record EvaluateIdeaResponse(
        @Schema(description = "QUEUED=방금 큐에 넣음, RUNNING=이미 진행 중이라 그대로 둠(QUEUED도 같은 이유로 올 수 있음). 중복 요청은 에러 없이 현재 상태를 돌려준다(멱등)",
                example = "QUEUED") EvaluationStatus status,
        @Schema(description = "요청 시점의 아이디어 상태. 결과는 GET /api/ideas/{id} 폴링으로 확인") IdeaResponse idea) {}
