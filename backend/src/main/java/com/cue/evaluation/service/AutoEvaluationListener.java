package com.cue.evaluation.service;

import com.cue.evaluation.config.EvaluationProperties;
import com.cue.global.ai.GeminiClient;
import com.cue.idea.event.IdeaEvaluationRequested;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 아이디어가 ACTIVE로 저장·수정되면 첫 평가를 <b>트랜잭션 커밋 뒤에</b> 비동기로 큐에 넣는다 (프론트 "분석 중" 화면용).
 * 롤백되면 호출되지 않는다. {@code cue.evaluation.auto-on-save=false}면 아무것도 하지 않는다.
 * 어떤 이유로 시작하지 못해도(Gemini 불가·대기열 가득 등) 저장 응답은 실패시키지 않는다: 로그만 남기고, 대기열이 가득 찬 경우는 FAILED(E004) 상태로 남긴다.
 * 평가가 필요한 아이디어(needs_full_evaluation)는 다음 일일 배치에서도 다시 평가된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AutoEvaluationListener {
    private final EvaluationProperties props;
    private final EvaluationQueueService jobs;
    private final GeminiClient gemini;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSaved(IdeaEvaluationRequested event) {
        if (!props.autoOnSave()) {
            return;
        }
        if (!gemini.available()) {
            log.info("Gemini를 사용할 수 없어 자동 평가를 시작하지 않습니다 ideaId={}", event.ideaId());
            return;
        }
        try {
            jobs.submit(event.ideaId(), false);
        } catch (RuntimeException e) {
            log.warn("자동 평가 시작에 실패했습니다 ideaId={}", event.ideaId(), e);
        }
    }
}
