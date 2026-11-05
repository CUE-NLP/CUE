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
import java.util.Arrays;
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
}
