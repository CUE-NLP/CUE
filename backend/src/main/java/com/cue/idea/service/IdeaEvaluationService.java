package com.cue.idea.service;

import com.cue.evaluation.service.EvaluationQueueService;
import com.cue.global.ai.GeminiClient;
import com.cue.global.error.CustomException;
import com.cue.global.error.ErrorCode;
import com.cue.idea.dto.EvaluateIdeaResponse;
import com.cue.idea.entity.EvaluationStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 수동 평가 요청 (POST /api/ideas/{id}/evaluate). 소유자·활성·AI 사용 가능 여부는 요청 시점에 동기로 검사하고,
 * 평가 자체는 큐에 넣고 바로 돌아온다(비동기). 평가 동안의 트랜잭션은 {@link EvaluationQueueService}가 잡지 않는다.
 */
@Service
@RequiredArgsConstructor
public class IdeaEvaluationService {
    private final IdeaService ideaService;
    private final EvaluationQueueService jobs;
    private final GeminiClient gemini;

    /**
     * @throws CustomException 없음 I001, 남의 것 I002, 보류 I004, AI 사용 불가 E001, 대기열 가득 E004
     */
    public EvaluateIdeaResponse request(Long userId, Long ideaId) {
        ideaService.assertEvaluable(userId, ideaId);
        if (!gemini.available()) {
            throw new CustomException(ErrorCode.AI_NOT_CONFIGURED);
        }
        EvaluationStatus status = jobs.submit(ideaId, true); // 이미 QUEUED/RUNNING이면 그 상태를 그대로 (멱등)
        return new EvaluateIdeaResponse(status, ideaService.get(userId, ideaId));
    }
}
