package com.cue.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cue.global.ai.GeminiClient;
import com.cue.source.service.SourceCheckService;
import com.cue.trend.service.GlobalTrendService;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 시연용 "지금 조회·평가" API (demo 활성, 실제 단일 스레드 실행기; Gemini·자료 조회·트렌드만 목). (Docker 필요) */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {"spring.docker.compose.enabled=false", "cue.gemini.enabled=false", "cue.evaluation.enabled=false",
        "cue.evaluation.delay-ms=0", "cue.evaluation.auto-on-save=false", "cue.demo.enabled=true", "cue.demo.cooldown-seconds=30"})
class DemoEvaluationApiTest extends DemoTestSupport {
    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @MockitoBean GeminiClient gemini;
    @MockitoBean SourceCheckService sourceCheck;
    @MockitoBean GlobalTrendService globalTrend;

    private volatile CountDownLatch gate = new CountDownLatch(0);

    @BeforeEach
    void reset() {
        truncate();
        org.mockito.Mockito.reset(gemini, sourceCheck, globalTrend);
        gate = new CountDownLatch(0);
        when(gemini.available()).thenReturn(true);
        when(sourceCheck.checkAll(anyLong())).thenReturn(List.of());
        when(globalTrend.today()).thenReturn(null);
        when(globalTrend.isCurrent()).thenReturn(false);
        doAnswer(inv -> {
            assertTrue(gate.await(10, TimeUnit.SECONDS));
            return mapper.readTree(INITIAL);
        }).when(gemini).generateJson(anyString(), anyMap());
    }

    @AfterEach
    void release() throws Exception {
        gate.countDown();
        awaitNoActive();
    }

    @Test
    void 본인의_ACTIVE_아이디어만_큐에_넣고_보류와_다른_사용자_아이디어는_건드리지_않는다() throws Exception {
        String a = signUpAndLogin("a@cue.com");
        String b = signUpAndLogin("b@cue.com");
        long a1 = createIdea(a, FULL);
        long hold = createIdea(a, HOLD);
        long a2 = createIdea(a, FULL);
        long b1 = createIdea(b, FULL);

        JsonNode res = run(a, null, 202); // 본문 없이도 된다 (force=false)
        assertEquals("200", res.get("code").asString());
        assertEquals(List.of(a1, a2), List.of(res.get("data").get("queued").get(0).asLong(), res.get("data").get("queued").get(1).asLong()));
        assertEquals(0, res.get("data").get("skipped").size());
        assertTrue(res.get("data").get("trendRefreshed").asBoolean());

        awaitStatus(a, a1, "IDLE");
        awaitStatus(a, a2, "IDLE");
        assertTrue(idea(a, a1).get("totalScore").asDouble() > 0);
        assertEquals("IDLE", idea(a, hold).get("evaluationStatus").asString());
        assertEquals(0, idea(a, hold).get("totalScore").asDouble());
        assertEquals(0, idea(b, b1).get("totalScore").asDouble());
        verify(sourceCheck, never()).checkAll(b1);
        verify(sourceCheck, never()).checkAll(hold);
        verify(globalTrend, timeout(5000)).refresh(false);
    }

    @Test
    void 오늘_트렌드가_이미_있으면_force가_아닐_때_다시_수집하지_않는다() throws Exception {
        when(globalTrend.isCurrent()).thenReturn(true);
        String a = signUpAndLogin("cur@cue.com");
        long id = createIdea(a, FULL);

        JsonNode res = run(a, "{\"force\":false}", 202);
        assertFalse(res.get("data").get("trendRefreshed").asBoolean());
        awaitStatus(a, id, "IDLE");
        verify(globalTrend, never()).refresh(anyBoolean());
    }

    @Test
    void 이미_대기_진행_중인_아이디어는_건너뛴다() throws Exception {
        String a = signUpAndLogin("skip@cue.com");
        long queuedIdea = createIdea(a, FULL);
        long runningIdea = createIdea(a, FULL);
        long free = createIdea(a, FULL);
        jdbc.update("update ideas set evaluation_status = 'QUEUED' where id = ?", queuedIdea);
        jdbc.update("update ideas set evaluation_status = 'RUNNING' where id = ?", runningIdea);

        JsonNode data = run(a, null, 202).get("data");
        assertEquals(1, data.get("queued").size());
        assertEquals(free, data.get("queued").get(0).asLong());
        assertEquals(2, data.get("skipped").size());
        assertEquals(queuedIdea, data.get("skipped").get(0).get("ideaId").asLong());
        assertEquals("ALREADY_QUEUED", data.get("skipped").get(0).get("reason").asString());
        assertEquals("ALREADY_RUNNING", data.get("skipped").get(1).get("reason").asString());
        awaitStatus(a, free, "IDLE");
        jdbc.update("update ideas set evaluation_status = 'IDLE'"); // 정리
    }

    @Test
    void force가_true면_트렌드를_강제로_다시_수집하고_source_해시를_바꿔_변경_감지되게_한다() throws Exception {
        when(globalTrend.isCurrent()).thenReturn(true); // 오늘 트렌드가 있어도 force면 다시 수집
        String a = signUpAndLogin("force@cue.com");
        String b = signUpAndLogin("forceb@cue.com");
        long id = createIdea(a, FULL);
        long other = createIdea(b, FULL);
        jdbc.update("update sources set content_hash = 'real-hash'");
        gate = new CountDownLatch(1); // 평가가 시작되기 전 상태(해시 변경 직후)를 본다
        // 실행기가 먼저 읽지 못하게: 큐에 넣기 전에 해시가 바뀌어야 하므로, 응답을 받은 시점에는 이미 바뀌어 있다

        JsonNode data = run(a, "{\"force\":true}", 202).get("data");
        assertTrue(data.get("trendRefreshed").asBoolean());
        assertEquals(id, data.get("queued").get(0).asLong());
        assertEquals(List.of("FORCED-REFRESH"), jdbc.queryForList("select distinct content_hash from sources where idea_id = ?", String.class, id));
        assertEquals(List.of("real-hash"), jdbc.queryForList("select distinct content_hash from sources where idea_id = ?", String.class, other));
        verify(globalTrend, timeout(5000)).refresh(true);
        gate.countDown();
        awaitStatus(a, id, "IDLE");
    }

    @Test
    void 같은_사용자가_쿨다운_안에_다시_누르면_D002이고_실패한_요청은_쿨다운을_쓰지_않는다() throws Exception {
        String a = signUpAndLogin("cool@cue.com");
        long id = createIdea(a, FULL);

        when(gemini.available()).thenReturn(false);
        mvc.perform(post("/api/demo/evaluations/run").header("Authorization", a))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("E001")); // 쿨다운 소비 안 함
        when(gemini.available()).thenReturn(true);

        run(a, null, 202);
        mvc.perform(post("/api/demo/evaluations/run").header("Authorization", a))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("D002"));
        awaitStatus(a, id, "IDLE");

        // 다른 사용자는 쿨다운과 무관하다
        String b = signUpAndLogin("coolb@cue.com");
        run(b, null, 202);
    }
}
