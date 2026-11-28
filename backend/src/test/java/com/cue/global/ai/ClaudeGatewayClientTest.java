package com.cue.global.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.cue.global.error.CustomException;
import com.cue.global.error.ErrorCode;
import com.cue.idea.service.GeminiProperties;
import com.cue.source.config.SourceApiProperties;
import com.cue.source.fetch.SourceApiLog;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class ClaudeGatewayClientTest {
    private static final String BASE_URL = "https://gateway.test/v1/gateway/claude";
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final ClaudeGatewayClient client = new ClaudeGatewayClient(builder.build(),
            new ClaudeGatewayProperties(true, "test-key", BASE_URL, "claude-opus-5-5", 45, 4096), mapper);

    @Test
    void 평가_로그에_응답_JSON을_파싱_전에_기록한다(@TempDir Path directory) throws IOException {
        String key = "claude-log-test-key";
        Path path = directory.resolve("source-api.txt");
        SourceApiLog log = new SourceApiLog(path.toString(), mock(SourceApiProperties.class, RETURNS_DEEP_STUBS), key, "");
        HttpServer endpoint = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        endpoint.createContext("/v1/messages/", exchange -> {
            String fixture = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String reason = fixture.contains("truncated-prompt") ? "max_tokens" : "end_turn";
            byte[] body = """
                    {"stop_reason":"%s","content":[{"type":"text","text":%s}],"echo":"%s"}
                    """.formatted(reason, mapper.writeValueAsString("{\"scores\":[]}"), key)
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        endpoint.start();
        try {
            String baseUrl = "http://127.0.0.1:" + endpoint.getAddress().getPort();
            GeminiClient claude = new GeminiClient(RestClient.builder(), mapper,
                    new GeminiProperties(false, "", "gemini", baseUrl, 5), GeminiLaneProperties.none(),
                    new ClaudeGatewayProperties(true, key, baseUrl, "claude", 5, 4096),
                    new OpenAiGatewayProperties(false, "", baseUrl, "", 5, 4096), log, 0, 0);
            var schema = Map.<String, Object>of("type", "OBJECT");
            var result = log.recordOperation("아이디어 평가 ideaId=7",
                    () -> claude.generateJson("private-prompt", schema));
            assertThat(result.path("scores").isArray()).isTrue();

            CustomException error = assertThrows(CustomException.class, () -> log.recordOperation(
                    "아이디어 평가 ideaId=8", () -> claude.generateJson("truncated-prompt", schema)));
            assertEquals(ErrorCode.AI_RESPONSE_INVALID, error.getErrorCode());
            assertEquals("Claude Gateway", error.getDetail().api());
            assertEquals("응답이 완료되지 않음(stop_reason=max_tokens)", error.getDetail().reason());
        } finally {
            endpoint.stop(0);
        }
        assertThat(Files.readString(path))
                .containsSubsequence("시작 아이디어 평가 ideaId=7", " POST ", "/v1/messages/", "HTTP 200",
                        "\"stop_reason\":\"end_turn\"", "\\\"scores\\\":", "\"echo\":\"[redacted]\"",
                        "종료 아이디어 평가 ideaId=7", "======",
                        "시작 아이디어 평가 ideaId=8", " POST ", "HTTP 200", "\"stop_reason\":\"max_tokens\"",
                        "종료 아이디어 평가 ideaId=8", "======")
                .doesNotContain(key, "private-prompt", "truncated-prompt");
    }

    @Test
    void 학교_게이트웨이에_Claude_형식의_스키마를_보내고_텍스트_JSON을_선택한다() {
        Map<String, Object> score = Map.of("type", "OBJECT",
                "properties", Map.of("referenceIds", Map.of("type", "ARRAY", "items", Map.of("type", "INTEGER"))),
                "required", List.of("referenceIds"));
        Map<String, Object> schema = Map.of("type", "OBJECT",
                "properties", Map.of("scores", Map.of("type", "ARRAY", "maxItems", 4, "items", score)),
                "required", List.of("scores"));
        String scoreJson = """
                {"scores":[{"referenceIds":[1]}]}
                """;
        String prefix = "$.output_config.format.schema";

        server.expect(requestTo(BASE_URL + "/v1/messages/"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-api-key", "test-key"))
                .andExpect(header("anthropic-version", "2023-06-01"))
                .andExpect(headerDoesNotExist("x-goog-api-key"))
                .andExpect(jsonPath("$.model").value("claude-opus-5-5"))
                .andExpect(jsonPath("$.max_tokens").value(4096))
                .andExpect(jsonPath("$.messages[0].content").value("아이디어"))
                .andExpect(jsonPath("$.system").value("규칙"))
                .andExpect(jsonPath("$.output_config.format.type").value("json_schema"))
                .andExpect(jsonPath(prefix + ".additionalProperties").value(false))
                .andExpect(jsonPath(prefix + ".properties.scores.maxItems").doesNotExist())
                .andExpect(jsonPath(prefix + ".properties.scores.items.additionalProperties").value(false))
                .andExpect(jsonPath(prefix + ".properties.scores.items.required[0]").value("referenceIds"))
                .andExpect(jsonPath(prefix + ".properties.scores.items.properties.referenceIds.items.type").value("integer"))
                .andRespond(withSuccess("""
                        {"stop_reason":"end_turn","content":[
                          {"type":"thinking"},
                          {"type":"text","text":%s}
                        ]}
                        """.formatted(mapper.writeValueAsString(scoreJson)),
                        MediaType.APPLICATION_JSON));

        String response = client.send("규칙", "아이디어", schema);
        String text = client.responseText(mapper.readTree(response)).asString();
        assertEquals(1, mapper.readTree(text).at("/scores/0/referenceIds/0").asInt());
        assertEquals(4, mapper.valueToTree(schema).at("/properties/scores/maxItems").asInt());
        server.verify();
    }

    @Test
    void 출력이_잘렸거나_텍스트_블록이_없으면_E002() {
        assertEquals(ErrorCode.AI_RESPONSE_INVALID, assertThrows(CustomException.class,
                () -> client.responseText(mapper.readTree("""
                        {"stop_reason":"max_tokens","content":[{"type":"text","text":"{}"}]}
                        """))).getErrorCode());
        assertEquals(ErrorCode.AI_RESPONSE_INVALID, assertThrows(CustomException.class,
                () -> client.responseText(mapper.readTree("""
                        {"stop_reason":"end_turn","content":[{"type":"thinking"}]}
                        """))).getErrorCode());
    }
}
