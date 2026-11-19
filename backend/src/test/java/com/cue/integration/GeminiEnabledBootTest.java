package com.cue.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.cue.evaluation.service.EvaluationScheduler;
import com.cue.global.ai.GeminiClient;
import com.cue.idea.service.IdeaAnalyzer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 기본 설정(Gemini 활성, 스케줄러 활성, API 키 없음)으로 앱이 부팅되고 키 없이도 동작하는지 확인한다.
 * (생성자가 둘인 Gemini 빈이 기본 설정에서 기동 실패하던 문제의 회귀 방지)
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {"spring.docker.compose.enabled=false", "cue.gemini.api-key="})
class GeminiEnabledBootTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @Autowired ApplicationContext ctx;

    @Test
    void 기본_설정으로_부팅되고_키가_없으면_Gemini는_사용_불가() {
        assertEquals(1, ctx.getBeanNamesForType(EvaluationScheduler.class).length);
        assertNotNull(ctx.getBean(IdeaAnalyzer.class));
        assertFalse(ctx.getBean(GeminiClient.class).available());
    }
}
