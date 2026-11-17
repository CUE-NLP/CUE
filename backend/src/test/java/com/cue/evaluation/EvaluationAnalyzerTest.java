package com.cue.evaluation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

import com.cue.evaluation.dto.IdeaSnapshot;
import com.cue.evaluation.dto.ScoreChange;
import com.cue.evaluation.service.EvaluationAnalyzer;
import com.cue.global.ai.GeminiClient;
import com.cue.global.ai.PromptLoader;
import com.cue.global.error.CustomException;
import com.cue.global.error.ErrorCode;
import com.cue.score.entity.ScoreType;
import com.cue.source.dto.SourceCheckResult;
import com.cue.source.dto.SourceItem;
import com.cue.source.entity.ApiProvider;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

class EvaluationAnalyzerTest {
    private final GeminiClient gemini = mock(GeminiClient.class);
    private final EvaluationAnalyzer analyzer = new EvaluationAnalyzer(gemini, new PromptLoader());
    private final JsonMapper mapper = JsonMapper.builder().build();

    private static final SourceCheckResult CHANGED = SourceCheckResult.changed(1L, ApiProvider.GITHUB, "agent",
            List.of(new SourceItem("a/b", "https://github.com/a/b", "desc")), LocalDateTime.now());

    private IdeaSnapshot idea(String demandScore) {
        Map<ScoreType, BigDecimal> scores = new EnumMap<>(ScoreType.class);
        for (ScoreType t : ScoreType.values()) scores.put(t, new BigDecimal("50"));
        scores.put(ScoreType.MARKET_DEMAND, new BigDecimal(demandScore));
        return new IdeaSnapshot(7L, "문제", "타깃", "해결", "기대", scores, false);
    }

    private void respond(String json) {
        when(gemini.generateJson(anyString(), anyMap())).thenReturn(mapper.readTree(json));
    }

    @Test
    void 변화량은_기준당_플러스마이너스_10으로_서버에서_다시_제한한다() {
        respond("{\"changes\":[{\"type\":\"MARKET_DEMAND\",\"difference\":35,\"evidenceSummary\":\"크게 늘었다\",\"referenceUrls\":[\"https://github.com/a/b\"]},"
                + "{\"type\":\"TECH_FEASIBILITY\",\"difference\":-50,\"evidenceSummary\":\"위험\"}]}");
        List<ScoreChange> changes = analyzer.reEvaluate(idea("55"), List.of(CHANGED), "요약");

        assertEquals(2, changes.size());
        assertEquals(new BigDecimal("10"), changes.get(0).difference());
        assertEquals(new BigDecimal("-10"), changes.get(1).difference());
        assertEquals("https://github.com/a/b", changes.get(0).references().get(0).url());
        assertEquals("a/b", changes.get(0).references().get(0).title());
        assertEquals(ApiProvider.GITHUB, changes.get(0).references().get(0).provider());
    }

    @Test
    void 변화_0과_알_수_없는_기준과_중복과_입력에_없는_URL은_버린다() {
        respond("{\"changes\":[{\"type\":\"MARKET_DEMAND\",\"difference\":0,\"evidenceSummary\":\"x\"},"
                + "{\"type\":\"WHATEVER\",\"difference\":3,\"evidenceSummary\":\"x\"},"
                + "{\"type\":\"PROBLEM_IMPORTANCE\",\"difference\":4,\"evidenceSummary\":\"첫번째\",\"referenceUrls\":[\"https://evil.example\"]},"
                + "{\"type\":\"PROBLEM_IMPORTANCE\",\"difference\":9,\"evidenceSummary\":\"중복\"},"
                + "{\"type\":\"MARKET_DIFFERENTIATION\",\"difference\":\"abc\",\"evidenceSummary\":\"x\"}]}");
        List<ScoreChange> changes = analyzer.reEvaluate(idea("55"), List.of(CHANGED), null);

        assertEquals(1, changes.size());
        assertEquals(ScoreType.PROBLEM_IMPORTANCE, changes.get(0).type());
        assertEquals(new BigDecimal("4"), changes.get(0).difference());
        assertTrue(changes.get(0).references().isEmpty());
    }

