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

/** 시연 API: 평가 대기열이 가득 차 아무것도 넣지 못하면 E004 (대기열 상한 1). (Docker 필요) */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {"spring.docker.compose.enabled=false", "cue.gemini.enabled=false", "cue.evaluation.enabled=false",
        "cue.evaluation.auto-on-save=false", "cue.evaluation.queue-capacity=1", "cue.demo.enabled=true"})
class DemoQueueFullTest extends DemoTestSupport {
    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @MockitoBean GeminiClient gemini;
    @MockitoBean SourceCheckService sourceCheck;
    @MockitoBean GlobalTrendService globalTrend;

    private volatile CountDownLatch gate = new CountDownLatch(1);

    @BeforeEach
    void reset() {
        truncate();
        org.mockito.Mockito.reset(gemini, sourceCheck, globalTrend);
        gate = new CountDownLatch(1);
        when(gemini.available()).thenReturn(true);
        when(sourceCheck.checkAll(anyLong())).thenReturn(List.of());
        when(globalTrend.today()).thenReturn(null);
        when(globalTrend.isCurrent()).thenReturn(true);
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
    void 대기열이_가득_차_하나도_넣지_못하면_E004이고_상태는_IDLE로_남는다() throws Exception {
        String a = signUpAndLogin("qa@cue.com");
        String b = signUpAndLogin("qb@cue.com");
        long busy = createIdea(a, FULL);
        long waiting = createIdea(a, FULL);
        long mine = createIdea(b, FULL);

        mvc.perform(post("/api/ideas/" + busy + "/evaluate").header("Authorization", a)).andExpect(status().isAccepted());
        awaitStatus(a, busy, "RUNNING");                                   // 실행기 점유
        mvc.perform(post("/api/ideas/" + waiting + "/evaluate").header("Authorization", a)).andExpect(status().isAccepted()); // 대기열 1칸

        mvc.perform(post("/api/demo/evaluations/run").header("Authorization", b))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("E004"));
        assertEquals("IDLE", idea(b, mine).get("evaluationStatus").asString());
    }
}
