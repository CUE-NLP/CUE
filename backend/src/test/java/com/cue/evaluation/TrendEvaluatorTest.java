package com.cue.evaluation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cue.evaluation.dto.IdeaSnapshot;
import com.cue.evaluation.dto.ScoreChange;
import com.cue.evaluation.service.TrendEvaluator;
import com.cue.global.ai.GeminiClient;
import com.cue.global.ai.PromptLoader;
import com.cue.score.entity.ScoreType;
import com.cue.source.dto.SourceCheckResult;
import com.cue.source.dto.SourceItem;
import com.cue.source.entity.ApiProvider;
import com.cue.trend.dto.TrendSnapshot;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

class TrendEvaluatorTest {
    private final GeminiClient gemini = mock(GeminiClient.class);
    private final TrendEvaluator evaluator = new TrendEvaluator(gemini, new PromptLoader());
    private final JsonMapper mapper = JsonMapper.builder().build();

    private static final TrendSnapshot TREND = new TrendSnapshot(9L, "에이전트와 소형 모델이 뜬다", LocalDateTime.now(),
            List.of(SourceCheckResult.changed(null, ApiProvider.GITHUB, "",
                    List.of(new SourceItem("a/b", "https://github.com/a/b", "에이전트 프레임워크", "2026-06-01", "stars=900")),
                    LocalDateTime.now())));

    private static IdeaSnapshot idea(String trendScore, Long lastTrendId) {
        Map<ScoreType, BigDecimal> scores = new EnumMap<>(ScoreType.class);
        for (ScoreType t : ScoreType.values()) scores.put(t, new BigDecimal("50"));
        scores.put(ScoreType.TREND, new BigDecimal(trendScore));
        return new IdeaSnapshot(7L, "문제", "타깃", "해결", "기대", scores, false, lastTrendId);
    }

    private void respond(String json) {
        when(gemini.generateJson(anyString(), anyMap())).thenReturn(mapper.readTree(json));
    }

    @Test
    void 첫_평가는_score를_현재_점수와의_차이로_바꾸고_범위를_0에서_100으로_제한한다() {
        respond("{\"score\":65,\"evidenceSummary\":\"흐름과 맞닿음\",\"referenceUrls\":[\"https://github.com/a/b\"]}");
        List<ScoreChange> changes = evaluator.evaluate(idea("0", null), TREND);
        assertEquals(1, changes.size());
        ScoreChange c = changes.get(0);
        assertEquals(ScoreType.TREND, c.type());
        assertEquals(new BigDecimal("65"), c.difference());
        assertEquals(9L, c.trendId());
        assertEquals("https://github.com/a/b", c.references().get(0).url());
        assertEquals(ApiProvider.GITHUB, c.references().get(0).provider());

        respond("{\"score\":250,\"evidenceSummary\":\"x\"}");
        assertEquals(new BigDecimal("100"), evaluator.evaluate(idea("0", null), TREND).get(0).difference());
        respond("{\"score\":-5,\"evidenceSummary\":\"x\"}");
        assertEquals(new BigDecimal("-30"), evaluator.evaluate(idea("30", null), TREND).get(0).difference()); // 0점으로 → 변화량 -30
    }

    @Test
    void 재평가도_새_점수를_받아_변화_폭_제한_없이_차이로_바꾸고_같으면_변화_없음() {
        respond("{\"score\":95,\"evidenceSummary\":\"크게 늘었다\"}");
        assertEquals(new BigDecimal("35"), evaluator.evaluate(idea("60", 3L), TREND).get(0).difference());
        respond("{\"score\":10,\"evidenceSummary\":\"식었다\"}");
        assertEquals(new BigDecimal("-50"), evaluator.evaluate(idea("60", 3L), TREND).get(0).difference());
        respond("{\"score\":60,\"evidenceSummary\":\"영향 없음\"}");
        assertTrue(evaluator.evaluate(idea("60", 3L), TREND).isEmpty());
    }

    @Test
    void 점수가_숫자가_아니거나_재평가에_근거가_없으면_변화_없음() {
        respond("{\"evidenceSummary\":\"x\"}"); // score가 없다
        assertTrue(evaluator.evaluate(idea("0", null), TREND).isEmpty());
        respond("{\"score\":\"많이\",\"evidenceSummary\":\"x\"}");
        assertTrue(evaluator.evaluate(idea("50", 3L), TREND).isEmpty());
        respond("{\"score\":55,\"evidenceSummary\":\" \"}"); // 재평가는 근거 없는 변동 금지
        assertTrue(evaluator.evaluate(idea("50", 3L), TREND).isEmpty());
    }

    @Test
    void 근거_URL은_트렌드_항목에_있는_것만_남기고_근거_요약은_300자로_자른다() {
        respond("{\"score\":70,\"evidenceSummary\":\"" + "가".repeat(400) + "\","
                + "\"referenceUrls\":[\"https://github.com/a/b\",\"https://github.com/a/b\",\"https://made-up.test/x\"]}");
        ScoreChange c = evaluator.evaluate(idea("0", null), TREND).get(0);
        assertEquals(1, c.references().size());
        assertEquals(300, c.evidenceSummary().length());
    }

    @Test
    void 프롬프트에_아이디어와_현재_점수와_트렌드가_채워지고_남은_플레이스홀더가_없다() {
        respond("{\"score\":70,\"evidenceSummary\":\"x\"}");
        evaluator.evaluate(idea("0", null), TREND);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(gemini).generateJson(prompt.capture(), anyMap());
        assertTrue(prompt.getValue().contains("없음 (첫 평가)"));
        assertTrue(prompt.getValue().contains("에이전트와 소형 모델이 뜬다"));
        assertTrue(prompt.getValue().contains("[GITHUB] a/b | https://github.com/a/b | 2026-06-01 | stars=900 | 에이전트 프레임워크"));
        assertTrue(prompt.getValue().contains("타깃: 타깃"));
        assertFalse(prompt.getValue().contains("{{"));

        evaluator.evaluate(idea("42", 3L), TREND);
        verify(gemini, times(2)).generateJson(prompt.capture(), anyMap());
        assertTrue(prompt.getValue().contains("[현재 TREND 점수]\n42"));
    }
}
