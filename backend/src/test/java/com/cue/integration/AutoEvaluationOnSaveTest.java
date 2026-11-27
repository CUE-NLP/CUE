package com.cue.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cue.global.ai.GeminiClient;
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

/** 등록·수정 직후 첫 평가 자동 시작(auto-on-save=true, 커밋 뒤 큐 투입). Gemini·자료 조회·트렌드만 목. (Docker 필요) */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {"spring.docker.compose.enabled=false", "cue.gemini.enabled=false", "cue.evaluation.enabled=false",
        "cue.evaluation.delay-ms=0", "cue.evaluation.auto-on-save=true"})
class AutoEvaluationOnSaveTest {
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
            + "\"solution\":\"질문 정리 도구\",\"expectation\":\"시간 절약\"}";
    private static final String INITIAL = """
            {"scores":{
              "PROBLEM_IMPORTANCE":{"score":70,"evidenceSummary":"문제가 크다","referenceIds":[]},
              "MARKET_DEMAND":{"score":0,"evidenceSummary":"자료 부족","referenceIds":[]},
              "MARKET_DIFFERENTIATION":{"score":0,"evidenceSummary":"자료 부족","referenceIds":[]},
              "TECH_FEASIBILITY":{"score":0,"evidenceSummary":"자료 부족","referenceIds":[]}
            }}
            """;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE users, trends RESTART IDENTITY CASCADE");
        org.mockito.Mockito.reset(gemini, sourceCheck, globalTrend);
        when(gemini.available()).thenReturn(true);
        when(sourceCheck.checkAll(anyLong())).thenReturn(List.of());
        when(globalTrend.today()).thenReturn(null);
        doAnswer(inv -> mapper.readTree(INITIAL)).when(gemini).generateJson(anyString(), anyMap());
    }

    private String signUpAndLogin(String email) throws Exception {
        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"kj\",\"email\":\"" + email + "\",\"password\":\"password123\"}"));
        String res = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"password123\"}"))
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + mapper.readTree(res).get("data").get("accessToken").asString();
    }

    private long create(String token, String body) throws Exception {
        return mapper.readTree(mvc.perform(post("/api/ideas").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("data").get("id").asLong();
    }

    private JsonNode idea(String token, long id) throws Exception {
        return mapper.readTree(mvc.perform(get("/api/ideas/" + id).header("Authorization", token))
                .andReturn().getResponse().getContentAsString()).get("data");
    }

    private void awaitIdle(String token, long id) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode i = idea(token, id);
            if ("IDLE".equals(i.get("evaluationStatus").asString()) && i.get("lastEvaluatedAt") != null && !i.get("lastEvaluatedAt").isNull()) return;
            Thread.sleep(25);
        }
        fail("자동 평가가 끝나지 않음: " + idea(token, id));
    }

    private void awaitQuiet() throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (jdbc.queryForObject("select count(*) from ideas where evaluation_status in ('QUEUED','RUNNING')", Integer.class) == 0) return;
            Thread.sleep(25);
        }
    }

    @Test
    void ACTIVE로_등록하면_커밋_뒤_첫_평가가_자동으로_돌아_점수가_채워진다() throws Exception {
        String token = signUpAndLogin("auto@cue.com");
        long id = create(token, FULL); // 응답은 평가를 기다리지 않는다
        awaitIdle(token, id);
        assertTrue(idea(token, id).get("totalScore").asDouble() > 0);
        assertFalse(jdbc.queryForObject("select needs_full_evaluation from ideas where id = ?", Boolean.class, id));
    }

    @Test
    void 보류로_등록하면_시작하지_않고_ACTIVE가_되거나_내용이_바뀔_때만_다시_시작한다() throws Exception {
        String token = signUpAndLogin("autoedit@cue.com");
        long hold = create(token, "{\"problem\":\"p\",\"target\":\"t\"}");
        awaitQuiet();
        verify(gemini, never()).generateJson(anyString(), anyMap());
        assertEquals("IDLE", idea(token, hold).get("evaluationStatus").asString());

        // HOLD → ACTIVE: 시작
        mvc.perform(patch("/api/ideas/" + hold).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"solution\":\"s\",\"expectation\":\"e\"}")).andExpect(status().isOk());
        awaitIdle(token, hold);
        verify(gemini, times(1)).generateJson(anyString(), anyMap());

        // 같은 값으로 수정: 시작하지 않음
        mvc.perform(patch("/api/ideas/" + hold).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"solution\":\"s\"}")).andExpect(status().isOk());
        awaitQuiet();
        verify(gemini, times(1)).generateJson(anyString(), anyMap());

        // 내용 변경: 다시 시작
        mvc.perform(patch("/api/ideas/" + hold).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"solution\":\"완전히 다른 해결\"}")).andExpect(status().isOk());
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && mockingDetails(gemini).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("generateJson")).count() < 2) Thread.sleep(25);
        awaitQuiet();
        verify(gemini, times(2)).generateJson(anyString(), anyMap());
    }

    @Test
    void Gemini를_쓸_수_없어도_등록은_성공하고_평가는_시작하지_않는다() throws Exception {
        when(gemini.available()).thenReturn(false);
        String token = signUpAndLogin("autonoai@cue.com");
        long id = create(token, FULL);
        awaitQuiet();
        assertEquals("IDLE", idea(token, id).get("evaluationStatus").asString());
        verify(gemini, never()).generateJson(anyString(), anyMap());
    }
}
