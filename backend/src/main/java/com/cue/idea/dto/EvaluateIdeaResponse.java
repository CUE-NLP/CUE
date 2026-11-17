package com.cue.idea.dto;

import com.cue.idea.entity.EvaluationStatus;

/**
 * 평가 요청 접수 결과 (HTTP 202). 평가는 비동기로 돌며 이 응답은 기다리지 않는다.
 * {@code status}는 QUEUED(방금 큐에 넣음) 또는 RUNNING(이미 진행 중이라 그대로 둠, 멱등). 결과는 {@code GET /api/ideas/{id}}를 폴링해
 * {@code evaluationStatus}가 IDLE이 되면 갱신된 점수·{@code hasNewChange}로, FAILED면 {@code lastEvaluationErrorCode}로 확인한다.
 * {@code idea}는 요청 시점의 아이디어 상태다.
 */
public record EvaluateIdeaResponse(EvaluationStatus status, IdeaResponse idea) {}
