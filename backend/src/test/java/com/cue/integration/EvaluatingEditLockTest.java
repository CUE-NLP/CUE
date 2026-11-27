package com.cue.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cue.global.ai.GeminiClient;
import com.cue.idea.entity.ItCategory;
import com.cue.idea.service.IdeaAnalysis;
import com.cue.idea.service.IdeaAnalyzer;
import com.cue.source.service.SourceCheckService;
import com.cue.trend.service.GlobalTrendService;
import java.util.List;
import java.util.concurrent.CompletableFuture;
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

/** 평가가 대기·진행 중인 아이디어의 수정 차단(I005)과 평가 시작과의 경쟁. 래치로 결정적으로 만든다. (Docker 필요) */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {"spring.docker.compose.enabled=false", "cue.gemini.enabled=false", "cue.evaluation.enabled=false",
        "cue.evaluation.auto-on-save=false"})
class EvaluatingEditLockTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean GeminiClient gemini;
    @MockitoBean SourceCheckService sourceCheck;
    @MockitoBean GlobalTrendService globalTrend;
    @MockitoBean IdeaAnalyzer analyzer;

    private static final String FULL = "{\"problem\":\"개발 질문이 흩어져 있다\",\"target\":\"주니어 개발자\","
            + "\"solution\":\"질문 정리 도구\",\"expectation\":\"시간 절약\"}";
    private static final String INITIAL = """
            {"scores":{
              "PROBLEM_IMPORTANCE":{"score":70,"evidenceSummary":"문제가 크다","referenceUrls":[]},
              "MARKET_DEMAND":{"score":0,"evidenceSummary":"자료 부족","referenceUrls":[]},
              "MARKET_DIFFERENTIATION":{"score":0,"evidenceSummary":"자료 부족","referenceUrls":[]},
              "TECH_FEASIBILITY":{"score":0,"evidenceSummary":"자료 부족","referenceUrls":[]}
            }}
            """;
    private static final String EDIT = "{\"solution\":\"완전히 다른 해결\"}";

    private volatile CountDownLatch geminiGate = new CountDownLatch(0);
    private volatile CountDownLatch analyzerGate = new CountDownLatch(0);

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE users, trends RESTART IDENTITY CASCADE");
        org.mockito.Mockito.reset(gemini, sourceCheck, globalTrend, analyzer);
        geminiGate = new CountDownLatch(0);
        analyzerGate = new CountDownLatch(0);
        when(gemini.available()).thenReturn(true);
        when(sourceCheck.checkAll(anyLong())).thenReturn(List.of());
        when(globalTrend.today()).thenReturn(null);
        doAnswer(inv -> {
            assertTrue(geminiGate.await(10, TimeUnit.SECONDS));
            return mapper.readTree(INITIAL);
        }).when(gemini).generateJson(anyString(), anyMap());
        doAnswer(inv -> {
            assertTrue(analyzerGate.await(10, TimeUnit.SECONDS)); // 수정 트랜잭션(행 잠금 보유 중)을 붙잡아 둔다
            return new IdeaAnalysis(ItCategory.DEV_TOOLS_INFRA, List.of("kw"));
        }).when(analyzer).analyze(any());
    }

    @AfterEach
    void release() throws Exception {
        geminiGate.countDown();
        analyzerGate.countDown();
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline
                && jdbc.queryForObject("select count(*) from ideas where evaluation_status in ('QUEUED','RUNNING')", Integer.class) > 0) {
            Thread.sleep(25);
        }
    }

    private String signUpAndLogin(String email) throws Exception {
        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"kj\",\"email\":\"" + email + "\",\"password\":\"password123\"}"));
        String res = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"password123\"}"))
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + mapper.readTree(res).get("data").get("accessToken").asString();
    }

    private long create(String token) throws Exception {
        analyzerGate.countDown();
        return mapper.readTree(mvc.perform(post("/api/ideas").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(FULL))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("data").get("id").asLong();
    }

    private JsonNode idea(String token, long id) throws Exception {
        return mapper.readTree(mvc.perform(get("/api/ideas/" + id).header("Authorization", token))
                .andReturn().getResponse().getContentAsString()).get("data");
    }

    private void awaitStatus(String token, long id, String expected) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (expected.equals(idea(token, id).get("evaluationStatus").asString())) return;
            Thread.sleep(25);
        }
        fail("상태가 " + expected + "가 되지 않음");
    }

    private void assertEditRejected(String token, long id) throws Exception {
        mvc.perform(patch("/api/ideas/" + id).header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(EDIT))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("I005"));
        assertEquals("질문 정리 도구", idea(token, id).get("solution").asString()); // 반영되지 않음
    }

    private void assertEditAllowed(String token, long id) throws Exception {
        mvc.perform(patch("/api/ideas/" + id).header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(EDIT))
                .andExpect(status().isOk());
        assertEquals("완전히 다른 해결", idea(token, id).get("solution").asString());
    }

    @Test
    void QUEUED와_RUNNING이면_수정은_I005로_거절되고_내용이_바뀌지_않는다() throws Exception {
        String token = signUpAndLogin("lock@cue.com");
        long id = create(token);

        jdbc.update("update ideas set evaluation_status = 'QUEUED' where id = ?", id);
        assertEditRejected(token, id);

        // 실제 실행 중(RUNNING): Gemini 목이 래치에서 기다린다
        jdbc.update("update ideas set evaluation_status = 'IDLE' where id = ?", id);
        geminiGate = new CountDownLatch(1);
        mvc.perform(post("/api/ideas/" + id + "/evaluate").header("Authorization", token)).andExpect(status().isAccepted());
        awaitStatus(token, id, "RUNNING");
        assertEditRejected(token, id);

        geminiGate.countDown();
        awaitStatus(token, id, "IDLE");
        assertEditAllowed(token, id); // 완료 뒤에는 수정할 수 있다
    }

    @Test
    void 실패_뒤에는_수정할_수_있고_소유자가_아니면_I002가_우선한다() throws Exception {
        String owner = signUpAndLogin("own@cue.com");
        String other = signUpAndLogin("oth@cue.com");
        long id = create(owner);

        jdbc.update("update ideas set evaluation_status = 'RUNNING' where id = ?", id);
        mvc.perform(patch("/api/ideas/" + id).header("Authorization", other).contentType(MediaType.APPLICATION_JSON).content(EDIT))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("I002")); // 평가 중이어도 I002
        mvc.perform(patch("/api/ideas/999999").header("Authorization", owner).contentType(MediaType.APPLICATION_JSON).content(EDIT))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("I001"));

        jdbc.update("update ideas set evaluation_status = 'FAILED', last_evaluation_error_code = 'G002' where id = ?", id);
        assertEditAllowed(owner, id);
    }

    @Test
    void 평가_중에도_가중치_예약과_확인_처리는_성공한다() throws Exception {
        String token = signUpAndLogin("free@cue.com");
        long id = create(token);
        jdbc.update("update ideas set evaluation_status = 'RUNNING' where id = ?", id);

        mvc.perform(patch("/api/ideas/" + id + "/weights").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"weights\":{\"PROBLEM_IMPORTANCE\":20,\"MARKET_DEMAND\":20,\"MARKET_DIFFERENTIATION\":20,"
                        + "\"TECH_FEASIBILITY\":20,\"TREND\":20}}")).andExpect(status().isOk());
        mvc.perform(post("/api/ideas/" + id + "/check").header("Authorization", token)).andExpect(status().isOk());
    }

    @Test
    void 수정이_먼저_행을_잡고_있으면_평가_시작이_기다렸다가_수정된_내용으로_시작한다() throws Exception {
        String token = signUpAndLogin("race1@cue.com");
        long id = create(token);
        analyzerGate = new CountDownLatch(1);

        // 수정 요청이 분석기에서 멈춘 상태 = 상태를 IDLE로 확인하고 행 잠금을 쥔 채 진행 중
        CompletableFuture<Integer> edit = CompletableFuture.supplyAsync(() -> {
            try {
                return mvc.perform(patch("/api/ideas/" + id).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(EDIT)).andReturn().getResponse().getStatus();
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline && mockingDetails(analyzer).getInvocations().size() < 2) Thread.sleep(10); // create 1 + edit 1
        Thread.sleep(150);

        CompletableFuture<Integer> evaluate = CompletableFuture.supplyAsync(() -> {
            try {
                return mvc.perform(post("/api/ideas/" + id + "/evaluate").header("Authorization", token)).andReturn().getResponse().getStatus();
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        Thread.sleep(400);
        assertFalse(evaluate.isDone(), "수정이 끝나기 전에는 평가 시작이 기다려야 한다");
        assertFalse(edit.isDone());

        analyzerGate.countDown();
        assertEquals(200, edit.get(10, TimeUnit.SECONDS));
        assertEquals(202, evaluate.get(10, TimeUnit.SECONDS));
        awaitStatus(token, id, "IDLE");
        assertEquals("완전히 다른 해결", idea(token, id).get("solution").asString());
        assertTrue(idea(token, id).get("totalScore").asDouble() > 0); // 평가는 수정 뒤에 돌았다
    }

    @Test
    void 평가가_먼저_시작됐으면_뒤따르는_수정은_거절되어_반영되지_않는다() throws Exception {
        String token = signUpAndLogin("race2@cue.com");
        long id = create(token);
        geminiGate = new CountDownLatch(1);

        mvc.perform(post("/api/ideas/" + id + "/evaluate").header("Authorization", token)).andExpect(status().isAccepted());
        // QUEUED로 바뀐 직후(실행기가 집기 전후 모두)에도 수정은 거절된다
        assertEditRejected(token, id);
        awaitStatus(token, id, "RUNNING");
        assertEditRejected(token, id);
        geminiGate.countDown();
        awaitStatus(token, id, "IDLE");
        assertEquals("질문 정리 도구", idea(token, id).get("solution").asString());
    }
}
