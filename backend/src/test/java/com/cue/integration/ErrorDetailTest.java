package com.cue.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cue.global.ai.AiLane;
import com.cue.global.ai.GeminiClient;
import com.cue.global.error.CustomException;
import com.cue.global.error.ErrorCode;
import com.cue.global.error.ErrorDetail;
import com.cue.global.error.ErrorReason;
import com.cue.global.error.ExternalApi;
import com.cue.source.service.SourceCheckService;
import com.cue.trend.service.GlobalTrendService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 에러가 난 위치·외부 API·원인이 실패 응답과 아이디어 조회에 나오는지. (Docker 필요) */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {"spring.docker.compose.enabled=false", "cue.gemini.enabled=false",
        "cue.evaluation.enabled=false", "cue.evaluation.auto-on-save=false"})
class ErrorDetailTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean GeminiClient gemini;
    @MockitoBean SourceCheckService sourceCheck;
    @MockitoBean GlobalTrendService globalTrend;

    private static final String FULL = "{\"problem\":\"개발 질문이 흩어져 있다\",\"target\":\"주니어 개발자\","
            + "\"solution\":\"질문 정리 도구\",\"expectation\":\"시간 절약\",\"keywords\":[\"code review\"]}";

    private String token;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.execute("TRUNCATE users, trends RESTART IDENTITY CASCADE");
        org.mockito.Mockito.reset(gemini, sourceCheck, globalTrend);
        when(sourceCheck.checkAll(anyLong())).thenReturn(List.of());
        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"kj\",\"email\":\"error@cue.com\",\"password\":\"password123\"}"));
        String login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"error@cue.com\",\"password\":\"password123\"}"))
                .andReturn().getResponse().getContentAsString();
        token = "Bearer " + mapper.readTree(login).get("data").get("accessToken").asString();
    }

    private long createIdea() throws Exception {
        String res = mvc.perform(post("/api/ideas").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(FULL))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(res).get("data").get("id").asLong();
    }

    private JsonNode evaluateAndWait(long id) throws Exception {
        mvc.perform(post("/api/ideas/" + id + "/evaluate").header("Authorization", token)).andExpect(status().isAccepted());
        long deadline = System.currentTimeMillis() + 10_000;
        while (true) {
            JsonNode idea = mapper.readTree(mvc.perform(get("/api/ideas/" + id).header("Authorization", token))
                    .andReturn().getResponse().getContentAsString()).get("data");
            if (List.of("IDLE", "FAILED").contains(idea.get("evaluationStatus").asString())) {
                return idea;
            }
            assertTrue(System.currentTimeMillis() < deadline, "평가가 끝나지 않음");
            Thread.sleep(25);
        }
    }

    @Test
    void 평가가_외부_API에서_실패하면_위치와_API와_원인을_보여주고_다시_성공하면_지운다() throws Exception {
        when(gemini.available()).thenReturn(true);
        long id = createIdea();
        doThrow(new CustomException(ErrorCode.EXTERNAL_SERVER_ERROR, ErrorDetail.of(ExternalApi.GEMINI, ErrorReason.HTTP_TOO_MANY_REQUESTS)))
                .when(gemini).generateJson(anyString(), anyMap());

        JsonNode failed = evaluateAndWait(id);

        assertEquals("FAILED", failed.get("evaluationStatus").asString());
        assertEquals("G002", failed.get("lastEvaluationErrorCode").asString());
        assertEquals("근거 4개 기준 평가", failed.get("lastEvaluationError").get("location").asString());
        assertEquals("Gemini", failed.get("lastEvaluationError").get("api").asString());
        assertEquals("429 호출 한도 초과", failed.get("lastEvaluationError").get("reason").asString());

        org.mockito.Mockito.reset(gemini);
        when(gemini.available()).thenReturn(true);
        when(gemini.generateJson(anyString(), anyMap())).thenReturn(mapper.readTree("{\"scores\":[]}"));

        JsonNode done = evaluateAndWait(id);

        assertEquals("IDLE", done.get("evaluationStatus").asString());
        assertTrue(done.path("lastEvaluationError").isNull() || done.path("lastEvaluationError").isMissingNode());
        assertNull(jdbc.queryForObject("select last_evaluation_error_reason from ideas where id = ?", String.class, id));
    }

    @Test
    void 예상하지_못한_평가_오류는_위치만_알리고_내부_메시지는_내보내지_않는다() throws Exception {
        when(gemini.available()).thenReturn(true);
        long id = createIdea();
        doThrow(new IllegalStateException("internal secret")).when(gemini).generateJson(anyString(), anyMap());

        JsonNode failed = evaluateAndWait(id);

        assertEquals("G001", failed.get("lastEvaluationErrorCode").asString());
        assertEquals("근거 4개 기준 평가", failed.get("lastEvaluationError").get("location").asString());
        assertEquals("서버 내부 오류", failed.get("lastEvaluationError").get("reason").asString());
        assertTrue(!failed.toString().contains("internal secret"));
    }

    @Test
    void 구조화_실패_응답은_data에_위치와_API와_원인을_담는다() throws Exception {
        when(gemini.available()).thenReturn(true);
        when(gemini.generateJson(any(AiLane.class), anyString(), anyString(), anyMap())).thenThrow(
                new CustomException(ErrorCode.AI_BUSY, ErrorDetail.of(ExternalApi.GEMINI, ErrorReason.HTTP_TOO_MANY_REQUESTS)));

        mvc.perform(post("/api/ideas/structure").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"코드 리뷰를 도와주는 도구\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("E006"))
                .andExpect(jsonPath("$.data.location").value("아이디어 구조화"))
                .andExpect(jsonPath("$.data.api").value("Gemini"))
                .andExpect(jsonPath("$.data.reason").value("429 호출 한도 초과"));
    }
}
