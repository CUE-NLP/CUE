package com.cue.evaluation.service;

import com.cue.evaluation.dto.EvaluationRunResult;
import com.cue.evaluation.dto.EvidenceChanges;
import com.cue.evaluation.dto.IdeaSnapshot;
import com.cue.evaluation.dto.PastEvaluation;
import com.cue.evaluation.dto.ReferenceInput;
import com.cue.evaluation.dto.ScoreChange;
import com.cue.evaluation.entity.Evaluation;
import com.cue.evaluation.repository.EvaluationRepository;
import com.cue.global.ai.GeminiClient;
import com.cue.global.error.CustomException;
import com.cue.global.error.ErrorCode;
import com.cue.global.error.ErrorDetail;
import com.cue.idea.entity.Idea;
import com.cue.idea.repository.IdeaRepository;
import com.cue.score.entity.ScoreType;
import com.cue.source.dto.EvaluatedItem;
import com.cue.source.dto.SourceCheckResult;
import com.cue.source.entity.Source;
import com.cue.source.repository.SourceRepository;
import com.cue.source.service.SourceCheckService;
import com.cue.source.service.SourceExcerptService;
import com.cue.trend.config.TrendProperties;
import com.cue.trend.dto.TrendSnapshot;
import com.cue.trend.entity.Trend;
import com.cue.trend.repository.TrendRepository;
import com.cue.trend.service.GlobalTrendService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 아이디어 한 개의 하루 평가 파이프라인. 외부 HTTP·Gemini 호출 동안 DB 트랜잭션을 잡지 않으려고 이 클래스는
 * @Transactional을 쓰지 않고, 필요한 DB 작업만 짧은 트랜잭션({@link TransactionTemplate} 또는 EvaluationRecorder)으로 처리한다.
 *
 * <p>평가는 아이디어마다 하루에 한 번이다. 오늘 이미 끝까지 마쳤으면(내용·키워드가 바뀐 경우 제외) 자료 조회 없이 끝낸다.
 * 실패했거나 첫 TREND 평가를 하지 못한(그날 트렌드가 없는) 경우는 마친 것으로 치지 않아 같은 날 다시 시도할 수 있다.
 *
 * <p>하루 평가는 서로 독립인 두 갈래다.
 * <ul>
 *   <li><b>A. 키워드 근거 자료 → 기존 4개 기준</b>: 전체 평가는 조회에 성공한 모든 source의 상위 N개를 쓴다. 일반 재평가는 반영해 둔 항목과
 *       비교해({@link EvidenceChanges}) 추가·삭제가 있을 때만 실행하며, 기존 평가 내역·새로 추가된 항목·삭제된 항목을 넘긴다.
 *       원문 발췌({@link SourceExcerptService}, AI 호출 없음)를 붙여 한 번 평가한다 ({@link EvidenceEvaluator}).</li>
 *   <li><b>B. 글로벌 트렌드 → TREND</b>: 그날의 글로벌 트렌드({@link GlobalTrendService#today()}, 아이디어와 무관)를 아이디어 4항목에 대입해
 *       첫 평가(0~100) 또는 재평가(±10) ({@link TrendEvaluator}). 이 아이디어가 이미 그 트렌드로 평가됐으면 호출하지 않는다</li>
 * </ul>
 * 순서: ① 대상 확인(I001/I004)·Gemini 사용 가능 확인(E001) → ② 새 턴 시작(예약 가중치 적용) → ③ 트렌드 확보·source 조회·항목 비교 →
 * ④ A도 B도 필요 없으면 평가·날짜 갱신 없이 종료 → ⑤ A, B를 각자 try로 실행 → ⑥ 성공한 쪽의 결과만 한 트랜잭션으로 저장
 * (총점·이력·날짜는 합쳐서 한 번). 한 쪽이 실패해도 다른 쪽은 저장하고, 실패한 쪽(A는 source 해시를 되돌려, B는 평가 완료 표시를 안 해서)은
 * 다음 실행에서 다시 한다. 실패가 있으면 저장 뒤 그 예외를 던진다. 동시 실행 방지(평가는 한 번에 하나)와 평가 상태 기록은 {@link EvaluationQueueService}가 맡는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdeaEvaluationPipeline {
    /** 재평가 프롬프트에 넣는 기준별 최근 평가 내역 수. */
    static final int HISTORY_PER_TYPE = 3;

    private final IdeaRepository ideas;
    private final SourceRepository sources;
    private final SourceCheckService sourceCheckService;
    private final EvaluationRecorder evaluationService;
    private final EvidenceEvaluator analyzer;
    private final TrendEvaluator trendEvaluator;
    private final GlobalTrendService globalTrend;
    private final SourceExcerptService sourceExcerptService;
    private final GeminiClient gemini;
    private final TransactionTemplate tx;
    private final TrendRepository trendRepository;
    private final TrendProperties trendProps;
    private final EvaluationRepository evaluations;

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
        LocalDate today = LocalDate.now(); // 자정을 넘겨 끝나도 시작한 날의 평가로 기록한다
        if (Boolean.TRUE.equals(tx.execute(s -> ideas.findById(ideaId).map(i -> i.dailyEvaluationDone(today)).orElse(false)))) {
            return EvaluationRunResult.noChange(); // 자료 조회·가중치 적용도 하루 한 번
        }
        if (evaluationService.startTurn(ideaId)) {
            log.info("가중치 적용으로 총점이 바뀌었습니다 ideaId={}", ideaId);
            snapshot = snapshot(ideaId); // 가중치 적용 후 점수 기준
        }

        TrendSnapshot trend = globalTrend.today(); // 그날 첫 호출에서만 수집·요약, 만들지 못하면 null (B 건너뜀)
        Map<Long, PreviousState> previous = previousStates(ideaId);
        List<SourceCheckResult> checked = sourceCheckService.checkAll(ideaId);
        List<SourceCheckResult> changed = checked.stream().filter(SourceCheckResult::changed).toList();

        EvidenceChanges evidenceChanges = EvidenceChanges.between(recordedItems(previous), checked);

        // 첫 TREND 평가를 아직 못 했는데 그날 트렌드가 없으면 오늘 평가를 마친 것으로 치지 않는다
        boolean completesToday = trend != null || snapshot.lastTrendId() != null;

        boolean runA = evidenceChanges.any() || snapshot.needsFullEvaluation();
        boolean runB = trend != null && !trend.id().equals(snapshot.lastTrendId()) && trendDue(snapshot, trend);
        if (!runA && !runB) {
            evaluationService.recordEvidenceItems(evidenceChanges.current()); // 추가·삭제가 없어 평가 없이 기록만 맞춘다
            if (completesToday) {
                evaluationService.completeDailyEvaluation(ideaId, today);
            }
            return EvaluationRunResult.noChange();
        }

        List<ScoreChange> changes = new ArrayList<>();
        RuntimeException failure = null;
        boolean fullDone = false;
        Long trendDone = null;
        // 근거 평가가 필요 없으면 지금 목록을 그대로 기록하고, 필요하면 성공했을 때만 기록한다
        Map<Long, List<EvaluatedItem>> reflected = runA ? Map.of() : evidenceChanges.current();

        if (runA) {
            try {
                changes.addAll(evaluateEvidence(snapshot, checked, evidenceChanges));
                fullDone = snapshot.needsFullEvaluation();
                reflected = evidenceChanges.current();
            } catch (RuntimeException e) {
                restoreHashes(changed, previous); // 다음 실행에서 같은 변경을 다시 감지한다
                failure = locate(e, "근거 4개 기준 평가");
            }
        }
        if (runB) {
            try {
                changes.addAll(trendEvaluator.evaluate(snapshot, trend));
                trendDone = trend.id();
            } catch (RuntimeException e) {
                RuntimeException located = locate(e, "트렌디함 평가");
                if (failure == null) {
                    failure = located;
                } else {
                    failure.addSuppressed(located);
                }
            }
        }

        boolean updated;
        try {
            updated = evaluationService.recordResults(ideaId, changes, fullDone, trendDone, reflected);
        } catch (RuntimeException e) {
            restoreHashes(changed, previous); // 저장하지 못했으니 변경을 다음에 다시 감지한다
            RuntimeException located = locate(e, "평가 결과 저장");
            if (failure != null) {
                located.addSuppressed(failure);
            }
            throw located;
        }
        if (failure != null) {
            log.warn("하루 평가 일부가 실패했습니다 (성공한 쪽은 저장됨, 실패한 쪽은 다음 실행에서 다시) ideaId={}: {}", ideaId, failure.toString());
            throw failure;
        }
        if (completesToday) {
            evaluationService.completeDailyEvaluation(ideaId, today);
        }
        return EvaluationRunResult.evaluated(updated);
    }

    /** 실패에 발생 위치를 붙인다. 예상하지 못한 예외는 G001로 감싸 원래 예외를 cause로 남긴다. */
    private static CustomException locate(RuntimeException e, String location) {
        return e instanceof CustomException custom ? custom.at(location)
                : new CustomException(ErrorCode.INTERNAL_SERVER_ERROR, new ErrorDetail(location, null, "서버 내부 오류"), e);
    }

    /**
     * A: 원문 발췌를 만들어(AI 호출 없음) 기존 4개 기준을 한 번의 호출로 평가한다. 전체 평가는 조회에 성공한 모든 자료를,
     * 재평가는 새로 추가된 자료만 발췌해 기존 평가 내역·삭제된 자료와 함께 넘긴다.
     */
    private List<ScoreChange> evaluateEvidence(IdeaSnapshot snapshot, List<SourceCheckResult> checked,
                                               EvidenceChanges evidenceChanges) {
        if (snapshot.needsFullEvaluation()) {
            return analyzer.initialEvaluate(snapshot, excerpts(checked.stream()
                    .filter(result -> result.status() != SourceCheckResult.Status.FAILED).toList()));
        }
        return analyzer.reEvaluate(snapshot, history(snapshot.id()), excerpts(evidenceChanges.added()),
                evidenceChanges.removed());
    }

    private List<SourceCheckResult> excerpts(List<SourceCheckResult> results) {
        return results.stream().map(sourceExcerptService::excerpt).toList();
    }

    /** 근거 4개 기준의 기준별 최근 평가 내역 (최신순). */
    private List<PastEvaluation> history(Long ideaId) {
        return tx.execute(s -> {
            Map<ScoreType, Integer> taken = new EnumMap<>(ScoreType.class);
            List<PastEvaluation> history = new ArrayList<>();
            for (Evaluation e : evaluations.findByIdeaId(ideaId)) {
                ScoreType type = e.getScore().getType();
                if (ScoreType.EVIDENCE_TYPES.contains(type) && taken.merge(type, 1, Integer::sum) <= HISTORY_PER_TYPE) {
                    history.add(new PastEvaluation(type, e.getCreatedAt(), e.getDifference(), e.getScoreAfter(),
                            e.getEvidenceSummary(), e.getReferences().stream()
                                    .map(r -> new ReferenceInput(r.getUrl(), r.getTitle(), r.getProvider())).toList()));
                }
            }
            return history;
        });
    }

    /**
     * TREND를 지금 평가할 차례인지. 첫 평가(아직 TREND를 평가한 적 없음)는 바로, 재평가는 마지막으로 평가에 쓴 트렌드가 오늘 트렌드보다
     * {@code cue.trend.min-reeval-days}일 이상 오래됐을 때만 한다(트렌디함은 천천히 변하는 지표라 매일 부를 필요가 없다).
     * 차례가 아니면 호출도 완료 표시도 하지 않아, 기간이 지난 뒤의 트렌드로 자연스럽게 다시 평가된다.
     */
    private boolean trendDue(IdeaSnapshot snapshot, TrendSnapshot trend) {
        if (snapshot.lastTrendId() == null || trendProps.minReevalDays() <= 0) {
            return true;
        }
        LocalDateTime last = tx.execute(s -> trendRepository.findById(snapshot.lastTrendId()).map(Trend::getCreatedAt).orElse(null));
        return last == null || ChronoUnit.DAYS.between(last.toLocalDate(), trend.createdAt().toLocalDate()) >= trendProps.minReevalDays();
    }

    private void restoreHashes(List<SourceCheckResult> changed, Map<Long, PreviousState> previous) {
        for (SourceCheckResult r : changed) {
            PreviousState prev = previous.get(r.sourceId());
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

    private Map<Long, PreviousState> previousStates(Long ideaId) {
        Map<Long, PreviousState> map = new HashMap<>();
        for (Source s : sources.findByIdeaId(ideaId)) {
            map.put(s.getId(), new PreviousState(s.getContentHash(), s.getCheckedAt(), s.getEvaluatedItems()));
        }
        return map;
    }

    /** 반영 기록이 있는 source만 담는다. */
    private static Map<Long, List<EvaluatedItem>> recordedItems(Map<Long, PreviousState> previous) {
        Map<Long, List<EvaluatedItem>> map = new LinkedHashMap<>();
        previous.keySet().stream().sorted().forEach(id -> {
            if (previous.get(id).items() != null) {
                map.put(id, previous.get(id).items());
            }
        });
        return map;
    }

    private record PreviousState(String hash, LocalDateTime checkedAt, List<EvaluatedItem> items) {}
}
