package com.cue.global.ai;

import com.cue.global.error.CustomException;
import com.cue.global.error.ErrorCode;
import com.cue.global.error.ErrorDetail;
import com.cue.global.error.ErrorReason;
import com.cue.global.error.ExternalApi;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** 학교 Gateway의 OpenAI Responses 형식으로 요청하고 출력 메시지의 JSON 텍스트를 읽는다. */
@RequiredArgsConstructor
final class OpenAiGatewayClient {
    private final RestClient restClient;
    private final OpenAiGatewayProperties properties;
    private final ObjectMapper mapper;

    boolean available() {
        return StringUtils.hasText(properties.apiKey()) && StringUtils.hasText(properties.model());
    }

    String model() {
        return properties.model();
    }

    String send(String systemPrompt, String userText, Map<String, Object> responseSchema) {
        ObjectNode schema = mapper.valueToTree(responseSchema);
        convertSchema(schema);
        Map<String, Object> body = new HashMap<>();
        body.put("model", model());
        body.put("input", userText);
        body.put("store", false);
        body.put("max_output_tokens", properties.maxOutputTokens());
        if (systemPrompt != null) {
            body.put("instructions", systemPrompt);
        }
        body.put("text", Map.of("format", Map.of("type", "json_schema", "name", "cue_result",
                "strict", true, "schema", schema)));
        return restClient.post()
                .uri("/responses/")
                .headers(headers -> headers.setBearerAuth(properties.apiKey()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
    }

    JsonNode responseText(JsonNode response) {
        String status = response.path("status").asString("");
        if (!"completed".equals(status)) {
            throw new CustomException(ErrorCode.AI_RESPONSE_INVALID,
                    ErrorDetail.of(ExternalApi.OPENAI_GATEWAY, ErrorReason.INCOMPLETE_RESPONSE, "status=" + status));
        }
        String text = response.path("output").valueStream()
                .filter(item -> "message".equals(item.path("type").asString()))
                .flatMap(item -> item.path("content").valueStream())
                .filter(content -> "output_text".equals(content.path("type").asString()))
                .map(content -> content.path("text").asString(""))
                .collect(Collectors.joining());
        if (text.isBlank()) {
            throw new CustomException(ErrorCode.AI_RESPONSE_INVALID,
                    ErrorDetail.of(ExternalApi.OPENAI_GATEWAY, ErrorReason.NO_RESPONSE_CONTENT, ""));
        }
        return mapper.getNodeFactory().stringNode(text);
    }

    private static void convertSchema(ObjectNode schema) {
        String type = schema.path("type").asString().toLowerCase(Locale.ROOT);
        schema.put("type", type);
        if ("array".equals(type)) {
            convertSchema((ObjectNode) schema.get("items"));
        }
        if ("object".equals(type)) {
            schema.put("additionalProperties", false);
            Set<String> originallyRequired = new HashSet<>();
            schema.path("required").valueStream().map(JsonNode::asString).forEach(originallyRequired::add);
            var required = schema.putArray("required");
            ObjectNode properties = (ObjectNode) schema.get("properties");
            for (String name : properties.propertyNames()) {
                ObjectNode property = (ObjectNode) properties.get(name);
                convertSchema(property);
                required.add(name);
                if (!originallyRequired.contains(name)) {
                    // strict 모드는 모든 필드를 요구하므로 기존 선택 필드에는 null을 허용한다.
                    var nullable = schema.objectNode();
                    nullable.putArray("anyOf").add(property).addObject().put("type", "null");
                    properties.set(name, nullable);
                }
            }
        }
    }
}
