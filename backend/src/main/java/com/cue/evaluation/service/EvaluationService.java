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
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** AI 평가 결과를 저장한다. 평가 스케줄러가 호출한다. */
@Service
@RequiredArgsConstructor
public class EvaluationService {
    private final IdeaRepository ideas;
    private final EvaluationRepository evaluations;
    private final IdeaScoreHistoryRepository histories;

    /**
     * 점수가 바뀐 항목만 반영한다. 항목별 평가(변화량·가중치·변경 후 점수·근거·출처)를 저장하고,
     * 총점을 다시 계산해 총점 변화량과 시각을 아이디어와 이력에 남긴다.
     * 변화가 하나도 없으면(비었거나 0으로 제한된 경우) 아무것도 저장하지 않으며 날짜도 갱신하지 않는다.
     *
     * @return 점수가 실제로 바뀌었으면 true
     */
    @Transactional
    public boolean recordChanges(Long ideaId, List<ScoreChange> changes) {
        Idea idea = ideas.findById(ideaId).orElseThrow(() -> new CustomException(ErrorCode.IDEA_NOT_FOUND));
        if (!idea.isActive()) {
            throw new CustomException(ErrorCode.IDEA_NOT_ACTIVE);
        }
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
            Evaluation evaluation = new Evaluation(score, null, change.evidenceSummary(), actual,
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
}
