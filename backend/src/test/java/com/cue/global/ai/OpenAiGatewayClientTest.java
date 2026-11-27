package com.cue.global.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.cue.global.error.CustomException;
import com.cue.global.error.ErrorCode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class OpenAiGatewayClientTest {
    private static final String BASE_URL = "https://gateway.test/v1/gateway";
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final OpenAiGatewayClient client = new OpenAiGatewayClient(builder.build(),
            new OpenAiGatewayProperties(true, "fixture-key", BASE_URL, "fixture-gpt", 45, 4096), mapper);

    @Test
    void Bearer_인증과_엄격한_스키마를_보내고_출력_메시지에_있는_JSON을_가져온다() {
        Map<String, Object> schema = Map.of("type", "OBJECT",
                "properties", Map.of("evidenceSummary", Map.of("type", "STRING"),
                        "score", Map.of("type", "NUMBER")),
                "required", List.of("evidenceSummary"));
        String format = "$.text.format";
        String response = """
                {"status":"completed","output":[{"type":"reasoning"},
                  {"type":"message","content":[{"type":"output_text","text":%s}]}]}
                """.formatted(mapper.writeValueAsString("{\"evidenceSummary\":\"평가 근거\",\"score\":null}"));
        server.expect(requestTo(BASE_URL + "/responses/"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer fixture-key"))
                .andExpect(jsonPath("$.model").value("fixture-gpt"))
                .andExpect(jsonPath("$.instructions").value("평가 규칙"))
                .andExpect(jsonPath("$.input").value("아이디어"))
                .andExpect(jsonPath("$.store").value(false))
                .andExpect(jsonPath("$.max_output_tokens").value(4096))
                .andExpect(jsonPath(format + ".type").value("json_schema"))
                .andExpect(jsonPath(format + ".strict").value(true))
                .andExpect(jsonPath(format + ".schema.additionalProperties").value(false))
                .andExpect(jsonPath(format + ".schema.required.length()").value(2))
                .andExpect(jsonPath(format + ".schema.properties.score.anyOf[1].type").value("null"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        String raw = client.send("평가 규칙", "아이디어", schema);
        var content = mapper.readTree(client.responseText(mapper.readTree(raw)).asString());
        assertEquals("평가 근거", content.path("evidenceSummary").asString());
        assertFalse(content.path("score").isNumber());
        server.verify();
    }

    @Test
    void 불완전_응답과_거절은_E002로_처리한다() {
        for (String raw : List.of(
                """
                {"status":"incomplete","output":[{"type":"message","content":[{"type":"output_text","text":"{}"}]}]}
                """,
                """
                {"status":"completed","output":[{"type":"message","content":[{"type":"refusal","refusal":"거절"}]}]}
                """)) {
            assertEquals(ErrorCode.AI_RESPONSE_INVALID, assertThrows(CustomException.class,
                    () -> client.responseText(mapper.readTree(raw))).getErrorCode());
        }
    }
}
