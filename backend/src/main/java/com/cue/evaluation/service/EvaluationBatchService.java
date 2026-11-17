package com.cue.evaluation.service;

import com.cue.evaluation.config.EvaluationProperties;
import com.cue.evaluation.dto.EvaluationRunResult;
import com.cue.global.ai.GeminiClient;
import com.cue.idea.entity.IdeaStatus;
import com.cue.idea.repository.IdeaRepository;
import com.cue.trend.service.GlobalTrendService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 일일 배치: ACTIVE 아이디어를 하나씩 재평가한다 (HOLD 제외). 아이디어마다 독립된 트랜잭션·예외 처리라서
 * 한 아이디어가 실패해도 다음 아이디어는 계속한다. 아이디어 루프 전에 글로벌 트렌드를 한 번 만든다(아이디어 수와 무관). Gemini 무료 티어 한도를 위해 아이디어 사이에 {@code cue.evaluation.delay-ms}만큼 쉰다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvaluationBatchService {
    private final IdeaRepository ideas;
    private final DailyEvaluationService evaluation;
    private final GeminiClient gemini;
    private final GlobalTrendService globalTrend;
    private final EvaluationProperties props;

    /** 배치 한 번의 집계. */
    public record Summary(int total, int evaluated, int changed, int noChange, int failed) {}

    public Summary runAll() {
        List<Long> ids = ideas.findIdsByStatus(IdeaStatus.ACTIVE);
        if (!gemini.available()) {
            log.warn("Gemini를 사용할 수 없어(비활성 또는 API 키 없음) 일일 재평가를 건너뜁니다. 대상 {}건", ids.size());
            return new Summary(ids.size(), 0, 0, 0, ids.size());
        }
        globalTrend.today(); // 글로벌 트렌드를 아이디어 루프 전에 한 번만 수집·요약한다 (실패하면 TREND 평가만 건너뛰고 다음 배치에서 다시)
        int evaluated = 0, changed = 0, noChange = 0, failed = 0;
        for (int i = 0; i < ids.size(); i++) {
            Long id = ids.get(i);
            boolean aiUsed = true;
            try {
                EvaluationRunResult r = evaluation.evaluate(id);
                if (r.status() == EvaluationRunResult.Status.NO_CHANGE) {
                    noChange++;
                    aiUsed = false;
                } else {
                    evaluated++;
                    if (r.changed()) {
                        changed++;
                    }
                }
            } catch (RuntimeException e) {
                failed++;
                log.warn("아이디어 재평가 실패, 건너뜁니다 ideaId={}: {}", id, e.toString());
            }
            if (aiUsed && i < ids.size() - 1) {
                pause();
            }
        }
        log.info("일일 재평가 완료: 대상 {}건, 평가 {}건(점수 변화 {}건), 변화 없음 {}건, 실패 {}건",
                ids.size(), evaluated, changed, noChange, failed);
        return new Summary(ids.size(), evaluated, changed, noChange, failed);
    }

    private void pause() {
        if (props.delayMs() <= 0) {
            return;
        }
        try {
            Thread.sleep(props.delayMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
