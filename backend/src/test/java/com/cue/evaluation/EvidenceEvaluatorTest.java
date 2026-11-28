package com.cue.evaluation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

import com.cue.evaluation.dto.IdeaSnapshot;
import com.cue.evaluation.dto.PastEvaluation;
import com.cue.evaluation.dto.ReferenceInput;
import com.cue.evaluation.dto.ScoreChange;
import com.cue.evaluation.service.EvidenceEvaluator;
import com.cue.global.ai.GeminiClient;
import com.cue.global.ai.PromptLoader;
import com.cue.global.error.CustomException;
import com.cue.global.error.ErrorCode;
import com.cue.score.entity.ScoreType;
import com.cue.source.dto.EvaluatedItem;
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

class EvidenceEvaluatorTest {
    private final GeminiClient gemini = mock(GeminiClient.class);
    private final EvidenceEvaluator analyzer = new EvidenceEvaluator(gemini, new PromptLoader());
    private final JsonMapper mapper = JsonMapper.builder().build();

    private static final SourceCheckResult CHANGED = SourceCheckResult.changed(1L, ApiProvider.GITHUB, "agent",
            List.of(new SourceItem("a/b", "https://github.com/a/b", "desc")), LocalDateTime.now());

    private IdeaSnapshot idea(String demandScore) {
        Map<ScoreType, BigDecimal> scores = new EnumMap<>(ScoreType.class);
        for (ScoreType t : ScoreType.values()) scores.put(t, new BigDecimal("50"));
        scores.put(ScoreType.MARKET_DEMAND, new BigDecimal(demandScore));
        return new IdeaSnapshot(7L, "문제", "타깃", "해결", "기대", scores, false, null);
    }

    /** 새로 추가된 자료만 있는 재평가. */
    private List<ScoreChange> reEvaluate() {
        return analyzer.reEvaluate(idea("55"), List.of(), List.of(CHANGED), List.of(), List.of());
    }

    private void respond(String json) {
        when(gemini.generateJson(anyString(), anyMap())).thenReturn(mapper.readTree(json));
    }

    @Test
    void 재평가는_새_점수를_받아_변화_폭_제한_없이_현재_점수와의_차이로_바꾼다() {
        respond("{\"changes\":[{\"type\":\"MARKET_DEMAND\",\"score\":95,\"evidenceSummary\":\"크게 늘었다\",\"referenceUrls\":[\"https://github.com/a/b\"]},"
                + "{\"type\":\"TECH_FEASIBILITY\",\"score\":-20,\"evidenceSummary\":\"위험\"}]}");
        List<ScoreChange> changes = reEvaluate();

        assertEquals(2, changes.size());
        assertEquals(new BigDecimal("40"), changes.get(0).difference());   // 55 → 95
        assertEquals(new BigDecimal("-50"), changes.get(1).difference());  // 50 → 0 (범위 제한)
        assertEquals("https://github.com/a/b", changes.get(0).references().get(0).url());
        assertEquals("a/b", changes.get(0).references().get(0).title());
        assertEquals(ApiProvider.GITHUB, changes.get(0).references().get(0).provider());
    }

    @Test
    void 같은_점수와_알_수_없는_기준과_중복과_입력에_없는_URL은_버린다() {
        respond("{\"changes\":[{\"type\":\"MARKET_DEMAND\",\"score\":55,\"evidenceSummary\":\"x\"},"
                + "{\"type\":\"WHATEVER\",\"score\":3,\"evidenceSummary\":\"x\"},"
                + "{\"type\":\"PROBLEM_IMPORTANCE\",\"score\":54,\"evidenceSummary\":\"첫번째\",\"referenceUrls\":[\"https://evil.example\"]},"
                + "{\"type\":\"PROBLEM_IMPORTANCE\",\"score\":59,\"evidenceSummary\":\"중복\"},"
                + "{\"type\":\"MARKET_DIFFERENTIATION\",\"score\":\"abc\",\"evidenceSummary\":\"x\"}]}");
        List<ScoreChange> changes = reEvaluate();

        assertEquals(1, changes.size());
        assertEquals(ScoreType.PROBLEM_IMPORTANCE, changes.get(0).type());
        assertEquals(new BigDecimal("4"), changes.get(0).difference());
        assertTrue(changes.get(0).references().isEmpty());
    }

