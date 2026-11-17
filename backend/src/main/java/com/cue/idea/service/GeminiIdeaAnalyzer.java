package com.cue.idea.service;

import com.cue.global.error.CustomException;
import com.cue.global.error.ErrorCode;
import com.cue.idea.entity.Idea;
import com.cue.idea.entity.IdeaType;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Gemini API로 아이디어 종류와 검색 키워드를 추출한다 (JSON 구조화 응답).
 * 호출·파싱 실패 시 {@link SimpleIdeaAnalyzer}로 폴백해, AI 장애가 아이디어 등록/수정 실패로 번지지 않게 한다.
 */
@Slf4j
@Component
@Primary
@EnableConfigurationProperties(GeminiProperties.class)
@ConditionalOnProperty(prefix = "cue.gemini", name = "enabled", havingValue = "true", matchIfMissing = true)
class GeminiIdeaAnalyzer implements IdeaAnalyzer {
    static final int MAX_KEYWORDS = 3;
    private static final int MAX_KEYWORD_LENGTH = 60;

    private static final String SYSTEM_PROMPT = """
            너는 아이디어를 분류하고 외부 자료(GitHub, 논문, 뉴스 등) 검색용 키워드를 뽑는 분석가다.
            입력은 아이디어의 타깃·문제·해결·기대효과다. 아래 JSON 스키마로만 답한다.
            - type: 아이디어 종류 중 정확히 하나.
            - keywords: 경쟁 제품·기술·시장 동향을 검색하기 좋은 핵심 키워드 3개 (짧은 명사구, 중복 없이).
            """;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final GeminiProperties props;
    private final SimpleIdeaAnalyzer fallback;

    GeminiIdeaAnalyzer(RestClient.Builder builder, ObjectMapper objectMapper, GeminiProperties props,
                       SimpleIdeaAnalyzer fallback) {
        Duration timeout = Duration.ofSeconds(props.timeoutSeconds());
        JdkClientHttpRequestFactory factory =
                new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(timeout).build());
        factory.setReadTimeout(timeout);
        this.restClient = builder.requestFactory(factory).baseUrl(props.baseUrl()).build();
        this.objectMapper = objectMapper;
        this.props = props;
        this.fallback = fallback;
    }

    /** 테스트용: 이미 구성된 RestClient(MockRestServiceServer 연결)를 그대로 쓴다. */
    GeminiIdeaAnalyzer(RestClient restClient, ObjectMapper objectMapper, GeminiProperties props,
                       SimpleIdeaAnalyzer fallback) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.props = props;
        this.fallback = fallback;
    }

    @Override
    public IdeaAnalysis analyze(Idea idea) {
        if (props.apiKey() == null || props.apiKey().isBlank()) {
            log.warn("GEMINI_API_KEY가 없어 임시 분석기로 대체합니다.");
            return fallback.analyze(idea);
        }
        try {
            return requestAnalysis(idea);
        } catch (RuntimeException e) {
            log.warn("Gemini 분석 실패, 임시 분석기로 대체합니다: {}", e.toString());
            return fallback.analyze(idea);
        }
    }

    /** Gemini를 호출해 결과를 파싱한다. 실패하면 G002(CustomException). */
    IdeaAnalysis requestAnalysis(Idea idea) {
        String body;
        try {
            body = restClient.post()
                    .uri("/v1beta/models/{model}:generateContent", props.model())
                    .header("x-goog-api-key", props.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(buildRequest(idea))
                    .retrieve()
                    .body(String.class);
        } catch (RuntimeException e) {
            throw new CustomException(ErrorCode.EXTERNAL_SERVER_ERROR);
        }
        return parse(body);
    }

    IdeaAnalysis parse(String responseBody) {
        try {
            JsonNode text = objectMapper.readTree(responseBody)
                    .path("candidates").path(0).path("content").path("parts").path(0).path("text");
            if (!text.isString()) {
                throw new CustomException(ErrorCode.EXTERNAL_SERVER_ERROR);
            }
            JsonNode result = objectMapper.readTree(text.asString());
            LinkedHashSet<String> keywords = new LinkedHashSet<>();
            for (JsonNode k : result.path("keywords")) {
                String keyword = k.asString("").trim();
                if (!keyword.isEmpty() && keyword.length() <= MAX_KEYWORD_LENGTH) {
                    keywords.add(keyword);
                }
                if (keywords.size() == MAX_KEYWORDS) {
                    break;
                }
            }
            if (keywords.isEmpty()) {
                throw new CustomException(ErrorCode.EXTERNAL_SERVER_ERROR);
            }
            return new IdeaAnalysis(IdeaType.fromAi(result.path("type").asString(null)), List.copyOf(keywords));
        } catch (CustomException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new CustomException(ErrorCode.EXTERNAL_SERVER_ERROR);
        }
    }

    private Map<String, Object> buildRequest(Idea idea) {
        String userText = "타깃: " + idea.getTarget() + "\n문제: " + idea.getProblem()
                + "\n해결: " + idea.getSolution() + "\n기대효과: " + idea.getExpectation();
        List<String> types = Arrays.stream(IdeaType.values()).map(Enum::name).toList();
        Map<String, Object> schema = Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "type", Map.of("type", "STRING", "enum", types),
                        "keywords", Map.of("type", "ARRAY", "items", Map.of("type", "STRING"),
                                "minItems", 1, "maxItems", MAX_KEYWORDS)),
                "required", List.of("type", "keywords"));
        return Map.of(
                "systemInstruction", Map.of("parts", List.of(Map.of("text", SYSTEM_PROMPT))),
                "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", userText)))),
                "generationConfig", Map.of(
                        "responseMimeType", "application/json",
                        "responseSchema", schema,
                        "temperature", 0.2));
    }
}
