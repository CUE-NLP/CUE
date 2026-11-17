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
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 아이디어 한 개의 재평가 파이프라인. 외부 HTTP·Gemini 호출 동안 DB 트랜잭션을 잡지 않으려고 이 클래스는
 * @Transactional을 쓰지 않고, 필요한 DB 작업만 짧은 트랜잭션({@link TransactionTemplate} 또는 EvaluationService)으로 처리한다.
 *
 * <ol>
 *   <li>(HOLD 아니어야 함, Gemini 사용 가능해야 함) 새 턴 시작: 예약 가중치 적용, 총점이 바뀌면 이력 스냅샷</li>
 *   <li>모든 source 조회 → 해시 비교로 변경 감지</li>
 *   <li>변경도 없고 전체 평가 요청도 없으면 평가·날짜 갱신 없이 종료</li>
 *   <li>변경된 source마다 상위 N개 원문 읽기→항목 요약→소스 요약({@link SourceSummaryService}) → 전체 평가 요청이면 초기 평가, 아니면 재평가를 Gemini에 요청 (기존 4개 기준)</li>
 *   <li>결과를 {@code recordChanges}로 저장 (trend_id 없음). 실패하면 해시를 되돌려 다음에 같은 변경을 다시 감지한다</li>
 * </ol>
 * 예외(CustomException)는 호출한 쪽(배치/API)이 처리한다. 같은 아이디어는 동시에 하나만 평가한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DailyEvaluationService {
    private final IdeaRepository ideas;
    private final SourceRepository sources;
    private final SourceCheckService sourceCheckService;
    private final EvaluationService evaluationService;
    private final EvaluationAnalyzer analyzer;
    private final SourceSummaryService sourceSummaryService;
    private final GeminiClient gemini;
    private final TransactionTemplate tx;

    private final Set<Long> running = ConcurrentHashMap.newKeySet();

    /**
     * @throws CustomException 없는 아이디어 I001, 보류 I004, AI 사용 불가 E001, 이미 평가 중 E003,
     *                         AI 통신 실패 G002, AI 응답 오류 E002
     */
    public EvaluationRunResult evaluate(Long ideaId) {
        if (!running.add(ideaId)) {
            throw new CustomException(ErrorCode.EVALUATION_IN_PROGRESS);
        }
        try {
            return run(ideaId);
        } finally {
            running.remove(ideaId);
        }
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

        Map<Long, PreviousHash> previous = previousHashes(ideaId);
        List<SourceCheckResult> changed = sourceCheckService.checkAll(ideaId).stream()
                .filter(SourceCheckResult::changed).toList();
        if (changed.isEmpty() && !snapshot.needsFullEvaluation()) {
            return EvaluationRunResult.noChange();
        }

        try {
            // 변경된 source마다: 상위 N개 원문 읽기 → 항목 요약 → 소스 요약(DB 덮어쓰기). 그 요약들로 최종 트렌드 요약.
            List<SourceSummary> summaries = changed.stream().map(sourceSummaryService::summarize).toList();
            List<SourceCheckResult> enriched = summaries.stream().map(SourceSummary::result).toList();
            List<ScoreChange> changes = snapshot.needsFullEvaluation()
                    ? analyzer.initialEvaluate(snapshot, enriched)
                    : analyzer.reEvaluate(snapshot, enriched);
            boolean full = snapshot.needsFullEvaluation();
            Boolean updated = tx.execute(status -> {
                boolean result = evaluationService.recordChanges(ideaId, changes); // 키워드 근거 평가: trend_id는 null
                if (full) {
                    ideas.findById(ideaId).orElseThrow().completeFullEvaluation();
                }
                return result;
            });
            return EvaluationRunResult.evaluated(Boolean.TRUE.equals(updated));
        } catch (RuntimeException e) {
            // 변경을 평가에 반영하지 못했으므로 다음 실행에서 다시 변경으로 감지되도록 해시를 되돌린다.
            for (SourceCheckResult r : changed) {
                PreviousHash prev = previous.get(r.sourceId());
                if (prev != null) {
                    sourceCheckService.restoreHash(r.sourceId(), prev.hash(), prev.checkedAt());
                }
            }
            throw e;
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
                    idea.getExpectation(), scores, idea.isNeedsFullEvaluation());
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