    @Test
    void 그대로인_자료도_넘기면_프롬프트에_넣고_근거_URL로_받는다() {
        respond("{\"changes\":[{\"type\":\"MARKET_DEMAND\",\"score\":60,\"evidenceSummary\":\"a\",\"referenceUrls\":[\"https://github.com/a/b\"]}]}");

        List<ScoreChange> changes = analyzer.reEvaluate(idea("55"), List.of(), List.of(), List.of(CHANGED), List.of());

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(gemini).generateJson(prompt.capture(), anyMap());
        assertTrue(section(prompt.getValue(), "[그대로인 자료]", "[삭제된 자료]").contains("https://github.com/a/b"));
        assertEquals("https://github.com/a/b", changes.get(0).references().get(0).url());
    }

    @Test
    void 빈_changes는_빈_목록이고_형식이_틀리면_E002() {
        respond("{\"changes\":[]}");
        assertTrue(reEvaluate().isEmpty());

        respond("{\"foo\":1}");
        assertEquals(ErrorCode.AI_RESPONSE_INVALID, assertThrows(CustomException.class,
                () -> reEvaluate()).getErrorCode());
    }

    @Test
    void 재평가에서_근거_요약이_비어_있으면_그_항목을_버린다() {
        respond("{\"changes\":[{\"type\":\"MARKET_DEMAND\",\"score\":57,\"evidenceSummary\":\"  \"},"
                + "{\"type\":\"PROBLEM_IMPORTANCE\",\"score\":57}]}");
        assertTrue(reEvaluate().isEmpty());
    }

    @Test
    void 근거_요약이_300자를_넘으면_자른다() {
        respond("{\"changes\":[{\"type\":\"MARKET_DEMAND\",\"score\":57,\"evidenceSummary\":\"" + "가".repeat(500) + "\"}]}");
        String summary = reEvaluate().get(0).evidenceSummary();
        assertEquals(EvidenceEvaluator.MAX_SUMMARY_CHARS, summary.length());
        assertTrue(summary.endsWith("…"));
    }

    @Test
    void 소수_점수는_정수로_반올림하고_현재_점수와_같아지면_버린다() {
        respond("{\"changes\":[{\"type\":\"MARKET_DEMAND\",\"score\":58.6,\"evidenceSummary\":\"a\"},"
                + "{\"type\":\"PROBLEM_IMPORTANCE\",\"score\":50.4,\"evidenceSummary\":\"b\"}]}");
        List<ScoreChange> changes = reEvaluate();
        assertEquals(1, changes.size());
        assertEquals(new BigDecimal("4"), changes.get(0).difference());
    }

    @Test
    void 초기_평가는_0에서_100_점수를_현재_점수와의_차이로_바꾼다() {
        respond("{\"scores\":[{\"type\":\"PROBLEM_IMPORTANCE\",\"score\":72,\"evidenceSummary\":\"a\"},"
                + "{\"type\":\"MARKET_DEMAND\",\"score\":55,\"evidenceSummary\":\"b\"},"
                + "{\"type\":\"MARKET_DIFFERENTIATION\",\"score\":150,\"evidenceSummary\":\"c\"},"
                + "{\"type\":\"TECH_FEASIBILITY\",\"score\":50,\"evidenceSummary\":\"d\"}]}");
        List<ScoreChange> changes = analyzer.initialEvaluate(idea("55"), List.of());

        // 현재 점수 50/55/50/50 → 72(+22), 55(변화 없음 제외), 100으로 제한(+50), 50(제외)
        assertEquals(2, changes.size());
        assertEquals(new BigDecimal("22"), changes.get(0).difference());
        assertEquals(ScoreType.MARKET_DIFFERENTIATION, changes.get(1).type());
        assertEquals(new BigDecimal("50"), changes.get(1).difference());
    }

    @Test
    void 프롬프트에_아이디어와_점수와_자료가_채워지고_남은_플레이스홀더가_없다() {
        respond("{\"changes\":[]}");
        reEvaluate();
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(gemini).generateJson(prompt.capture(), anyMap());
        assertTrue(prompt.getValue().contains("MARKET_DEMAND: 55"));
        assertTrue(prompt.getValue().contains("https://github.com/a/b"));
        assertFalse(prompt.getValue().contains("TREND")); // 키워드 근거 평가는 4개 기준만 다룬다
        assertTrue(prompt.getValue().contains("주니어".isEmpty() ? "" : "타깃: 타깃"));
        assertFalse(prompt.getValue().contains("{{"));

        respond("{\"scores\":[]}");
        analyzer.initialEvaluate(idea("55"), List.of());
        verify(gemini, times(2)).generateJson(prompt.capture(), anyMap());
        assertFalse(prompt.getValue().contains("{{"));
        assertTrue(prompt.getValue().contains("(수집된 자료 없음)"));
    }

