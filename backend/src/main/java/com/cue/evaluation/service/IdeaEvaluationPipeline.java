package com.cue.evaluation.service;

import com.cue.evaluation.dto.EvaluationRunResult;
import com.cue.evaluation.dto.IdeaSnapshot;
import com.cue.evaluation.dto.ScoreChange;
import com.cue.global.ai.GeminiClient;
import com.cue.global.error.CustomException;
import com.cue.global.error.ErrorCode;
import com.cue.idea.entity.Idea;
import com.cue.idea.repository.IdeaRepository;
import com.cue.score.entity.ScoreType;
import com.cue.source.dto.SourceCheckResult;
import com.cue.source.dto.SourceSummary;
import com.cue.source.entity.Source;
import com.cue.source.repository.SourceRepository;
import com.cue.source.service.SourceCheckService;
import com.cue.source.service.SourceSummaryService;
import com.cue.trend.dto.TrendSnapshot;
import com.cue.trend.service.GlobalTrendService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 아이디어 한 개의 하루 평가 파이프라인. 외부 HTTP·Gemini 호출 동안 DB 트랜잭션을 잡지 않으려고 이 클래스는
 * @Transactional을 쓰지 않고, 필요한 DB 작업만 짧은 트랜잭션({@link TransactionTemplate} 또는 EvaluationRecorder)으로 처리한다.
 *
 * <p>하루 평가는 서로 독립인 두 갈래다.
 * <ul>
 *   <li><b>A. 키워드 근거 자료 → 기존 4개 기준</b>: 아이디어 종류별 source를 키워드로 조회해 해시로 변경을 감지하고, 변경된 source마다
 *       상위 N개 원문 읽기→항목 요약→소스 요약({@link SourceSummaryService}) 후 전체 평가 요청이면 초기 평가, 아니면 재평가 ({@link EvidenceEvaluator})</li>
 *   <li><b>B. 글로벌 트렌드 → TREND</b>: 그날의 글로벌 트렌드({@link GlobalTrendService#today()}, 아이디어와 무관)를 아이디어 4항목에 대입해
 *       첫 평가(0~100) 또는 재평가(±10) ({@link TrendEvaluator}). 이 아이디어가 이미 그 트렌드로 평가됐으면 호출하지 않는다</li>
 * </ul>
 * 순서: ① 대상 확인(I001/I004)·Gemini 사용 가능 확인(E001) → ② 새 턴 시작(예약 가중치 적용) → ③ 트렌드 확보·source 조회 →
 * ④ A도 B도 필요 없으면 평가·날짜 갱신 없이 종료 → ⑤ A, B를 각자 try로 실행 → ⑥ 성공한 쪽의 결과만 한 트랜잭션으로 저장
 * (총점·이력·날짜는 합쳐서 한 번). 한 쪽이 실패해도 다른 쪽은 저장하고, 실패한 쪽(A는 source 해시를 되돌려, B는 평가 완료 표시를 안 해서)은
 * 다음 실행에서 다시 한다. 실패가 있으면 저장 뒤 그 예외를 던진다. 동시 실행 방지(평가는 한 번에 하나)와 평가 상태 기록은 {@link EvaluationQueueService}가 맡는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdeaEvaluationPipeline {
    private final IdeaRepository ideas;
    private final SourceRepository sources;
    private final SourceCheckService sourceCheckService;
    private final EvaluationRecorder evaluationService;
    private final EvidenceEvaluator analyzer;
    private final TrendEvaluator trendEvaluator;
    private final GlobalTrendService globalTrend;
    private final SourceSummaryService sourceSummaryService;
    private final GeminiClient gemini;
    private final TransactionTemplate tx;

    /**
     * @throws CustomException 없는 아이디어 I001, 보류 I004, AI 사용 불가 E001,
     *                         AI 통신 실패 G002, AI 응답 오류 E002 (A·B 중 하나가 실패한 경우, 다른 쪽 결과는 저장된 뒤다)
     */
    public EvaluationRunResult evaluate(Long ideaId) {
        return run(ideaId);
    }

    private EvaluationRunResult run(Long ideaId) {
        IdeaSnapshot snapshot = snapshot(ideaId); // 없음 I001, 보류 I004
        if (!gemini.available()) {
            throw new CustomException(ErrorCode.AI_NOT_CONFIGURED);
        }
        if (evaluationService.startTurn(ideaId)) {
            log.info("가중치 적용으로 총점이 바뀌었습니다 ideaId={}", ideaId);
            snapshot = snapshot(ideaId); // 가중치 적용 후 점수 기준
        }

        TrendSnapshot trend = globalTrend.today(); // 그날 첫 호출에서만 수집·요약, 만들지 못하면 null (B 건너뜀)
        Map<Long, PreviousHash> previous = previousHashes(ideaId);
        List<SourceCheckResult> changed = sourceCheckService.checkAll(ideaId).stream()
                .filter(SourceCheckResult::changed).toList();

        boolean runA = !changed.isEmpty() || snapshot.needsFullEvaluation();
        boolean runB = trend != null && !trend.id().equals(snapshot.lastTrendId());
        if (!runA && !runB) {
            return EvaluationRunResult.noChange();
        }

        List<ScoreChange> changes = new ArrayList<>();
        RuntimeException failure = null;
        boolean fullDone = false;
        Long trendDone = null;
        Set<Long> degraded = new java.util.HashSet<>(); // 일괄 요약이 실패해 원래 항목으로 평가한 source

        if (runA) {
            try {
                changes.addAll(evaluateEvidence(snapshot, changed, degraded));
                fullDone = snapshot.needsFullEvaluation();
            } catch (RuntimeException e) {
                restoreHashes(changed, previous); // 다음 실행에서 같은 변경을 다시 감지한다
                failure = e;
            }
        }
        if (runB) {
            try {
                changes.addAll(trendEvaluator.evaluate(snapshot, trend));
                trendDone = trend.id();
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }

        boolean updated;
        try {
            updated = evaluationService.recordResults(ideaId, changes, fullDone, trendDone);
        } catch (RuntimeException e) {
            restoreHashes(changed, previous); // 저장하지 못했으니 변경을 다음에 다시 감지한다
            if (failure != null) {
                e.addSuppressed(failure);
            }
            throw e;
        }
        // 일괄 요약에 실패해 짧은 요약으로 평가한 source는 해시를 되돌려 다음 실행에서 다시 읽고 요약한다
        restoreHashes(changed.stream().filter(r -> degraded.contains(r.sourceId())).toList(), previous);
        if (failure != null) {
            log.warn("하루 평가 일부가 실패했습니다 (성공한 쪽은 저장됨, 실패한 쪽은 다음 실행에서 다시) ideaId={}: {}", ideaId, failure.toString());
            throw failure;
        }
        return EvaluationRunResult.evaluated(updated);
    }

    /** A: 변경된 source의 원문을 읽고 요약해 기존 4개 기준을 평가한다. */
    private List<ScoreChange> evaluateEvidence(IdeaSnapshot snapshot, List<SourceCheckResult> changed, Set<Long> degraded) {
        List<SourceSummary> summaries = changed.stream().map(sourceSummaryService::summarize).toList();
        summaries.stream().filter(SourceSummary::degraded).forEach(d -> degraded.add(d.result().sourceId()));
        List<SourceCheckResult> enriched = summaries.stream().map(SourceSummary::result).toList();
        return snapshot.needsFullEvaluation()
                ? analyzer.initialEvaluate(snapshot, enriched)
                : analyzer.reEvaluate(snapshot, enriched);
    }

    private void restoreHashes(List<SourceCheckResult> changed, Map<Long, PreviousHash> previous) {
        for (SourceCheckResult r : changed) {
            PreviousHash prev = previous.get(r.sourceId());
            if (prev != null) {
                sourceCheckService.restoreHash(r.sourceId(), prev.hash(), prev.checkedAt());
            }
        }
    }

    private IdeaSnapshot snapshot(Long ideaId) {
        return tx.execute(status -> {
            Idea idea = ideas.findById(ideaId).orElseThrow(() -> new CustomException(ErrorCode.IDEA_NOT_FOUND));
            if (!idea.isActive()) {
                throw new CustomException(ErrorCode.IDEA_NOT_ACTIVE);
            }
            Map<ScoreType, BigDecimal> scores = new EnumMap<>(ScoreType.class);
            idea.getScores().forEach(s -> scores.put(s.getType(), s.getScore()));
            return new IdeaSnapshot(idea.getId(), idea.getProblem(), idea.getTarget(), idea.getSolution(),
                    idea.getExpectation(), scores, idea.isNeedsFullEvaluation(), idea.getLastTrendId());
        });
    }

    private Map<Long, PreviousHash> previousHashes(Long ideaId) {
        Map<Long, PreviousHash> map = new HashMap<>();
        for (Source s : sources.findByIdeaId(ideaId)) {
            map.put(s.getId(), new PreviousHash(s.getContentHash(), s.getCheckedAt()));
        }
        return map;
    }

    private record PreviousHash(String hash, LocalDateTime checkedAt) {}
}
