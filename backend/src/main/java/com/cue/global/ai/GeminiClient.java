package com.cue.global.ai;

import com.cue.global.error.CustomException;
import com.cue.global.error.ErrorCode;
import com.cue.idea.service.GeminiProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 평가용 Gemini 호출 클라이언트. 설정은 아이디어 분석기와 같은 {@code cue.gemini.*}를 쓴다.
 * 비활성이거나 키가 없으면 {@link #available()}이 false이며, 호출하는 쪽이 먼저 확인해 건너뛴다.
 * 프롬프트 전체는 {@link PromptLoader}로 만든 텍스트 하나이고, 출력은 JSON 구조화 응답(responseSchema)이다.
 */
@Component
@EnableConfigurationProperties(GeminiProperties.class)
public class GeminiClient {
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final GeminiProperties props;

    @Autowired
    public GeminiClient(RestClient.Builder builder, ObjectMapper objectMapper, GeminiProperties props) {
        Duration timeout = Duration.ofSeconds(props.timeoutSeconds());
        JdkClientHttpRequestFactory factory =
                new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(timeout).build());
        factory.setReadTimeout(timeout);
        this.restClient = builder.clone().requestFactory(factory).baseUrl(props.baseUrl()).build();
        this.objectMapper = objectMapper;
        this.props = props;
    }

    /** 테스트용: 이미 구성된 RestClient(MockRestServiceServer 연결)를 쓴다. */
    GeminiClient(RestClient restClient, ObjectMapper objectMapper, GeminiProperties props) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.props = props;
    }

    /** Gemini를 쓸 수 있는지 (활성 + API 키 있음). */
    public boolean available() {
        return props.enabled() && props.apiKey() != null && !props.apiKey().isBlank();
    }

    /**
     * 프롬프트를 보내고 JSON으로 파싱한 응답 본문을 돌려준다.
     *
     * @throws CustomException 키 없음 E001, 통신 실패 G002, 응답 형식 오류 E002
     */
    public JsonNode generateJson(String prompt, Map<String, Object> responseSchema) {
        if (!available()) {
            throw new CustomException(ErrorCode.AI_NOT_CONFIGURED);
        }
        String body;
        try {
            body = restClient.post()
                    .uri("/v1beta/models/{model}:generateContent", props.model())
                    .header("x-goog-api-key", props.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", prompt)))),
                            "generationConfig", Map.of(
                                    "responseMimeType", "application/json",
                                    "responseSchema", responseSchema,
                                    "temperature", 0.2)))
                    .retrieve()
                    .body(String.class);
        } catch (RuntimeException e) {
            throw new CustomException(ErrorCode.EXTERNAL_SERVER_ERROR);
        }
        return parse(body);
    }

    JsonNode parse(String responseBody) {
        try {
            JsonNode text = objectMapper.readTree(responseBody)
                    .path("candidates").path(0).path("content").path("parts").path(0).path("text");
            if (!text.isString()) {
                throw new CustomException(ErrorCode.AI_RESPONSE_INVALID);
            }
            return objectMapper.readTree(text.asString());
        } catch (CustomException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new CustomException(ErrorCode.AI_RESPONSE_INVALID);
        }
    }
}
