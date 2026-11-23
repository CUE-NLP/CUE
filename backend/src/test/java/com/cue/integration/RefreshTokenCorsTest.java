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
@TestPropertySource(properties = {"spring.docker.compose.enabled=false", "cue.auth.cleanup.cron=-"})
class RefreshTokenCorsTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.cue.auth.service.AuthService authService;

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

    private org.springframework.test.web.servlet.ResultActions logout(String access, String refresh) throws Exception {
        return mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + access)
                .contentType(MediaType.APPLICATION_JSON).content(body(refresh)));
    }

    @Test
    void 로그아웃하면_리프레시_토큰만_폐기되고_다른_기기는_유지된다() throws Exception {
        JsonNode deviceA = login("logout@cue.com");
        JsonNode deviceB = mapper.readTree(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"logout@cue.com\",\"password\":\"password123\"}"))
                .andReturn().getResponse().getContentAsString()).get("data");
        String accessA = deviceA.get("accessToken").asString();
        String refreshA = deviceA.get("refreshToken").asString();
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + accessA)).andExpect(status().isOk());

        logout(accessA, refreshA).andExpect(status().isOk());

        // 액세스 토큰은 서버가 폐기하지 않으므로 만료 전까지 유효하다 (클라이언트가 지우는 것이 전제). 같은 요청으로 다시 로그아웃해도 멱등이다.
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + accessA)).andExpect(status().isOk());
        logout(accessA, refreshA).andExpect(status().isOk());

        // 다른 기기(B)의 액세스·리프레시 토큰은 영향 없음
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + deviceB.get("accessToken").asString()))
                .andExpect(status().isOk());
        refresh(deviceB.get("refreshToken").asString()).andExpect(status().isOk());

        // 폐기된 리프레시 토큰 재사용 → A010
        refresh(refreshA).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("A010"));
    }

    @Test
    void 토큰_없이_로그아웃하면_A006() throws Exception {
        mvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON).content(body("x")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("A006"));
    }

    @Test
    void 로그아웃은_멱등이고_남의_리프레시_토큰은_폐기하지_않는다() throws Exception {
        JsonNode owner = login("owner-lo@cue.com");
        JsonNode other = login("other-lo@cue.com");
        // other 가 owner 의 리프레시 토큰을 넣어 로그아웃해도 owner 의 토큰은 유지된다
        logout(other.get("accessToken").asString(), owner.get("refreshToken").asString()).andExpect(status().isOk());
        refresh(owner.get("refreshToken").asString()).andExpect(status().isOk());

        // 모르는 리프레시 토큰으로도 성공
        JsonNode third = login("third-lo@cue.com");
        logout(third.get("accessToken").asString(), "unknown").andExpect(status().isOk());
    }

    @Test
    void 정리_작업은_만료된_토큰_행만_삭제한다() throws Exception {
        JsonNode keep = login("cleanup-keep@cue.com");
        JsonNode expired = login("cleanup-old@cue.com");
        logout(expired.get("accessToken").asString(), expired.get("refreshToken").asString()).andExpect(status().isOk());
        JsonNode revokedYetValid = login("cleanup-revoked@cue.com");
        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content(body(revokedYetValid.get("refreshToken").asString()))).andExpect(status().isOk()); // 회전: 이전 행은 폐기됨, 아직 만료 전

        // cleanup-old 의 리프레시 토큰만 만료시킨다
        jdbc.update("update refresh_tokens set expires_at = now() - interval '1 day' where user_id = (select id from users where email = 'cleanup-old@cue.com')");

        int deleted = authService.cleanupExpired(java.time.LocalDateTime.now());
        org.junit.jupiter.api.Assertions.assertTrue(deleted >= 1); // 다른 테스트가 남긴 만료 행도 지워질 수 있다
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbc.queryForObject(
                "select count(*) from refresh_tokens r join users u on u.id = r.user_id where u.email = 'cleanup-old@cue.com'", Integer.class));
        // 폐기됐지만 만료 전인 리프레시 행(재사용 탐지용)과 정상 토큰은 남는다
        org.junit.jupiter.api.Assertions.assertEquals(2, jdbc.queryForObject(
                "select count(*) from refresh_tokens r join users u on u.id = r.user_id where u.email = 'cleanup-revoked@cue.com'", Integer.class));
        refresh(keep.get("refreshToken").asString()).andExpect(status().isOk());
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
