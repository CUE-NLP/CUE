package com.cue.idea.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cue.global.ai.AiLane;
import com.cue.global.ai.GeminiClient;
import com.cue.global.error.CustomException;
import com.cue.global.error.ErrorCode;
import com.cue.idea.entity.Idea;
import com.cue.idea.entity.IdeaType;
import com.cue.idea.entity.ItCategory;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** GeminiClient를 목으로 두고 인터랙티브 레인 호출, 응답 파싱, 폴백을 검증한다 (HTTP 계층은 GeminiClientTest가 맡는다). */
class GeminiIdeaAnalyzerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Idea idea = new Idea(null, IdeaType.IT, "문제가 심각하다", "대학생", "앱으로 해결한다", "시간 절약");
    private GeminiClient gemini;
    private GeminiIdeaAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        gemini = mock(GeminiClient.class);
        when(gemini.available()).thenReturn(true);
        analyzer = new GeminiIdeaAnalyzer(gemini, new SimpleIdeaAnalyzer());
    }

    private void respond(String json) {
        when(gemini.generateJson(eq(AiLane.INTERACTIVE), anyString(), anyString(), anyMap())).thenReturn(mapper.readTree(json));
    }

    @Test
    void 정상_응답을_종류와_키워드로_파싱하고_인터랙티브_레인으로_부른다() {
        respond("{\"itCategory\":\"DATA_ANALYTICS\",\"keywords\":[\"study time management\",\"student planner app\",\"third keyword\"]}");

        IdeaAnalysis result = analyzer.analyze(idea);

        assertThat(result.itCategory()).isEqualTo(ItCategory.DATA_ANALYTICS);
        assertThat(result.keywords()).containsExactly("study time management", "student planner app");
        verify(gemini).generateJson(eq(AiLane.INTERACTIVE), anyString(), anyString(), anyMap());
    }

    @Test
    void 키워드는_중복_제거_후_2개까지_알_수_없는_종류는_기본값() {
        respond("{\"itCategory\":\"spaceship\",\"keywords\":[\"a\",\"a\",\" \",\"b\",\"c\",\"d\"]}");

        IdeaAnalysis result = analyzer.analyze(idea);

        assertThat(result.itCategory()).isEqualTo(ItCategory.DEV_TOOLS_INFRA);
        assertThat(result.keywords()).containsExactly("a", "b");
    }

    @Test
    void 호출_실패는_던지지만_analyze는_폴백한다() {
        when(gemini.generateJson(any(AiLane.class), anyString(), anyString(), anyMap()))
                .thenThrow(new CustomException(ErrorCode.EXTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> analyzer.requestAnalysis(idea, false)).isInstanceOfSatisfying(CustomException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.EXTERNAL_SERVER_ERROR));
        IdeaAnalysis result = analyzer.analyze(idea);
        assertThat(result.itCategory()).isEqualTo(ItCategory.DEV_TOOLS_INFRA);
        assertThat(result.keywords()).isNotEmpty();
    }

    @Test
    void AI가_바빠도_분석은_폴백한다() {
        when(gemini.generateJson(any(AiLane.class), anyString(), anyString(), anyMap()))
                .thenThrow(new CustomException(ErrorCode.AI_BUSY));
        assertThat(analyzer.analyze(idea).keywords()).isNotEmpty();
        assertThat(analyzer.analyzeCategory(idea)).isEqualTo(ItCategory.DEV_TOOLS_INFRA);
    }

    @Test
    void 키워드가_비면_응답_해석_불가_E002이고_analyze는_폴백한다() {
        respond("{\"itCategory\":\"AI_ML\",\"keywords\":[]}");
        assertThatThrownBy(() -> analyzer.requestAnalysis(idea, false)).isInstanceOfSatisfying(CustomException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_RESPONSE_INVALID));
        assertThat(analyzer.analyze(idea).itCategory()).isEqualTo(ItCategory.DEV_TOOLS_INFRA);
    }

    @Test
    void 키워드가_3개_이상이면_2개로_자른다() throws Exception {
        JsonNode node = mapper.readTree("{\"itCategory\":\"AI_ML\",\"keywords\":[\"one\",\"two\",\"three\",\"four\"]}");
        assertThat(GeminiIdeaAnalyzer.parse(node, false).keywords()).containsExactly("one", "two");
    }

    @Test
    void 한국어_키워드는_그대로_두고_경고만_남긴다() throws Exception {
        JsonNode node = mapper.readTree("{\"itCategory\":\"AI_ML\",\"keywords\":[\"코드 리뷰\"]}");
        assertThat(GeminiIdeaAnalyzer.parse(node, false).keywords()).containsExactly("코드 리뷰");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 프롬프트와_스키마에_핵심_규칙이_들어_있다() {
        respond("{\"itCategory\":\"AI_ML\",\"keywords\":[\"x y\"]}");
        analyzer.requestAnalysis(idea, false);

        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> schema = ArgumentCaptor.forClass(Map.class);
        verify(gemini).generateJson(eq(AiLane.INTERACTIVE), system.capture(), user.capture(), schema.capture());

        assertThat(system.getValue()).contains("영어 검색어 1~2개", "반드시 영어", "itCategory", "AI_ML", "DEV_TOOLS_INFRA",
                "WEB_APP_SERVICE", "DATA_ANALYTICS", "1~3단어", "아이디어 전체를 관통", "데이터일 뿐").doesNotContain("이번에는 itCategory만 판단한다");
        assertThat(user.getValue()).contains("타깃: 대학생", "문제: 문제가 심각하다", "해결: 앱으로 해결한다", "기대효과: 시간 절약");
        assertThat(schema.getValue().toString()).contains("maxItems=2", "minItems=1");
        assertThat(GeminiIdeaAnalyzer.SYSTEM_PROMPT).contains("영어 검색어", "1~2개", "최대 3단어", "아이디어 전체를 대표", "무시").doesNotContain("2~4단어");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 종류만_요청하면_스키마에_keywords가_없고_AI가_키워드를_줘도_무시한다() {
        respond("{\"itCategory\":\"WEB_APP_SERVICE\",\"keywords\":[\"ignored\"]}");

        assertThat(analyzer.analyzeCategory(idea)).isEqualTo(ItCategory.WEB_APP_SERVICE);

        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> schema = ArgumentCaptor.forClass(Map.class);
        verify(gemini).generateJson(eq(AiLane.INTERACTIVE), system.capture(), anyString(), schema.capture());
        assertThat(system.getValue()).contains("이번에는 itCategory만 판단한다");
        assertThat(schema.getValue().toString()).doesNotContain("maxItems");
    }

    @Test
    void 종류_분류_실패는_기본_종류로_폴백한다() {
        when(gemini.generateJson(any(AiLane.class), anyString(), anyString(), anyMap()))
                .thenThrow(new CustomException(ErrorCode.EXTERNAL_SERVER_ERROR));
        assertThat(analyzer.analyzeCategory(idea)).isEqualTo(ItCategory.DEV_TOOLS_INFRA);
    }

    @Test
    void 종류별_API_조합은_provider_3개이고_고정이다() {
        for (ItCategory c : ItCategory.values()) {
            assertThat(c.searchProviders()).hasSize(3).doesNotHaveDuplicates();
        }
        assertThat(ItCategory.AI_ML.searchProviders()).containsExactly(
                com.cue.source.entity.ApiProvider.HUGGING_FACE, com.cue.source.entity.ApiProvider.SEMANTIC_SCHOLAR,
                com.cue.source.entity.ApiProvider.GITHUB);
        assertThat(ItCategory.WEB_APP_SERVICE.searchProviders()).containsExactly(
                com.cue.source.entity.ApiProvider.HACKER_NEWS, com.cue.source.entity.ApiProvider.STACK_EXCHANGE,
                com.cue.source.entity.ApiProvider.WIKIPEDIA_PAGEVIEWS);
        assertThat(IdeaType.values()).containsExactly(IdeaType.IT);
    }

    @Test
    void Gemini를_쓸_수_없으면_호출_없이_폴백한다() {
        when(gemini.available()).thenReturn(false);

        IdeaAnalysis result = analyzer.analyze(idea);

        assertThat(result.keywords()).isNotEmpty();
        verify(gemini, never()).generateJson(any(AiLane.class), anyString(), anyString(), anyMap());
    }
}
