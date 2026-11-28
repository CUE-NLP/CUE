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
                .andExpect(jsonPath("$.generationConfig.responseSchema.type").value("OBJECT"))
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

    @Test
    void 실패하면_어느_API가_왜_실패했는지_담는다() {
        MockRestServiceServer[] server = new MockRestServiceServer[1];
        GeminiClient c = client(server, "k", true);
        server[0].expect(requestTo(org.hamcrest.Matchers.startsWith("https://gemini.test")))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("{\"error\":\"secret body\"}"));

        CustomException http = assertThrows(CustomException.class, () -> c.generateJson("p", SCHEMA));
        assertEquals("Gemini", http.getDetail().api());
        assertEquals("429 호출 한도 초과", http.getDetail().reason());
        assertFalse(http.getMessage().contains("secret body")); // 응답 본문은 원인에 담지 않는다

        CustomException cut = assertThrows(CustomException.class,
                () -> c.parse("{\"candidates\":[{\"finishReason\":\"MAX_TOKENS\"}]}"));
        assertEquals("응답에 본문이 없음(finishReason=MAX_TOKENS)", cut.getDetail().reason());
        assertEquals("응답 해석 실패", assertThrows(CustomException.class, () -> c.parse("not json")).getDetail().reason());
    }

    // ===== 레인·재시도·속도 제한 =====

    private static final String OK = "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{\\\"a\\\":1}\"}]}}]}";

    private GeminiClient laneClient(MockRestServiceServer[] holder, GeminiLaneProperties lane, int batchRetries,
                                    java.util.List<Long> sleeps, long batchMinInterval) {
        RestClient.Builder b = RestClient.builder().baseUrl("https://gemini.test");
        holder[0] = MockRestServiceServer.bindTo(b).build();
        return new GeminiClient(b.build(), JsonMapper.builder().build(),
                new GeminiProperties(true, "k", "main-model", "https://gemini.test", 5), lane, batchMinInterval, batchRetries,
                java.time.Clock.systemUTC(), sleeps::add);
    }

    @Test
    void 인터랙티브_레인은_가벼운_모델과_systemInstruction으로_호출한다() {
        MockRestServiceServer[] server = new MockRestServiceServer[1];
        GeminiClient c = laneClient(server, new GeminiLaneProperties("lite-model", 0, 1000, 0), 0, new java.util.ArrayList<>(), 0);
        server[0].expect(requestTo("https://gemini.test/v1beta/models/lite-model:generateContent"))
                .andExpect(jsonPath("$.systemInstruction.parts[0].text").value("규칙"))
                .andExpect(jsonPath("$.contents[0].parts[0].text").value("본문"))
                .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));
        assertEquals(1, c.generateJson(AiLane.INTERACTIVE, "규칙", "본문", SCHEMA).path("a").asInt());
        server[0].verify();
    }

    @Test
    void 배치_레인과_인터랙티브_모델이_비면_기본_모델을_쓴다() {
        MockRestServiceServer[] server = new MockRestServiceServer[1];
        GeminiClient c = laneClient(server, new GeminiLaneProperties("", 0, 1000, 0), 0, new java.util.ArrayList<>(), 0);
        assertEquals("main-model", c.modelFor(AiLane.INTERACTIVE));
        assertEquals("main-model", c.modelFor(AiLane.BATCH));
        GeminiClient c2 = laneClient(server, new GeminiLaneProperties("lite", 0, 1000, 0), 0, new java.util.ArrayList<>(), 0);
        assertEquals("lite", c2.modelFor(AiLane.INTERACTIVE));
        assertEquals("main-model", c2.modelFor(AiLane.BATCH));
    }

    @Test
    void 인터랙티브는_429면_백오프_뒤_재시도해_성공한다() {
        MockRestServiceServer[] server = new MockRestServiceServer[1];
        java.util.List<Long> sleeps = new java.util.ArrayList<>();
        GeminiClient c = laneClient(server, new GeminiLaneProperties("", 0, 5000, 1), 0, sleeps, 0);
        server[0].expect(requestTo(org.hamcrest.Matchers.startsWith("https://gemini.test"))).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server[0].expect(requestTo(org.hamcrest.Matchers.startsWith("https://gemini.test")))
                .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));
        assertEquals(1, c.generateJson(AiLane.INTERACTIVE, null, "p", SCHEMA).path("a").asInt());
        assertEquals(java.util.List.of(1000L), sleeps);
        server[0].verify();
    }

    @Test
    void Retry_After_헤더가_있으면_그_값만큼_기다린다() {
        MockRestServiceServer[] server = new MockRestServiceServer[1];
        java.util.List<Long> sleeps = new java.util.ArrayList<>();
        GeminiClient c = laneClient(server, new GeminiLaneProperties("", 0, 5000, 1), 0, sleeps, 0);
        org.springframework.http.HttpHeaders h = new org.springframework.http.HttpHeaders();
        h.add("Retry-After", "2");
        server[0].expect(requestTo(org.hamcrest.Matchers.startsWith("https://gemini.test")))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).headers(h));
        server[0].expect(requestTo(org.hamcrest.Matchers.startsWith("https://gemini.test")))
                .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));
        c.generateJson(AiLane.INTERACTIVE, null, "p", SCHEMA);
        assertEquals(java.util.List.of(2000L), sleeps);
    }

    @Test
    void 인터랙티브는_재시도를_다_써도_429면_E006() {
        MockRestServiceServer[] server = new MockRestServiceServer[1];
        GeminiClient c = laneClient(server, new GeminiLaneProperties("", 0, 5000, 1), 0, new java.util.ArrayList<>(), 0);
        server[0].expect(org.springframework.test.web.client.ExpectedCount.times(2),
                requestTo(org.hamcrest.Matchers.startsWith("https://gemini.test"))).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        assertEquals(ErrorCode.AI_BUSY, assertThrows(CustomException.class,
                () -> c.generateJson(AiLane.INTERACTIVE, null, "p", SCHEMA)).getErrorCode());
        server[0].verify();
    }

    @Test
    void 대기해야_할_시간이_인터랙티브_한도를_넘으면_재시도_없이_바로_E006() {
        MockRestServiceServer[] server = new MockRestServiceServer[1];
        java.util.List<Long> sleeps = new java.util.ArrayList<>();
        GeminiClient c = laneClient(server, new GeminiLaneProperties("", 0, 1500, 2), 0, sleeps, 0);
        org.springframework.http.HttpHeaders h = new org.springframework.http.HttpHeaders();
        h.add("Retry-After", "30");
        server[0].expect(requestTo(org.hamcrest.Matchers.startsWith("https://gemini.test")))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).headers(h));
        assertEquals(ErrorCode.AI_BUSY, assertThrows(CustomException.class,
                () -> c.generateJson(AiLane.INTERACTIVE, null, "p", SCHEMA)).getErrorCode());
        assertTrue(sleeps.isEmpty());
    }

    @Test
    void 배치는_재시도_뒤에도_429면_G002() {
        MockRestServiceServer[] server = new MockRestServiceServer[1];
        java.util.List<Long> sleeps = new java.util.ArrayList<>();
        GeminiClient c = laneClient(server, GeminiLaneProperties.none(), 2, sleeps, 0);
        server[0].expect(org.springframework.test.web.client.ExpectedCount.times(3),
                requestTo(org.hamcrest.Matchers.startsWith("https://gemini.test"))).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        assertEquals(ErrorCode.EXTERNAL_SERVER_ERROR, assertThrows(CustomException.class,
                () -> c.generateJson("p", SCHEMA)).getErrorCode());
        assertEquals(java.util.List.of(1000L, 2000L), sleeps);
        server[0].verify();
    }

    @Test
    void 재시도_대상이_아닌_오류는_바로_G002() {
        MockRestServiceServer[] server = new MockRestServiceServer[1];
        java.util.List<Long> sleeps = new java.util.ArrayList<>();
        GeminiClient c = laneClient(server, new GeminiLaneProperties("", 0, 5000, 3), 3, sleeps, 0);
        server[0].expect(requestTo(org.hamcrest.Matchers.startsWith("https://gemini.test"))).andRespond(withStatus(HttpStatus.BAD_REQUEST));
        assertEquals(ErrorCode.EXTERNAL_SERVER_ERROR, assertThrows(CustomException.class,
                () -> c.generateJson(AiLane.INTERACTIVE, null, "p", SCHEMA)).getErrorCode());
        assertTrue(sleeps.isEmpty());
    }
}
