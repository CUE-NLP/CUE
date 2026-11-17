package com.cue.global.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.cue.global.error.CustomException;
import com.cue.global.error.ErrorCode;
import com.cue.idea.service.GeminiProperties;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class GeminiClientTest {
    private static final Map<String, Object> SCHEMA = Map.of("type", "OBJECT");

    private GeminiClient client(MockRestServiceServer[] holder, String apiKey, boolean enabled) {
        RestClient.Builder b = RestClient.builder().baseUrl("https://gemini.test");
        holder[0] = MockRestServiceServer.bindTo(b).build();
        return new GeminiClient(b.build(), JsonMapper.builder().build(),
                new GeminiProperties(enabled, apiKey, "m1", "https://gemini.test", 5));
    }

    @Test
    void 응답의_JSON_본문을_파싱해_돌려주고_키를_헤더로_보낸다() {
        MockRestServiceServer[] server = new MockRestServiceServer[1];
        GeminiClient c = client(server, "secret", true);
        server[0].expect(requestTo("https://gemini.test/v1beta/models/m1:generateContent"))
                .andExpect(header("x-goog-api-key", "secret"))
                .andExpect(jsonPath("$.generationConfig.responseMimeType").value("application/json"))
                .andExpect(jsonPath("$.contents[0].parts[0].text").value("안녕"))
                .andRespond(withSuccess("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{\\\"a\\\":1}\"}]}}]}",
                        MediaType.APPLICATION_JSON));
        assertEquals(1, c.generateJson("안녕", SCHEMA).path("a").asInt());
        server[0].verify();
    }

    @Test
    void 키가_없거나_비활성이면_사용_불가이고_호출하면_E001() {
        MockRestServiceServer[] server = new MockRestServiceServer[1];
        GeminiClient noKey = client(server, "", true);
        assertFalse(noKey.available());
        assertEquals(ErrorCode.AI_NOT_CONFIGURED,
                assertThrows(CustomException.class, () -> noKey.generateJson("p", SCHEMA)).getErrorCode());
        assertFalse(client(server, "k", false).available());
        assertTrue(client(server, "k", true).available());
    }

    @Test
    void 통신_실패는_G002_응답_형식_오류는_E002() {
        MockRestServiceServer[] server = new MockRestServiceServer[1];
        GeminiClient c = client(server, "k", true);
        server[0].expect(requestTo(org.hamcrest.Matchers.startsWith("https://gemini.test")))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        assertEquals(ErrorCode.EXTERNAL_SERVER_ERROR,
                assertThrows(CustomException.class, () -> c.generateJson("p", SCHEMA)).getErrorCode());

        assertEquals(ErrorCode.AI_RESPONSE_INVALID,
                assertThrows(CustomException.class, () -> c.parse("{\"candidates\":[]}")).getErrorCode());
        assertEquals(ErrorCode.AI_RESPONSE_INVALID, assertThrows(CustomException.class,
                () -> c.parse("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"not json\"}]}}]}")).getErrorCode());
    }
}