    @Test
    void 빈_changes는_빈_목록이고_형식이_틀리면_E002() {
        respond("{\"changes\":[]}");
        assertTrue(analyzer.reEvaluate(idea("55"), List.of(CHANGED), null).isEmpty());

        respond("{\"foo\":1}");
        assertEquals(ErrorCode.AI_RESPONSE_INVALID, assertThrows(CustomException.class,
                () -> analyzer.reEvaluate(idea("55"), List.of(CHANGED), null)).getErrorCode());
    }

    @Test
    void 재평가에서_근거_요약이_비어_있으면_그_항목을_버린다() {
        respond("{\"changes\":[{\"type\":\"MARKET_DEMAND\",\"difference\":2,\"evidenceSummary\":\"  \"},"
                + "{\"type\":\"PROBLEM_IMPORTANCE\",\"difference\":2}]}");
        assertTrue(analyzer.reEvaluate(idea("55"), List.of(CHANGED), null).isEmpty());
    }

    @Test
    void 근거_요약이_300자를_넘으면_자른다() {
        respond("{\"changes\":[{\"type\":\"MARKET_DEMAND\",\"difference\":2,\"evidenceSummary\":\"" + "가".repeat(500) + "\"}]}");
        String summary = analyzer.reEvaluate(idea("55"), List.of(CHANGED), null).get(0).evidenceSummary();
        assertEquals(EvaluationAnalyzer.MAX_SUMMARY_CHARS, summary.length());
        assertTrue(summary.endsWith("…"));
    }

    @Test
    void 소수_변화량은_정수로_반올림하고_0이_되면_버린다() {
        respond("{\"changes\":[{\"type\":\"MARKET_DEMAND\",\"difference\":3.6,\"evidenceSummary\":\"a\"},"
                + "{\"type\":\"PROBLEM_IMPORTANCE\",\"difference\":0.4,\"evidenceSummary\":\"b\"}]}");
        List<ScoreChange> changes = analyzer.reEvaluate(idea("55"), List.of(CHANGED), null);
        assertEquals(1, changes.size());
        assertEquals(new BigDecimal("4"), changes.get(0).difference());
    }

    @Test
    void 초기_평가는_0에서_100_점수를_현재_점수와의_차이로_바꾼다() {
        respond("{\"scores\":[{\"type\":\"PROBLEM_IMPORTANCE\",\"score\":72,\"evidenceSummary\":\"a\"},"
                + "{\"type\":\"MARKET_DEMAND\",\"score\":55,\"evidenceSummary\":\"b\"},"
                + "{\"type\":\"MARKET_DIFFERENTIATION\",\"score\":150,\"evidenceSummary\":\"c\"},"
                + "{\"type\":\"TECH_FEASIBILITY\",\"score\":50,\"evidenceSummary\":\"d\"}]}");
        List<ScoreChange> changes = analyzer.initialEvaluate(idea("55"), List.of(), null);

        // 현재 점수 50/55/50/50 → 72(+22), 55(변화 없음 제외), 100으로 제한(+50), 50(제외)
        assertEquals(2, changes.size());
        assertEquals(new BigDecimal("22"), changes.get(0).difference());
        assertEquals(ScoreType.MARKET_DIFFERENTIATION, changes.get(1).type());
        assertEquals(new BigDecimal("50"), changes.get(1).difference());
    }

    @Test
    void 프롬프트에_아이디어와_점수와_자료가_채워지고_남은_플레이스홀더가_없다() {
        respond("{\"changes\":[]}");
        analyzer.reEvaluate(idea("55"), List.of(CHANGED), "트렌드 요약문");
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(gemini).generateJson(prompt.capture(), anyMap());
        assertTrue(prompt.getValue().contains("MARKET_DEMAND: 55"));
        assertTrue(prompt.getValue().contains("https://github.com/a/b"));
        assertTrue(prompt.getValue().contains("트렌드 요약문"));
        assertTrue(prompt.getValue().contains("주니어".isEmpty() ? "" : "타깃: 타깃"));
        assertFalse(prompt.getValue().contains("{{"));

        respond("{\"scores\":[]}");
        analyzer.initialEvaluate(idea("55"), List.of(), null);
        verify(gemini, times(2)).generateJson(prompt.capture(), anyMap());
        assertFalse(prompt.getValue().contains("{{"));
        assertTrue(prompt.getValue().contains("(변경된 자료 없음)"));
    }
}
