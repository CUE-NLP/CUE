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
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class EvidenceEvaluatorTest {
    private static final String INITIAL_SCORES = """
            {"scores":{
              "PROBLEM_IMPORTANCE":{"score":72,"evidenceSummary":"a","referenceIds":[]},
              "MARKET_DEMAND":{"score":55,"evidenceSummary":"b","referenceIds":[]},
              "MARKET_DIFFERENTIATION":{"score":150,"evidenceSummary":"c","referenceIds":[]},
              "TECH_FEASIBILITY":{"score":50,"evidenceSummary":"d","referenceIds":[]}
            }}
            """;

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
        return analyzer.reEvaluate(idea("55"), List.of(), List.of(CHANGED), List.of());
    }

    private void respond(String json) {
        when(gemini.generateJson(anyString(), anyMap())).thenReturn(mapper.readTree(json));
    }

    @Test
    void 변화량은_기준당_플러스마이너스_10으로_서버에서_다시_제한한다() {
        respond("{\"changes\":[{\"type\":\"MARKET_DEMAND\",\"difference\":35,\"evidenceSummary\":\"크게 늘었다\",\"referenceIds\":[1]},"
                + "{\"type\":\"TECH_FEASIBILITY\",\"difference\":-50,\"evidenceSummary\":\"위험\"}]}");
        List<ScoreChange> changes = reEvaluate();

        assertEquals(2, changes.size());
        assertEquals(new BigDecimal("10"), changes.get(0).difference());
        assertEquals(new BigDecimal("-10"), changes.get(1).difference());
        assertEquals("https://github.com/a/b", changes.get(0).references().get(0).url());
        assertEquals("a/b", changes.get(0).references().get(0).title());
        assertEquals(ApiProvider.GITHUB, changes.get(0).references().get(0).provider());
    }

    @Test
    void 변화_0과_알_수_없는_기준과_중복과_입력에_없는_ID은_버린다() {
        respond("{\"changes\":[{\"type\":\"MARKET_DEMAND\",\"difference\":0,\"evidenceSummary\":\"x\"},"
                + "{\"type\":\"WHATEVER\",\"difference\":3,\"evidenceSummary\":\"x\"},"
                + "{\"type\":\"PROBLEM_IMPORTANCE\",\"difference\":4,\"evidenceSummary\":\"첫번째\",\"referenceIds\":[99]},"
                + "{\"type\":\"PROBLEM_IMPORTANCE\",\"difference\":9,\"evidenceSummary\":\"중복\"},"
                + "{\"type\":\"MARKET_DIFFERENTIATION\",\"difference\":\"abc\",\"evidenceSummary\":\"x\"}]}");
        List<ScoreChange> changes = reEvaluate();

        assertEquals(1, changes.size());
        assertEquals(ScoreType.PROBLEM_IMPORTANCE, changes.get(0).type());
        assertEquals(new BigDecimal("4"), changes.get(0).difference());
        assertTrue(changes.get(0).references().isEmpty());
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
        respond("{\"changes\":[{\"type\":\"MARKET_DEMAND\",\"difference\":2,\"evidenceSummary\":\"  \"},"
                + "{\"type\":\"PROBLEM_IMPORTANCE\",\"difference\":2}]}");
        assertTrue(reEvaluate().isEmpty());
    }

    @Test
    void 근거_요약이_300자를_넘으면_자른다() {
        respond("{\"changes\":[{\"type\":\"MARKET_DEMAND\",\"difference\":2,\"evidenceSummary\":\"" + "가".repeat(500) + "\"}]}");
        String summary = reEvaluate().get(0).evidenceSummary();
        assertEquals(EvidenceEvaluator.MAX_SUMMARY_CHARS, summary.length());
        assertTrue(summary.endsWith("…"));
    }

    @Test
    void 소수_변화량은_정수로_반올림하고_0이_되면_버린다() {
        respond("{\"changes\":[{\"type\":\"MARKET_DEMAND\",\"difference\":3.6,\"evidenceSummary\":\"a\"},"
                + "{\"type\":\"PROBLEM_IMPORTANCE\",\"difference\":0.4,\"evidenceSummary\":\"b\"}]}");
        List<ScoreChange> changes = reEvaluate();
        assertEquals(1, changes.size());
        assertEquals(new BigDecimal("4"), changes.get(0).difference());
    }

    @Test
    void 초기_평가는_0에서_100_점수를_현재_점수와의_차이로_바꾼다() {
        respond(INITIAL_SCORES);
        List<ScoreChange> changes = analyzer.initialEvaluate(idea("55"), List.of());

        // 현재 점수 50/55/50/50 → 72(+22), 55(변화 없음 제외), 100으로 제한(+50), 50(제외)
        assertEquals(2, changes.size());
        assertEquals(new BigDecimal("22"), changes.get(0).difference());
        assertEquals(ScoreType.MARKET_DIFFERENTIATION, changes.get(1).type());
        assertEquals(new BigDecimal("50"), changes.get(1).difference());
    }

    @Test
    void 프롬프트에_노출한_ID만_수집한_원래_URL로_연결한다() {
        String base = "https://docs.example.test/item/";
        List<SourceItem> items = IntStream.rangeClosed(1, 31)
                .mapToObj(id -> new SourceItem("자료 " + id, base + id + "?lang=en-US&v=1.0", "발췌"))
                .toList();
        var source = SourceCheckResult.changed(1L, ApiProvider.GITHUB, "agent", items, LocalDateTime.now());
        var reply = mapper.readTree(INITIAL_SCORES);
        ((ObjectNode) reply.at("/scores/PROBLEM_IMPORTANCE")).putArray("referenceIds")
                .add(30).add(31).add(30).add(4_294_967_326L);
        respond(reply.toString());

        var changes = analyzer.initialEvaluate(idea("55"), List.of(source));

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(gemini).generateJson(prompt.capture(), anyMap());
        assertTrue(prompt.getValue().contains("30 | [GITHUB] 자료 30 | " + items.get(29).url()));
        assertFalse(prompt.getValue().contains(items.get(30).url()));
        assertEquals(List.of(new ReferenceInput(items.get(29).url(), "자료 30", ApiProvider.GITHUB)),
                changes.getFirst().references());
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

        respond(INITIAL_SCORES);
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
        respond("{\"changes\":[{\"type\":\"MARKET_DEMAND\",\"difference\":-2,\"evidenceSummary\":\"줄었다\","
                + "\"referenceIds\":[1,2,3]}]}");

        List<ScoreChange> changes = analyzer.reEvaluate(idea("55"), List.of(past), List.of(CHANGED),
                List.of(new EvaluatedItem("https://x.test/gone", "사라진 저장소")));

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(gemini).generateJson(prompt.capture(), anyMap());
        String text = prompt.getValue();
        String history = section(text, "[기존 평가 내역]", "[새로 추가된 자료]");
        assertTrue(history.contains("MARKET_DEMAND | 2026-11-01 | 변화 +60 → 60점 | 기존 저장소에서 수요가 확인돼요."));
        assertTrue(history.contains("- 근거 자료(삭제됨): 사라진 저장소 | https://x.test/gone"));
        assertTrue(history.contains("- 근거 자료: 남은 저장소 | https://x.test/kept"));
        String added = section(text, "[새로 추가된 자료]", "[삭제된 자료]");
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
    void 재평가_응답에_근거_ID_배열을_요구한다() {
        respond("{\"changes\":[]}");
        reEvaluate();
        ArgumentCaptor<Map<String, Object>> schema = ArgumentCaptor.captor();
        verify(gemini).generateJson(anyString(), schema.capture());
        assertTrue(mapper.valueToTree(schema.getValue()).at("/properties/changes/items/required").toString()
                .contains("referenceIds"));
    }

    @Test
    void 키워드_근거_평가는_TREND_기준을_받아들이지_않는다() {
        respond("{\"changes\":[{\"type\":\"TREND\",\"difference\":5,\"evidenceSummary\":\"x\"},"
                + "{\"type\":\"MARKET_DEMAND\",\"difference\":2,\"evidenceSummary\":\"y\"}]}");
        List<ScoreChange> changes = reEvaluate();
        assertEquals(1, changes.size());
        assertEquals(ScoreType.MARKET_DEMAND, changes.get(0).type());

        respond(INITIAL_SCORES.replace("TECH_FEASIBILITY", "TREND"));
        assertEquals(ErrorCode.AI_RESPONSE_INVALID, assertThrows(CustomException.class,
                () -> analyzer.initialEvaluate(idea("55"), List.of(CHANGED))).getErrorCode());
    }

    @Test
    void 점수_근거_또는_출처_배열이_잘못되면_전체_평가를_거절한다() {
        for (String invalid : List.of(INITIAL_SCORES.replace("72", "\"72\""),
                INITIAL_SCORES.replace("\"a\"", "\"  \""),
                INITIAL_SCORES.replace("[]", "null"), INITIAL_SCORES.replace("[]", "[\"3\"]"))) {
            respond(invalid);
            assertEquals(ErrorCode.AI_RESPONSE_INVALID, assertThrows(CustomException.class,
                    () -> analyzer.initialEvaluate(idea("55"), List.of(CHANGED))).getErrorCode());
        }
    }
}
