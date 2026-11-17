package com.cue.idea.service;

import com.cue.evaluation.dto.EvaluationRunResult;
import com.cue.evaluation.service.DailyEvaluationService;
import com.cue.idea.dto.EvaluateIdeaResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 수동 평가 트리거 (POST /api/ideas/{id}/evaluate). 소유자·활성 검사 후 일일 평가와 같은 파이프라인을 한 아이디어에 돌린다.
 * 외부 호출 동안 트랜잭션을 잡지 않도록 이 클래스는 @Transactional을 쓰지 않는다.
 */
@Service
@RequiredArgsConstructor
public class IdeaEvaluationService {
    private final IdeaService ideaService;
    private final DailyEvaluationService evaluation;

    public EvaluateIdeaResponse evaluate(Long userId, Long ideaId) {
        ideaService.assertEvaluable(userId, ideaId); // I001 / I002 / I004
        EvaluationRunResult result = evaluation.evaluate(ideaId);
        return new EvaluateIdeaResponse(result.status(), result.changed(), ideaService.get(userId, ideaId));
    }
}
