package com.cue.evaluation;

import static org.junit.jupiter.api.Assertions.*;

import com.cue.idea.entity.Idea;
import com.cue.idea.entity.IdeaType;
import java.util.Map;
import com.cue.score.entity.ScoreType;
import org.junit.jupiter.api.Test;

class EvaluationDomainTest {
    private Idea idea() {
        return new Idea(null, IdeaType.IT, "문제", "타깃", "해결", "기대");
    }

    @Test
    void 새_아이디어는_전체_평가가_필요하고_완료하면_꺼진다() {
        Idea idea = idea();
        assertTrue(idea.isNeedsFullEvaluation());
        idea.completeFullEvaluation();
        assertFalse(idea.isNeedsFullEvaluation());
    }

    @Test
    void 내용이_실제로_바뀌어야_전체_평가가_다시_켜진다() {
        Idea idea = idea();
        idea.completeFullEvaluation();

        idea.edit(null, null, null, null);       // 아무것도 안 바뀜
        idea.edit("문제", "타깃", null, null);    // 같은 값
        assertFalse(idea.isNeedsFullEvaluation());

        idea.edit(null, null, "새 해결", null);
        assertTrue(idea.isNeedsFullEvaluation());
    }

    @Test
    void 예약_가중치는_턴_시작에서_적용되고_총점이_바뀐다() {
        Idea idea = idea();
        idea.score(ScoreType.MARKET_DEMAND).applyDifference(new java.math.BigDecimal("80"));
        idea.recalculateTotal(); // 20.00
        idea.reserveWeights(Map.of(ScoreType.PROBLEM_IMPORTANCE, 0, ScoreType.MARKET_DEMAND, 100,
                ScoreType.MARKET_DIFFERENTIATION, 0, ScoreType.TECH_FEASIBILITY, 0));
        assertEquals(new java.math.BigDecimal("20.00"), idea.getTotalScore());
        idea.startNextTurn();
        assertEquals(new java.math.BigDecimal("80.00"), idea.getTotalScore());
    }
}
