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

/** 시연 API가 꺼져 있을 때(기본값)와 미인증. (Docker 필요) */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {"spring.docker.compose.enabled=false", "cue.gemini.enabled=false", "cue.evaluation.enabled=false",
        "cue.evaluation.auto-on-save=false"})
class DemoApiDisabledTest extends DemoTestSupport {
    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @MockitoBean GeminiClient gemini;
    @MockitoBean GlobalTrendService globalTrend;

    @BeforeEach
    void reset() {
        truncate();
        when(gemini.available()).thenReturn(true);
    }

    @Test
    void 꺼져_있으면_D001로_거절하고_아무것도_큐에_넣지_않는다() throws Exception {
        String a = signUpAndLogin("off@cue.com");
        long id = createIdea(a, FULL);
        mvc.perform(post("/api/demo/evaluations/run").header("Authorization", a))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("D001"));
        assertEquals("IDLE", idea(a, id).get("evaluationStatus").asString());
        verify(globalTrend, never()).refresh();
    }

    @Test
    void 로그인하지_않으면_A006이다() throws Exception {
        mvc.perform(post("/api/demo/evaluations/run"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("A006"));
    }
}
