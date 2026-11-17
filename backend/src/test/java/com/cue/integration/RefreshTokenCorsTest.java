package com.cue.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** CORS 설정과 리프레시 토큰(발급·회전·재사용 탐지·로그아웃) 통합 테스트. (Docker 필요) */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = "spring.docker.compose.enabled=false")
class RefreshTokenCorsTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    private JsonNode login(String email) throws Exception {
        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"kj\",\"email\":\"" + email + "\",\"password\":\"password123\"}"));
        String res = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"password123\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(res).get("data");
    }

    private String body(String refreshToken) {
        return "{\"refreshToken\":\"" + refreshToken + "\"}";
    }

    private org.springframework.test.web.servlet.ResultActions refresh(String refreshToken) throws Exception {
        return mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(body(refreshToken)));
    }

    @Test
    void 허용된_오리진의_preflight는_통과한다() throws Exception {
        mvc.perform(options("/api/ideas")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "PATCH")
                        .header("Access-Control-Request-Headers", "Authorization,Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().string("Access-Control-Allow-Headers", org.hamcrest.Matchers.containsString("Authorization")))
                .andExpect(header().string("Access-Control-Allow-Methods", org.hamcrest.Matchers.containsString("PATCH")));
        mvc.perform(options("/api/ideas")
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"));
    }

    @Test
    void 허용되지_않은_오리진의_preflight는_거부된다() throws Exception {
        mvc.perform(options("/api/ideas")
                        .header("Origin", "http://evil.example.com")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void 실제_요청에도_CORS_헤더가_붙는다() throws Exception {
        mvc.perform(get("/api/users/me").header("Origin", "http://localhost:5173"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }

    @Test
    void 로그인_리프레시_재사용거부_로그아웃_흐름() throws Exception {
        JsonNode first = login("refresh@cue.com");
        String access1 = first.get("accessToken").asString();
        String refresh1 = first.get("refreshToken").asString();

        // DB에는 원문이 아니라 해시만 저장된다
        Integer plain = jdbc.queryForObject("select count(*) from refresh_tokens where token_hash = ?", Integer.class, refresh1);
        org.junit.jupiter.api.Assertions.assertEquals(0, plain);

        // 재발급: 새 토큰 쌍이 나오고 새 액세스 토큰으로 접근할 수 있다
        String res = refresh(refresh1).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andReturn().getResponse().getContentAsString();
        JsonNode second = mapper.readTree(res).get("data");
        String refresh2 = second.get("refreshToken").asString();
        org.junit.jupiter.api.Assertions.assertNotEquals(refresh1, refresh2);
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + second.get("accessToken").asString()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + access1)).andExpect(status().isOk());

        // 이전 토큰 재사용 → A010, 그리고 회전된 최신 토큰까지 모두 폐기된다
        refresh(refresh1).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("A010"));
        refresh(refresh2).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("A010"));
    }

    @Test
    void 로그아웃하면_리프레시_토큰이_폐기된다() throws Exception {
        String refresh = login("logout@cue.com").get("refreshToken").asString();
        mvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON).content(body(refresh)))
                .andExpect(status().isOk());
        refresh(refresh).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("A010"));
        // 멱등: 다시 로그아웃해도, 모르는 토큰으로 로그아웃해도 성공
        mvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON).content(body(refresh)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON).content(body("unknown")))
                .andExpect(status().isOk());
    }

    @Test
    void 모르는_토큰과_만료된_토큰은_각각_다른_코드로_거부된다() throws Exception {
        refresh("not-a-real-token").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("A008"));

        String refresh = login("expired@cue.com").get("refreshToken").asString();
        jdbc.update("update refresh_tokens set expires_at = now() - interval '1 day'");
        refresh(refresh).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("A009"));

        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("G004"));
    }
}
