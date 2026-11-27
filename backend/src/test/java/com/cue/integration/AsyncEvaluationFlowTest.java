package com.cue.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cue.evaluation.service.DailyEvaluationBatch;
import com.cue.evaluation.service.EvaluationStateRecovery;
import com.cue.global.ai.GeminiClient;
import com.cue.global.error.CustomException;
import com.cue.global.error.ErrorCode;
import com.cue.source.service.SourceCheckService;
import com.cue.trend.service.GlobalTrendService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
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

/**
 * 비동기 평가 API·실행 상태 통합 테스트 (실제 단일 스레드 실행기 사용, Gemini·자료 조회·트렌드만 목).
 * 결정적으로 만들려고 Gemini 목이 래치에서 기다리게 하거나, 상태가 바뀔 때까지 짧게 폴링한다. (Docker 필요)
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {"spring.docker.compose.enabled=false", "cue.gemini.enabled=false", "cue.evaluation.enabled=false",
        "cue.evaluation.delay-ms=0", "cue.evaluation.auto-on-save=false", "cue.evaluation.queue-capacity=1"})
class AsyncEvaluationFlowTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired EvaluationStateRecovery recovery;
    @Autowired DailyEvaluationBatch batch;
    @MockitoBean GeminiClient gemini;
    @MockitoBean SourceCheckService sourceCheck;
    @MockitoBean GlobalTrendService globalTrend;

    private static final String FULL = "{\"problem\":\"개발 질문이 흩어져 있다\",\"target\":\"주니어 개발자\","
            + "\"solution\":\"질문 정리 도구\",\"expectation\":\"시간 절약\"}";
    private static final String INITIAL = """
            {"scores":{
              "PROBLEM_IMPORTANCE":{"score":70,"evidenceSummary":"문제가 크다","referenceUrls":[]},
              "MARKET_DEMAND":{"score":60,"evidenceSummary":"수요가 있다","referenceUrls":[]},
              "MARKET_DIFFERENTIATION":{"score":0,"evidenceSummary":"자료 부족","referenceUrls":[]},
              "TECH_FEASIBILITY":{"score":0,"evidenceSummary":"자료 부족","referenceUrls":[]}
            }}
            """;

    /** 이 래치가 열릴 때까지 Gemini 목이 응답하지 않는다 (평가를 RUNNING 상태로 붙잡아 둔다). */
    private volatile CountDownLatch gate = new CountDownLatch(0);

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE users, trends RESTART IDENTITY CASCADE");
        org.mockito.Mockito.reset(gemini, sourceCheck, globalTrend);
        gate = new CountDownLatch(0);
        when(gemini.available()).thenReturn(true);
        when(sourceCheck.checkAll(anyLong())).thenReturn(List.of());
        when(globalTrend.today()).thenReturn(null); // TREND 갈래는 건너뛰고 근거 4개 기준만 평가
        doAnswer(inv -> {
            assertTrue(gate.await(10, TimeUnit.SECONDS), "래치가 열리지 않음");
            return mapper.readTree(INITIAL);
        }).when(gemini).generateJson(anyString(), anyMap());
    }

    @AfterEach
    void openGate() {
        gate.countDown(); // 실패한 테스트가 실행기를 붙잡고 있지 않게
        awaitNoActiveEvaluation();
    }

    private String signUpAndLogin(String email) throws Exception {
        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"kj\",\"email\":\"" + email + "\",\"password\":\"password123\"}"));
        String res = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"password123\"}"))
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + mapper.readTree(res).get("data").get("accessToken").asString();
    }

    private long createIdea(String token, String body) throws Exception {
        String res = mvc.perform(post("/api/ideas").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(res).get("data").get("id").asLong();
    }

    private JsonNode idea(String token, long id) throws Exception {
        return mapper.readTree(mvc.perform(get("/api/ideas/" + id).header("Authorization", token))
                .andReturn().getResponse().getContentAsString()).get("data");
    }

    private JsonNode requestEvaluation(String token, long id, int expectedStatus) throws Exception {
        return mapper.readTree(mvc.perform(post("/api/ideas/" + id + "/evaluate").header("Authorization", token))
                .andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString());
    }

    /** 상태가 expected가 될 때까지 폴링한다 (최대 10초). */
    private JsonNode awaitStatus(String token, long id, String expected) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode i = idea(token, id);
            if (expected.equals(i.get("evaluationStatus").asString())) return i;
            Thread.sleep(25);
        }
        fail("평가 상태가 " + expected + "가 되지 않음: " + idea(token, id).get("evaluationStatus"));
        return null;
    }

    private void awaitNoActiveEvaluation() {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            Integer n = jdbc.queryForObject("select count(*) from ideas where evaluation_status in ('QUEUED','RUNNING')", Integer.class);
            if (n != null && n == 0) return;
            try { Thread.sleep(25); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    @Test
    void 평가_요청은_즉시_202를_돌려주고_이후_폴링하면_점수가_갱신된_채_IDLE이_된다() throws Exception {
        String token = signUpAndLogin("async@cue.com");
        long id = createIdea(token, FULL);
        assertEquals("IDLE", idea(token, id).get("evaluationStatus").asString());
        gate = new CountDownLatch(1);

        JsonNode accepted = requestEvaluation(token, id, 202); // 평가가 래치에 걸려 있어도 즉시 돌아온다
        assertEquals("SUCCESS", accepted.get("result").asString());
        assertEquals("200", accepted.get("code").asString());
        assertEquals("QUEUED", accepted.get("data").get("status").asString());
        assertEquals(id, accepted.get("data").get("idea").get("id").asLong());

        awaitStatus(token, id, "RUNNING");
        assertEquals(0, idea(token, id).get("totalScore").asDouble()); // 아직 점수 없음
        assertNotNull(jdbc.queryForObject("select evaluation_started_at from ideas where id = ?", Object.class, id));

        gate.countDown();
        JsonNode done = awaitStatus(token, id, "IDLE");
        assertTrue(done.get("totalScore").asDouble() > 0);
        assertTrue(done.get("hasNewChange").asBoolean());
        assertTrue(done.path("lastEvaluationErrorCode").isNull() || done.path("lastEvaluationErrorCode").isMissingNode());
        assertNotNull(jdbc.queryForObject("select evaluation_finished_at from ideas where id = ?", Object.class, id));
    }

    @Test
    void 이미_대기_진행_중이면_에러_없이_현재_상태를_돌려주고_평가는_한_번만_돌아간다() throws Exception {
        String token = signUpAndLogin("idem@cue.com");
        long id = createIdea(token, FULL);
        gate = new CountDownLatch(1);

        assertEquals("QUEUED", requestEvaluation(token, id, 202).get("data").get("status").asString());
        awaitStatus(token, id, "RUNNING");
        assertEquals("RUNNING", requestEvaluation(token, id, 202).get("data").get("status").asString());
        assertEquals("RUNNING", requestEvaluation(token, id, 202).get("data").get("status").asString());

        gate.countDown();
        awaitStatus(token, id, "IDLE");
        verify(gemini, times(1)).generateJson(anyString(), anyMap()); // 첫 평가 1회 (TREND는 트렌드가 없어 건너뜀)
    }

    @Test
    void 보류_남의_것_없는_것_AI불가는_요청_시점에_동기로_거절하고_상태를_바꾸지_않는다() throws Exception {
        String owner = signUpAndLogin("own@cue.com");
        String other = signUpAndLogin("oth@cue.com");
        long id = createIdea(owner, FULL);
        long hold = createIdea(owner, "{\"problem\":\"p\",\"target\":\"t\"}");

        mvc.perform(post("/api/ideas/" + hold + "/evaluate").header("Authorization", owner))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("I004"));
        mvc.perform(post("/api/ideas/" + id + "/evaluate").header("Authorization", other))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("I002"));
        mvc.perform(post("/api/ideas/999999/evaluate").header("Authorization", owner))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("I001"));
        when(gemini.available()).thenReturn(false);
        mvc.perform(post("/api/ideas/" + id + "/evaluate").header("Authorization", owner))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("E001"));

        assertEquals("IDLE", idea(owner, id).get("evaluationStatus").asString());
        verify(gemini, never()).generateJson(anyString(), anyMap());
    }

    @Test
    void 평가가_실패하면_FAILED와_에러_코드를_남기고_다시_요청하면_복구된다() throws Exception {
        String token = signUpAndLogin("fail@cue.com");
        long id = createIdea(token, FULL);
        doThrow(new CustomException(ErrorCode.EXTERNAL_SERVER_ERROR)).when(gemini).generateJson(anyString(), anyMap());

        requestEvaluation(token, id, 202);
        JsonNode failed = awaitStatus(token, id, "FAILED");
        assertEquals("G002", failed.get("lastEvaluationErrorCode").asString());
        assertEquals(0, failed.get("totalScore").asDouble());
        assertNotNull(jdbc.queryForObject("select evaluation_finished_at from ideas where id = ?", Object.class, id));

        doAnswer(inv -> mapper.readTree(INITIAL)).when(gemini).generateJson(anyString(), anyMap());
        assertEquals("QUEUED", requestEvaluation(token, id, 202).get("data").get("status").asString()); // FAILED → 다시 QUEUED
        JsonNode ok = awaitStatus(token, id, "IDLE");
        assertTrue(ok.path("lastEvaluationErrorCode").isNull() || ok.path("lastEvaluationErrorCode").isMissingNode());
        assertTrue(ok.get("totalScore").asDouble() > 0);
    }

    @Test
    void 예상치_못한_예외도_FAILED와_G001로_남는다() throws Exception {
        String token = signUpAndLogin("boom@cue.com");
        long id = createIdea(token, FULL);
        doThrow(new IllegalStateException("boom")).when(gemini).generateJson(anyString(), anyMap());
        requestEvaluation(token, id, 202);
        assertEquals("G001", awaitStatus(token, id, "FAILED").get("lastEvaluationErrorCode").asString());
    }

    @Test
    void 대기열이_가득_차면_E004로_거절하고_그_아이디어는_IDLE로_남는다() throws Exception {
        String token = signUpAndLogin("full@cue.com");
        long first = createIdea(token, FULL);
        long second = createIdea(token, FULL);
        long third = createIdea(token, FULL);
        gate = new CountDownLatch(1);

        requestEvaluation(token, first, 202);
        awaitStatus(token, first, "RUNNING");                              // 실행기 스레드 점유
        assertEquals("QUEUED", requestEvaluation(token, second, 202).get("data").get("status").asString()); // 대기열 1칸
        mvc.perform(post("/api/ideas/" + third + "/evaluate").header("Authorization", token))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("E004"));
        assertEquals("IDLE", idea(token, third).get("evaluationStatus").asString());

        gate.countDown();
        awaitStatus(token, first, "IDLE");
        awaitStatus(token, second, "IDLE"); // 대기하던 것도 순서대로 실행된다
    }

    @Test
    void 부팅_시_남은_QUEUED_RUNNING은_FAILED_E005로_정리하고_나머지는_건드리지_않는다() throws Exception {
        String token = signUpAndLogin("boot@cue.com");
        long a = createIdea(token, FULL);
        long b = createIdea(token, FULL);
        long c = createIdea(token, FULL);
        jdbc.update("update ideas set evaluation_status = 'RUNNING' where id = ?", a);
        jdbc.update("update ideas set evaluation_status = 'QUEUED' where id = ?", b);

        assertEquals(2, recovery.recover());

        for (long id : new long[]{a, b}) {
            JsonNode i = idea(token, id);
            assertEquals("FAILED", i.get("evaluationStatus").asString());
            assertEquals("E005", i.get("lastEvaluationErrorCode").asString());
        }
        assertEquals("IDLE", idea(token, c).get("evaluationStatus").asString());
        assertEquals(0, recovery.recover());
    }

    @Test
    void 일일_배치도_같은_상태_규칙을_따르고_수동_요청과_동시에_돌지_않는다() throws Exception {
        String token = signUpAndLogin("batch@cue.com");
        long id = createIdea(token, FULL);
        long failing = createIdea(token, FULL);
        doAnswer(inv -> mapper.readTree(INITIAL)).when(gemini).generateJson(anyString(), anyMap());

        DailyEvaluationBatch.Summary summary = batch.runAll();
        assertEquals(2, summary.total());
        assertEquals("IDLE", idea(token, id).get("evaluationStatus").asString());
        assertTrue(idea(token, id).get("totalScore").asDouble() > 0);
        assertEquals("IDLE", idea(token, failing).get("evaluationStatus").asString());

        // 배치 실패는 해당 아이디어를 FAILED로 남긴다
        jdbc.update("update ideas set needs_full_evaluation = true");
        doThrow(new CustomException(ErrorCode.EXTERNAL_SERVER_ERROR)).when(gemini).generateJson(anyString(), anyMap());
        assertEquals(2, batch.runAll().failed());
        assertEquals("FAILED", idea(token, id).get("evaluationStatus").asString());
        assertEquals("G002", idea(token, id).get("lastEvaluationErrorCode").asString());
    }

    @Test
    void 자동_평가가_꺼져_있으면_저장해도_큐에_넣지_않는다() throws Exception {
        String token = signUpAndLogin("noauto@cue.com");
        long id = createIdea(token, FULL);
        Thread.sleep(200);
        assertEquals("IDLE", idea(token, id).get("evaluationStatus").asString());
        verify(gemini, never()).generateJson(anyString(), anyMap());
    }
}
