package com.cue.idea.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.cue.global.error.CustomException;
import com.cue.global.error.ErrorCode;
import com.cue.idea.entity.Idea;
import com.cue.idea.entity.IdeaType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/** 실제 Gemini를 호출하지 않고 MockRestServiceServer로 요청/응답 파싱과 폴백을 검증한다. */
class GeminiIdeaAnalyzerTest {
    private static final String URL =
            "https://gemini.test/v1beta/models/test-model:generateContent";

    private final ObjectMapper mapper = new ObjectMapper();
    private final Idea idea = new Idea(null, IdeaType.IT, "문제가 심각하다", "대학생", "앱으로 해결한다", "시간 절약");
    private MockRestServiceServer server;
    private GeminiIdeaAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gemini.test");
        server = MockRestServiceServer.bindTo(builder).build();
        analyzer = analyzer(builder, "test-key");
    }

    private GeminiIdeaAnalyzer analyzer(RestClient.Builder builder, String apiKey) {
        GeminiProperties props = new GeminiProperties(true, apiKey, "test-model", "https://gemini.test", 5);
        return new GeminiIdeaAnalyzer(builder.build(), mapper, props, new SimpleIdeaAnalyzer());
    }

    /** Gemini generateContent 응답 형태: 구조화 JSON은 candidates[0].content.parts[0].text 문자열 안에 들어 있다. */
    private String envelope(String innerJson) {
        return mapper.writeValueAsString(java.util.Map.of("candidates", java.util.List.of(java.util.Map.of(
                "content", java.util.Map.of("parts", java.util.List.of(java.util.Map.of("text", innerJson)))))));
    }

    @Test
    void 정상_응답을_종류와_키워드로_파싱한다() {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-goog-api-key", "test-key"))
                .andRespond(withSuccess(
                        envelope("{\"type\":\"EDUCATION\",\"keywords\":[\"학습 앱\",\"대학생 시간관리\",\"스터디 플래너\"]}"),
                        MediaType.APPLICATION_JSON));

        IdeaAnalysis result = analyzer.analyze(idea);

        assertThat(result.type()).isEqualTo(IdeaType.EDUCATION);
        assertThat(result.keywords()).containsExactly("학습 앱", "대학생 시간관리", "스터디 플래너");
        server.verify();
    }

    @Test
    void 키워드는_중복_제거_후_3개까지_알_수_없는_종류는_OTHER() {
        server.expect(requestTo(URL)).andRespond(withSuccess(
                envelope("{\"type\":\"spaceship\",\"keywords\":[\"a\",\"a\",\" \",\"b\",\"c\",\"d\"]}"),
                MediaType.APPLICATION_JSON));

        IdeaAnalysis result = analyzer.analyze(idea);

        assertThat(result.type()).isEqualTo(IdeaType.OTHER);
        assertThat(result.keywords()).containsExactly("a", "b", "c");
    }

    @Test
    void 호출_실패는_G002로_던지지만_analyze는_폴백한다() {
        server.expect(requestTo(URL)).andRespond(withServerError());
        assertThatThrownBy(() -> analyzer.requestAnalysis(idea))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.EXTERNAL_SERVER_ERROR));

        server.reset();
        server.expect(requestTo(URL)).andRespond(withServerError());
        IdeaAnalysis result = analyzer.analyze(idea);
        assertThat(result.type()).isEqualTo(IdeaType.IT);
        assertThat(result.keywords()).isNotEmpty();
    }

    @Test
    void 파싱_실패는_폴백한다() {
        assertThatThrownBy(() -> analyzer.parse("not json"))
                .isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> analyzer.parse(envelope("{\"type\":\"IT\",\"keywords\":[]}")))
                .isInstanceOf(CustomException.class);

        server.expect(requestTo(URL)).andRespond(withSuccess("{\"candidates\":[]}", MediaType.APPLICATION_JSON));
        assertThat(analyzer.analyze(idea).type()).isEqualTo(IdeaType.IT);
    }

    @Test
    void API_키가_없으면_호출_없이_폴백한다() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gemini.test");
        MockRestServiceServer noCall = MockRestServiceServer.bindTo(builder).build();

        IdeaAnalysis result = analyzer(builder, "").analyze(idea);

        assertThat(result.keywords()).isNotEmpty();
        noCall.verify(); // 기대 요청이 없으므로 호출이 있었다면 이미 실패했다
    }
}
