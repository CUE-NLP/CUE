package com.cue.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** 실제 PostgreSQL 컨테이너로 마이그레이션부터 인증·아이디어 흐름까지 검증한다. (Docker 필요) */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = "spring.docker.compose.enabled=false")
class AuthIdeaFlowTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired org.springframework.security.oauth2.jwt.JwtEncoder jwtEncoder;


    private String signUpAndLogin(String email) throws Exception {
        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"kj\",\"email\":\"" + email + "\",\"password\":\"password123\"}"))
                .andExpect(status().isCreated());
        String res = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"password123\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return "Bearer " + mapper.readTree(res).get("data").get("accessToken").asString();
    }

    @Test
    void 토큰없이_접근하면_401_에러코드를_반환한다() throws Exception {
        mvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("A006"));
    }

    @Test
    void 가입_로그인_아이디어저장_리더보드_흐름() throws Exception {
        String token = signUpAndLogin("flow@cue.com");

        mvc.perform(get("/api/users/me").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("flow@cue.com"));

        mvc.perform(post("/api/ideas").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"problem\":\"p\",\"target\":\"t\",\"solution\":\"s\",\"expectation\":\"e\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.scores.length()").value(4));

        mvc.perform(get("/api/ideas").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void 다른_사용자의_아이디어는_조회할_수_없다() throws Exception {
        String owner = signUpAndLogin("owner@cue.com");
        String other = signUpAndLogin("other@cue.com");
        String created = mvc.perform(post("/api/ideas").header("Authorization", owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"problem\":\"p\",\"target\":\"t\",\"solution\":\"s\",\"expectation\":\"e\"}"))
                .andReturn().getResponse().getContentAsString();
        long id = mapper.readTree(created).get("data").get("id").asLong();

        mvc.perform(get("/api/ideas/" + id).header("Authorization", other))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("I002"));
    }

    @Test
    void 입력값이_잘못되면_필드별_에러를_반환한다() throws Exception {
        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("G004"))
                .andExpect(jsonPath("$.data").isNotEmpty());
    }

    @Test
    void 가중치는_예약되어_응답에_표시되고_합이_100이_아니면_400() throws Exception {
        String token = signUpAndLogin("weight@cue.com");
        String created = mvc.perform(post("/api/ideas").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"problem\":\"p\",\"target\":\"t\",\"solution\":\"s\",\"expectation\":\"e\"}"))
                .andReturn().getResponse().getContentAsString();
        long id = mapper.readTree(created).get("data").get("id").asLong();

        mvc.perform(patch("/api/ideas/" + id + "/weights").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"weights\":{\"PROBLEM_IMPORTANCE\":40,\"MARKET_DEMAND\":30,"
                                + "\"MARKET_DIFFERENTIATION\":20,\"TECH_FEASIBILITY\":10}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scores[0].weightPercent").value(25))
                .andExpect(jsonPath("$.data.scores[0].pendingWeightPercent").value(40));

        mvc.perform(patch("/api/ideas/" + id + "/weights").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"weights\":{\"PROBLEM_IMPORTANCE\":50,\"MARKET_DEMAND\":30,"
                                + "\"MARKET_DIFFERENTIATION\":20,\"TECH_FEASIBILITY\":10}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("I003"));
    }

    @Test
    void 응답은_CommonResponse로_감싸진다() throws Exception {
        String token = signUpAndLogin("wrap@cue.com");
        mvc.perform(get("/api/users/me").header("Authorization", token))
                .andExpect(jsonPath("$.result").value("SUCCESS"))
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void JWT_실패_원인에_따라_에러코드가_구분된다() throws Exception {
        // 변조(형식 오류)
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer abc.def"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.result").value("FAIL"))
                .andExpect(jsonPath("$.code").value("A003"));

        // 만료된 토큰
        java.time.Instant past = java.time.Instant.now().minusSeconds(3600);
        var claims = org.springframework.security.oauth2.jwt.JwtClaimsSet.builder()
                .subject("1").issuedAt(past.minusSeconds(60)).expiresAt(past).build();
        var header = org.springframework.security.oauth2.jwt.JwsHeader
                .with(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build();
        String expired = jwtEncoder.encode(
                org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(header, claims)).getTokenValue();
        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("A004"));
    }

    @Test
    void 존재하지_않는_경로는_404_공통응답() throws Exception {
        String token = signUpAndLogin("nf@cue.com");
        mvc.perform(get("/api/nothing").header("Authorization", token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("G006"));
    }
}
