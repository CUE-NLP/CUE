package com.cue.global.ai;

import com.cue.global.error.CustomException;
import com.cue.global.error.ErrorCode;
import com.cue.global.error.ErrorDetail;
import com.cue.global.error.ErrorReason;
import com.cue.global.error.ExternalApi;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Anthropic Messages 요청과 구조화 응답 형식으로 변환한다. */
@RequiredArgsConstructor
final class ClaudeGatewayClient {
    private final RestClient restClient;
    private final ClaudeGatewayProperties properties;
    private final ObjectMapper mapper;

    boolean available() {
        return properties.apiKey() != null && !properties.apiKey().isBlank();
    }

    String model() {
        return properties.model();
    }

    String send(String systemPrompt, String userText, Map<String, Object> responseSchema) {
        ObjectNode schema = mapper.valueToTree(responseSchema);
        convertSchema(schema);
        Map<String, Object> body = new HashMap<>();
        body.put("model", model());
        body.put("max_tokens", properties.maxTokens());
        if (systemPrompt != null) {
            body.put("system", systemPrompt);
        }
        body.put("messages", List.of(Map.of("role", "user", "content", userText)));
        body.put("output_config", Map.of("format", Map.of("type", "json_schema", "schema", schema)));
        return restClient.post()
                .uri("/v1/messages/")
                .header("x-api-key", properties.apiKey())
                .header("anthropic-version", "2023-06-01")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
    }

    JsonNode responseText(JsonNode response) {
        // 중단된 응답은 부분 JSON을 반환할 수 있다.
        String stopReason = response.path("stop_reason").asString("");
        if (!"end_turn".equals(stopReason)) {
            throw new CustomException(ErrorCode.AI_RESPONSE_INVALID,
                    ErrorDetail.of(ExternalApi.CLAUDE_GATEWAY, ErrorReason.INCOMPLETE_RESPONSE, "stop_reason=" + stopReason));
        }
        return response.path("content").valueStream()
                .filter(block -> "text".equals(block.path("type").asString()))
                .map(block -> block.path("text"))
                .findFirst()
                .orElseThrow(() -> new CustomException(ErrorCode.AI_RESPONSE_INVALID,
                        ErrorDetail.of(ExternalApi.CLAUDE_GATEWAY, ErrorReason.NO_RESPONSE_CONTENT, "")));
    }

    private static void convertSchema(ObjectNode schema) {
        String type = schema.path("type").asString().toLowerCase(Locale.ROOT);
        schema.put("type", type);
        if ("object".equals(type)) {
            schema.put("additionalProperties", false);
            schema.path("properties").valueStream().forEach(property -> convertSchema((ObjectNode) property));
        } else if ("array".equals(type)) {
            // Claude는 배열 길이 제약 중 minItems 0·1만 지원한다. 상한은 프롬프트와 기존 서버 로직에서 처리한다.
            schema.remove("maxItems");
            convertSchema((ObjectNode) schema.get("items"));
        }
    }
}
