package com.cue;

import static org.junit.jupiter.api.Assertions.*;

import com.cue.global.error.ErrorCode;
import com.cue.idea.entity.Idea;
import com.cue.idea.entity.IdeaType;
import com.cue.score.entity.Score;
import com.cue.score.entity.ScoreType;
import com.cue.source.entity.ApiProvider;
import com.cue.source.entity.Source;
import java.math.BigDecimal;
import com.cue.global.error.CustomException;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class DomainTest {
    @Test
    void 변화량을_반영하면_총점이_가중합으로_계산된다() {
        Idea idea = new Idea(null, IdeaType.IT, "p", "t", "s", "e");
        idea.getScores().forEach(s -> s.applyDifference(new BigDecimal("70")));
        idea.getScores().stream()
                .filter(s -> s.getType() == ScoreType.TECH_FEASIBILITY)
                .forEach(s -> s.applyDifference(new BigDecimal("20")));
        idea.recalculateTotal();
        assertEquals(0, new BigDecimal("75.00").compareTo(idea.getTotalScore())); // (70*3+90)*0.25
    }

    @Test
    void 점수는_0에서_100_사이로_제한된다() {
        Score s = new Idea(null, IdeaType.IT, "p", "t", "s", "e").getScores().get(0);
        s.applyDifference(new BigDecimal("150"));
        assertEquals(0, new BigDecimal("100").compareTo(s.getScore()));
        s.applyDifference(new BigDecimal("-500"));
        assertEquals(0, BigDecimal.ZERO.compareTo(s.getScore()));
    }

    @Test
    void 해시가_바뀌면_변경으로_감지한다() {
        Source src = new Source(null, ApiProvider.GITHUB, "ai tutor");
        assertFalse(src.updateHash("a"));
        assertFalse(src.updateHash("a"));
        assertTrue(src.updateHash("b"));
    }

    @Test
    void 에러코드는_중복되지_않는다() {
        long distinct = Arrays.stream(ErrorCode.values()).map(ErrorCode::getCode).collect(Collectors.toSet()).size();
        assertEquals(ErrorCode.values().length, distinct);
    }

    private static Map<ScoreType, Integer> weights(int a, int b, int c, int d) {
        return Map.of(ScoreType.PROBLEM_IMPORTANCE, a, ScoreType.MARKET_DEMAND, b,
                ScoreType.MARKET_DIFFERENTIATION, c, ScoreType.TECH_FEASIBILITY, d);
    }

    @Test
    void 가중치_변경은_예약만_되고_다음_턴에_적용된다() {
        Idea idea = new Idea(null, IdeaType.IT, "p", "t", "s", "e");
        idea.getScores().forEach(s -> s.applyDifference(new BigDecimal("80")));
        idea.recalculateTotal(); // 80.00

        idea.reserveWeights(weights(10, 10, 10, 70));
        idea.getScores().stream().filter(s -> s.getType() == ScoreType.TECH_FEASIBILITY)
                .forEach(s -> s.applyDifference(new BigDecimal("-30"))); // 50점으로 변경

        // 예약 직후: 현재 weight 그대로, pending에만 저장
        Score tech = idea.getScores().get(3);
        assertEquals(0, new BigDecimal("0.250").compareTo(tech.getWeight()));
        assertEquals(0, new BigDecimal("0.700").compareTo(tech.getPendingWeight()));

        idea.startNextTurn();
        assertEquals(0, new BigDecimal("0.700").compareTo(tech.getWeight()));
        assertNull(tech.getPendingWeight());
        idea.recalculateTotal();
        // 80*0.1*3 + 50*0.7 = 24 + 35 = 59
        assertEquals(0, new BigDecimal("59.00").compareTo(idea.getTotalScore()));
    }

    @Test
    void 가중치_합이_100이_아니거나_항목이_빠지면_거부한다() {
        Idea idea = new Idea(null, IdeaType.IT, "p", "t", "s", "e");
        assertThrows(CustomException.class, () -> idea.reserveWeights(weights(30, 30, 30, 30)));
        assertThrows(CustomException.class, () -> idea.reserveWeights(weights(-10, 40, 40, 30)));
        assertThrows(CustomException.class, () -> idea.reserveWeights(Map.of(ScoreType.MARKET_DEMAND, 100)));
        assertThrows(CustomException.class, () -> idea.reserveWeights(null));
    }
}