    @Test
    void 재평가_프롬프트에_기존_평가_내역과_새_자료와_삭제된_자료를_나눠_넣는다() {
        var past = new PastEvaluation(ScoreType.MARKET_DEMAND, LocalDateTime.of(2026, 11, 1, 3, 0), new BigDecimal("60.00"),
                new BigDecimal("60.00"), "기존 저장소에서 수요가 확인돼요.",
                List.of(new ReferenceInput("https://x.test/gone", "사라진 저장소", ApiProvider.GITHUB),
                        new ReferenceInput("https://x.test/kept", "남은 저장소", ApiProvider.GITHUB)));
        respond("{\"changes\":[{\"type\":\"MARKET_DEMAND\",\"score\":53,\"evidenceSummary\":\"줄었다\","
                + "\"referenceUrls\":[\"https://github.com/a/b\",\"https://x.test/gone\",\"https://x.test/kept\"]}]}");

        List<ScoreChange> changes = analyzer.reEvaluate(idea("55"), List.of(past), List.of(CHANGED), List.of(),
                List.of(new EvaluatedItem("https://x.test/gone", "사라진 저장소")));

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(gemini).generateJson(prompt.capture(), anyMap());
        String text = prompt.getValue();
        String history = section(text, "[기존 평가 내역]", "[새로 추가된 자료]");
        assertTrue(history.contains("MARKET_DEMAND | 2026-11-01 | 변화 +60 → 60점 | 기존 저장소에서 수요가 확인돼요."));
        assertTrue(history.contains("- 근거 자료(삭제됨): 사라진 저장소 | https://x.test/gone"));
        assertTrue(history.contains("- 근거 자료: 남은 저장소 | https://x.test/kept"));
        String added = section(text, "[새로 추가된 자료]", "[그대로인 자료]");
        assertTrue(added.contains("https://github.com/a/b"));
        assertFalse(added.contains("https://x.test/"));
        assertTrue(text.substring(text.lastIndexOf("[삭제된 자료]")).contains("사라진 저장소 | https://x.test/gone"));
        // 근거 URL은 새로 추가된 자료의 것만 남는다
        assertEquals(List.of("https://github.com/a/b"),
                changes.get(0).references().stream().map(ReferenceInput::url).toList());
    }

    /** 프롬프트 끝의 입력 영역에서 제목 두 개 사이 (규칙 설명에도 같은 제목이 나오므로 마지막 것을 쓴다). */
    private static String section(String text, String from, String to) {
        return text.substring(text.lastIndexOf(from), text.lastIndexOf(to));
    }

    @Test
    void 재평가_응답에_근거_URL_배열을_요구한다() {
        respond("{\"changes\":[]}");
        reEvaluate();
        ArgumentCaptor<Map<String, Object>> schema = ArgumentCaptor.captor();
        verify(gemini).generateJson(anyString(), schema.capture());
        assertTrue(mapper.valueToTree(schema.getValue()).at("/properties/changes/items/required").toString()
                .contains("referenceUrls"));
    }

    @Test
    void 키워드_근거_평가는_TREND_기준을_받아들이지_않는다() {
        respond("{\"changes\":[{\"type\":\"TREND\",\"score\":55,\"evidenceSummary\":\"x\"},"
                + "{\"type\":\"MARKET_DEMAND\",\"score\":57,\"evidenceSummary\":\"y\"}]}");
        List<ScoreChange> changes = reEvaluate();
        assertEquals(1, changes.size());
        assertEquals(ScoreType.MARKET_DEMAND, changes.get(0).type());

        respond("{\"scores\":[{\"type\":\"TREND\",\"score\":90,\"evidenceSummary\":\"x\"}]}");
        assertTrue(analyzer.initialEvaluate(idea("55"), List.of(CHANGED)).isEmpty());
    }
}
