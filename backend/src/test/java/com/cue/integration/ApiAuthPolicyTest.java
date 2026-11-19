package com.cue.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.cue.global.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import com.cue.global.security.SecurityErrorWriter;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import tools.jackson.databind.ObjectMapper;

/**
 * 인증 정책 통합 테스트: 회원가입·로그인·재발급·로그아웃을 뺀 모든 /api/** 는 액세스 토큰이 필요하고,
 * 토큰 상태별로 올바른 A00x 코드가 나가며, 타인의 아이디어는 I002로 막힌다. (엔드포인트 목록 순회)
 */
class ApiAuthPolicyTest extends PostgresIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JwtEncoder jwtEncoder;
    @Autowired SecurityErrorWriter errorWriter;

    /** 인증이 필요한 모든 엔드포인트. {id}는 실제 아이디어 ID로 치환한다. 새 API를 만들면 여기에도 추가한다. */
    record Endpoint(HttpMethod method, String path, String body) {
        boolean ownsIdea() { return path.contains("{id}"); }
        @Override public String toString() { return method + " " + path; }
    }

    static final String IDEA_BODY = "{\"problem\":\"p\",\"target\":\"t\",\"solution\":\"s\",\"expectation\":\"e\",\"keywords\":[\"kw1\"]}";
    static final String WEIGHTS_BODY = "{\"weights\":{\"PROBLEM_IMPORTANCE\":25,\"MARKET_DEMAND\":25,"
            + "\"MARKET_DIFFERENTIATION\":25,\"TECH_FEASIBILITY\":25}}";

    static List<Endpoint> protectedEndpoints() {
        return List.of(
                new Endpoint(HttpMethod.GET, "/api/users/me", null),
                new Endpoint(HttpMethod.POST, "/api/auth/logout", "{\"refreshToken\":\"x\"}"),
                new Endpoint(HttpMethod.POST, "/api/ideas", IDEA_BODY),
                new Endpoint(HttpMethod.GET, "/api/ideas", null),
                new Endpoint(HttpMethod.GET, "/api/ideas/{id}", null),
                new Endpoint(HttpMethod.PATCH, "/api/ideas/{id}", "{\"problem\":\"x\"}"),
                new Endpoint(HttpMethod.PATCH, "/api/ideas/{id}/weights", WEIGHTS_BODY),
                new Endpoint(HttpMethod.POST, "/api/ideas/{id}/check", null),
                new Endpoint(HttpMethod.GET, "/api/ideas/{id}/history", null),
                new Endpoint(HttpMethod.GET, "/api/ideas/{id}/evaluations", null));
    }

    static Stream<Arguments> endpoints() {
        return protectedEndpoints().stream().map(Arguments::of);
    }

    private MockHttpServletRequestBuilder request(Endpoint e, Long ideaId) {
        String path = e.path().replace("{id}", String.valueOf(ideaId));
        MockHttpServletRequestBuilder b = switch (e.method().name()) {
            case "GET" -> get(path);
            case "POST" -> post(path);
            case "PATCH" -> patch(path);
            default -> throw new IllegalArgumentException(e.toString());
        };
        if (e.body() != null) {
            b.contentType(MediaType.APPLICATION_JSON).content(e.body());
        }
        return b;
    }

    private ResultActions call(Endpoint e, Long ideaId, String authorization) throws Exception {
        MockHttpServletRequestBuilder b = request(e, ideaId);
        if (authorization != null) {
            b.header("Authorization", authorization);
        }
        return mvc.perform(b);
    }

    private String signUpAndLogin(String email) throws Exception {
        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"kj\",\"email\":\"" + email + "\",\"password\":\"password123\"}"))
                .andExpect(status().isCreated());
        String res = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"password123\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return "Bearer " + mapper.readTree(res).get("data").get("accessToken").asString();
    }

    private Long createIdea(String token) throws Exception {
        String res = mvc.perform(post("/api/ideas").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(IDEA_BODY))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(res).get("data").get("id").asLong();
    }

    private String signedToken(JwtEncoder encoder, String subject, Instant issuedAt, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder().id(java.util.UUID.randomUUID().toString()).subject(subject).issuedAt(issuedAt).expiresAt(expiresAt)
                .claim("email", "x@cue.com").claim("nickname", "x").build();
        return "Bearer " + encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    private void expectError(ResultActions r, ErrorCode code) throws Exception {
        r.andExpect(status().is(code.getStatus().value()))
                .andExpect(jsonPath("$.result").value("FAIL"))
                .andExpect(jsonPath("$.code").value(code.getCode()))
                .andExpect(jsonPath("$.message").value(code.getMessage()));
    }

    // ---------- 토큰 상태별 A00x ----------

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    void 토큰이_없으면_A006(Endpoint e) throws Exception {
        expectError(call(e, 1L, null), ErrorCode.JWT_MISSING);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    void Bearer_방식이_아니면_A006(Endpoint e) throws Exception {
        expectError(call(e, 1L, "Basic dXNlcjpwYXNz"), ErrorCode.JWT_MISSING);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    void 형식이_잘못된_토큰은_A003(Endpoint e) throws Exception {
        expectError(call(e, 1L, "Bearer not-a-jwt"), ErrorCode.JWT_MALFORMED);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    void 만료된_토큰은_A004(Endpoint e) throws Exception {
        Instant past = Instant.now().minusSeconds(3600);
        String expired = signedToken(jwtEncoder, "1", past.minusSeconds(60), past);
        expectError(call(e, 1L, expired), ErrorCode.JWT_EXPIRED);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    void 다른_키로_서명된_토큰은_A005(Endpoint e) throws Exception {
        JwtEncoder other = new NimbusJwtEncoder(new ImmutableSecret<>(
                new SecretKeySpec("another-secret-key-another-secret-key!!".getBytes(StandardCharsets.UTF_8), "HmacSHA256")));
        String forged = signedToken(other, "1", Instant.now(), Instant.now().plusSeconds(600));
        expectError(call(e, 1L, forged), ErrorCode.JWT_INVALID);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    void 서명이_변조된_토큰은_A005(Endpoint e) throws Exception {
        String valid = signedToken(jwtEncoder, "1", Instant.now(), Instant.now().plusSeconds(600));
        expectError(call(e, 1L, valid.substring(0, valid.length() - 4) + "AAAA"), ErrorCode.JWT_INVALID);
    }

    @Test
    void 서명은_맞지만_sub가_숫자가_아닌_토큰은_A005() throws Exception {
        String weird = signedToken(jwtEncoder, "not-a-number", Instant.now(), Instant.now().plusSeconds(600));
        expectError(mvc.perform(get("/api/users/me").header("Authorization", weird)), ErrorCode.JWT_INVALID);
    }

    @Test
    void 가입이_삭제된_사용자의_유효한_토큰은_U002() throws Exception {
        String ghost = signedToken(jwtEncoder, "987654321", Instant.now(), Instant.now().plusSeconds(600));
        expectError(mvc.perform(get("/api/users/me").header("Authorization", ghost)), ErrorCode.USER_NOT_FOUND);
        expectError(mvc.perform(post("/api/ideas").header("Authorization", ghost)
                .contentType(MediaType.APPLICATION_JSON).content(IDEA_BODY)), ErrorCode.USER_NOT_FOUND);
    }

    @Test
    void 권한_부족_응답은_A002_403이다() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        errorWriter.handle(new org.springframework.mock.web.MockHttpServletRequest(), res, new AccessDeniedException("denied"));
        assertEquals(403, res.getStatus());
        assertEquals("A002", mapper.readTree(res.getContentAsString(StandardCharsets.UTF_8)).get("code").asString());
    }

    // ---------- 소유자 검사 ----------

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    void 유효한_토큰이면_인증_에러가_아니다(Endpoint e) throws Exception {
        String token = signUpAndLogin("ok-" + System.nanoTime() + "@cue.com");
        Long ideaId = e.ownsIdea() ? createIdea(token) : 1L;
        String code = mapper.readTree(call(e, ideaId, token).andReturn().getResponse().getContentAsString()).get("code").asString();
        assertEquals("200", code, e + " 는 본인 토큰으로 성공해야 한다");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    void 타인의_아이디어는_I002_존재하지_않는_아이디어는_I001(Endpoint e) throws Exception {
        if (!e.ownsIdea()) {
            return;
        }
        String owner = signUpAndLogin("owner-" + System.nanoTime() + "@cue.com");
        String other = signUpAndLogin("other-" + System.nanoTime() + "@cue.com");
        Long ideaId = createIdea(owner);
        expectError(call(e, ideaId, other), ErrorCode.IDEA_ACCESS_DENIED);
        expectError(call(e, 999_999_999L, owner), ErrorCode.IDEA_NOT_FOUND);
    }

    @Test
    void 타인의_아이디어는_리더보드에_나오지_않는다() throws Exception {
        String owner = signUpAndLogin("lb-owner-" + System.nanoTime() + "@cue.com");
        String other = signUpAndLogin("lb-other-" + System.nanoTime() + "@cue.com");
        createIdea(owner);
        mvc.perform(get("/api/ideas").header("Authorization", other))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
    }

    // ---------- 열린 경로 범위 ----------

    @Test
    void 인증_없이_열린_API는_가입_로그인_재발급뿐이다() throws Exception {
        // 열린 경로는 토큰이 없어도 인증 에러(A00x)가 아니라 입력 검증(G004)까지 도달한다.
        for (String path : List.of("/api/users", "/api/auth/login", "/api/auth/refresh")) {
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("G004"));
        }
    }

    @ParameterizedTest
    @MethodSource("closedPaths")
    void 열려_있지_않은_경로는_토큰_없이_A006(String method, String path) throws Exception {
        MockHttpServletRequestBuilder b = switch (method) {
            case "GET" -> get(path);
            default -> post(path).contentType(MediaType.APPLICATION_JSON).content("{}");
        };
        expectError(mvc.perform(b), ErrorCode.JWT_MISSING);
    }

    static Stream<Arguments> closedPaths() {
        return Stream.of(
                Arguments.of("GET", "/api/users"),            // 가입은 POST만 열려 있다
                Arguments.of("GET", "/api/auth/login"),
                Arguments.of("GET", "/api/auth/refresh"),
                Arguments.of("GET", "/api/auth/logout"),
                Arguments.of("GET", "/api/unknown"),
                Arguments.of("POST", "/api/users/me"),
                Arguments.of("GET", "/actuator/info"),
                Arguments.of("GET", "/actuator/env"),
                Arguments.of("GET", "/actuator"),
                Arguments.of("GET", "/error"),
                Arguments.of("GET", "/"));
    }

    @Test
    void 헬스체크와_스웨거_문서는_열려_있다() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }

    @Test
    void JSON이_아닌_Content_Type은_G007_깨진_JSON은_G003() throws Exception {
        expectError(mvc.perform(post("/api/auth/login").contentType(MediaType.TEXT_PLAIN).content("x")),
                ErrorCode.UNSUPPORTED_MEDIA_TYPE);
        expectError(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{broken")),
                ErrorCode.BAD_REQUEST);
    }

    @Test
    void 키워드_검증_실패는_G004_필드_목록을_반환한다() throws Exception {
        String token = signUpAndLogin("kw-" + System.nanoTime() + "@cue.com");
        Long ideaId = createIdea(token);
        for (String keywords : List.of("[\"a\",\"b\",\"c\"]", "[\"\"]", "[\"" + "x".repeat(51) + "\"]")) {
            mvc.perform(post("/api/ideas").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"problem\":\"p\",\"keywords\":" + keywords + "}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("G004"))
                    .andExpect(jsonPath("$.data[0].field").value(org.hamcrest.Matchers.startsWith("keywords")));
            mvc.perform(patch("/api/ideas/" + ideaId).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"keywords\":" + keywords + "}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("G004"));
        }
    }
}
