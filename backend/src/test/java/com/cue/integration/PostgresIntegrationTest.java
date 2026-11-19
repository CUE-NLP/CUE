package com.cue.integration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 통합 테스트 공통 설정: PostgreSQL 컨테이너 하나와 스프링 컨텍스트를 하위 테스트 클래스가 함께 쓴다. (Docker 필요) */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {"spring.docker.compose.enabled=false", "cue.auth.cleanup.cron=-",
        "cue.evaluation.enabled=false", "cue.evaluation.auto-on-save=false"})
abstract class PostgresIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    static {
        POSTGRES.start();
    }
}
