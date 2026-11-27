package com.cue.evaluation.service;

import com.cue.evaluation.dto.ReferenceInput;
import com.cue.evaluation.dto.ScoreChange;
import com.cue.evaluation.entity.Evaluation;
import com.cue.evaluation.entity.IdeaScoreHistory;
import com.cue.evaluation.repository.EvaluationRepository;
import com.cue.evaluation.repository.IdeaScoreHistoryRepository;
import com.cue.global.error.CustomException;
import com.cue.global.error.ErrorCode;
import com.cue.idea.entity.Idea;
import com.cue.idea.repository.IdeaRepository;
import com.cue.score.entity.Score;
import com.cue.source.dto.EvaluatedItem;
import com.cue.source.repository.SourceRepository;
import com.cue.trend.entity.Trend;
import com.cue.trend.repository.TrendRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI 평가 결과를 저장한다(항목별 Evaluation·총점 재계산·총점 이력·완료 표시를 한 트랜잭션으로, {@link #recordResults}).
 * 하루 평가 파이프라인({@link IdeaEvaluationPipeline})이 호출하고, 새 평가 턴을 시작할 때 예약 가중치를 적용한다({@link #startTurn}).
 */
@Service
@RequiredArgsConstructor
public class EvaluationRecorder {
    private final IdeaRepository ideas;
    private final EvaluationRepository evaluations;
    private final IdeaScoreHistoryRepository histories;
    private final TrendRepository trends;
    private final SourceRepository sources;

    /**
     * 점수가 바뀐 항목만 반영한다. 항목별 평가(변화량·가중치·변경 후 점수·근거·출처)를 저장하고,
     * 총점을 다시 계산해 총점 변화량과 시각을 아이디어와 이력에 남긴다.
     * 변화가 하나도 없으면(비었거나 0으로 제한된 경우) 아무것도 저장하지 않으며 날짜도 갱신하지 않는다.
     *
     * @return 점수가 실제로 바뀌었으면 true
     */
    @Transactional
    public boolean recordChanges(Long ideaId, List<ScoreChange> changes) {
        return recordResults(ideaId, changes, false, null, Map.of());
    }

    /**
     * {@link #recordChanges}와 같고, 하루 평가의 결과(키워드 근거 4개 기준 + TREND)를 한 트랜잭션으로 저장하면서 평가 완료 표시도 함께 남긴다.
     * TREND 변화({@link ScoreChange#trendId()} 있음)는 그 트렌드 행에 연결된다. 총점·이력·날짜는 합쳐서 한 번만 기록한다.
     *
     * @param completeFull   키워드 근거 전체(초기) 평가를 마쳤으면 true ({@code needs_full_evaluation} 해제)
     * @param trendEvaluated TREND 평가를 마친 글로벌 트렌드 id (점수가 안 바뀌었어도 기록한다), 안 했으면 null
     * @param evidenceItems  점수에 반영한 source별 항목 (점수가 안 바뀌었어도 기록한다), 근거 평가에 실패했으면 빈 값
     */
    @Transactional
    public boolean recordResults(Long ideaId, List<ScoreChange> changes, boolean completeFull, Long trendEvaluated,
                                 Map<Long, List<EvaluatedItem>> evidenceItems) {
        Idea idea = ideas.findById(ideaId).orElseThrow(() -> new CustomException(ErrorCode.IDEA_NOT_FOUND));
        if (!idea.isActive()) {
            throw new CustomException(ErrorCode.IDEA_NOT_ACTIVE);
        }
        if (completeFull) {
            idea.completeFullEvaluation();
        }
        if (trendEvaluated != null) {
            idea.completeTrendEvaluation(trendEvaluated);
        }
        recordEvidenceItems(evidenceItems);
        LocalDateTime now = LocalDateTime.now();
        BigDecimal totalBefore = idea.getTotalScore();
        boolean changed = false;
        for (ScoreChange change : changes) {
            Score score = idea.score(change.type());
            BigDecimal before = score.getScore();
            score.applyDifference(change.difference());
            BigDecimal actual = score.getScore().subtract(before); // 0~100 제한이 반영된 실제 변화량
            if (actual.signum() == 0) {
                continue;
            }
            Trend trend = change.trendId() == null ? null : trends.getReferenceById(change.trendId());
            Evaluation evaluation = new Evaluation(score, trend, change.evidenceSummary(), actual,
                    score.getWeight(), score.getScore(), now);
            if (change.references() != null) {
                for (ReferenceInput ref : change.references()) {
                    evaluation.addReference(ref.url(), ref.title(), ref.provider());
                }
            }
            evaluations.save(evaluation);
            changed = true;
        }
        if (!changed) {
            return false;
        }
        idea.recalculateTotal();
        BigDecimal totalDifference = idea.getTotalScore().subtract(totalBefore);
        idea.recordEvaluation(totalDifference, now);
        histories.save(new IdeaScoreHistory(idea, idea.getTotalScore(), totalDifference, now));
        return true;
    }

    /** source별로 점수에 반영한 항목을 기록한다. 다음 조회에서 이 목록과 비교해 추가·삭제를 가려낸다. */
    @Transactional
    public void recordEvidenceItems(Map<Long, List<EvaluatedItem>> evidenceItems) {
        evidenceItems.forEach((sourceId, items) -> sources.findById(sourceId).ifPresent(s -> s.recordEvaluatedItems(items)));
    }

    /**
     * 새 평가 턴을 시작한다: 예약된 가중치를 적용한다. 가중치만으로 총점이 바뀌면 그 총점을 이력 스냅샷으로 남긴다
     * (평가 이력은 만들지 않고 {@code last_evaluated_at}·{@code last_difference}도 건드리지 않는다. 평가가 아니라서 "새 변화" 알림이 켜지지 않는다).
     *
     * @return 총점이 바뀌어 스냅샷을 남겼으면 true
     */
    @Transactional
    public boolean startTurn(Long ideaId) {
        Idea idea = ideas.findById(ideaId).orElseThrow(() -> new CustomException(ErrorCode.IDEA_NOT_FOUND));
        BigDecimal totalBefore = idea.getTotalScore();
        idea.startNextTurn();
        BigDecimal difference = idea.getTotalScore().subtract(totalBefore);
        if (difference.signum() == 0) {
            return false;
        }
        histories.save(new IdeaScoreHistory(idea, idea.getTotalScore(), difference, LocalDateTime.now()));
        return true;
    }
}
