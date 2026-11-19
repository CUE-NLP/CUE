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
@TestPropertySource(properties = {"spring.docker.compose.enabled=false", "cue.gemini.enabled=false", "cue.auth.cleanup.cron=-"})
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

    // ===== 보류/활성, 수정, 확인, 평가 이력 =====

    @Autowired com.cue.source.repository.SourceRepository sourceRepository;
    @Autowired com.cue.evaluation.service.EvaluationService evaluationService;

    private long createIdea(String token, String body) throws Exception {
        String res = mvc.perform(post("/api/ideas").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(res).get("data").get("id").asLong();
    }

    private long sourceCount(long ideaId) {
        return sourceRepository.findAll().stream().filter(s -> s.getIdea().getId() == ideaId).count();
    }

    private static final String FULL = "{\"problem\":\"개발 질문이 흩어져 있다\",\"target\":\"주니어 개발자\","
            + "\"solution\":\"질문 정리 도구\",\"expectation\":\"시간 절약\"}";

    @Test
    void 항목이_비어_있으면_HOLD로_저장되고_키워드를_추출하지_않는다() throws Exception {
        String token = signUpAndLogin("hold@cue.com");
        long id = createIdea(token, "{\"problem\":\"p\",\"target\":\"t\"}");

        mvc.perform(get("/api/ideas/" + id).header("Authorization", token))
                .andExpect(jsonPath("$.data.status").value("HOLD"));
        org.junit.jupiter.api.Assertions.assertEquals(0, sourceCount(id));

        // 기본 목록(ACTIVE)에는 없고 HOLD 목록에는 있다
        mvc.perform(get("/api/ideas").header("Authorization", token))
                .andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get("/api/ideas?status=HOLD").header("Authorization", token))
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void 모두_채워지면_ACTIVE이고_종류별_API_곱하기_키워드로_수집_설정이_만들어진다() throws Exception {
        String token = signUpAndLogin("active@cue.com");
        long id = createIdea(token, FULL);

        mvc.perform(get("/api/ideas/" + id).header("Authorization", token))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.type").value("IT"))
                .andExpect(jsonPath("$.data.itCategory").value("DEV_TOOLS_INFRA"));
        org.junit.jupiter.api.Assertions.assertEquals(6, sourceCount(id)); // 3 providers x 2 keywords
        org.junit.jupiter.api.Assertions.assertEquals(
                new java.util.HashSet<>(com.cue.idea.entity.ItCategory.DEV_TOOLS_INFRA.searchProviders()),
                sourceRepository.findAll().stream().filter(x -> x.getIdea().getId() == id)
                        .map(com.cue.source.entity.Source::getProvider).collect(java.util.stream.Collectors.toSet()));
    }

    // ===== 사용자 키워드 우선 =====

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.cue.idea.service.IdeaAnalyzer analyzerSpy;

    private static final String FULL_BODY_PREFIX = FULL.substring(0, FULL.length() - 1);

    private java.util.List<String> sourceKeywords(long ideaId) {
        return sourceRepository.findAll().stream().filter(x -> x.getIdea().getId() == ideaId)
                .map(com.cue.source.entity.Source::getKeyword).distinct().sorted().toList();
    }

    private org.springframework.test.web.servlet.ResultActions patchIdea(String token, long id, String body)
            throws Exception {
        return mvc.perform(patch("/api/ideas/" + id).header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void 사용자_키워드로_등록하면_AI_키워드_추출을_하지_않고_종류만_분류한다() throws Exception {
        String token = signUpAndLogin("kwuser@cue.com");
        String res = mvc.perform(post("/api/ideas").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(FULL_BODY_PREFIX + ",\"keywords\":[\" 코드 리뷰 \",\"code review\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.keywords.length()").value(2))
                .andExpect(jsonPath("$.data.keywords[0]").value("코드 리뷰"))
                .andExpect(jsonPath("$.data.keywords[1]").value("code review"))
                .andExpect(jsonPath("$.data.keywordOrigin").value("USER"))
                .andReturn().getResponse().getContentAsString();
        long id = mapper.readTree(res).get("data").get("id").asLong();

        org.mockito.Mockito.verify(analyzerSpy, org.mockito.Mockito.never()).analyze(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(analyzerSpy).analyzeCategory(org.mockito.ArgumentMatchers.any());
        org.junit.jupiter.api.Assertions.assertEquals(6, sourceCount(id));
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("code review", "코드 리뷰"), sourceKeywords(id));
    }

    @Test
    void 키워드가_없거나_비어_있으면_AI가_추출하고_출처는_AI() throws Exception {
        String token = signUpAndLogin("kwai@cue.com");
        String res = mvc.perform(post("/api/ideas").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(FULL_BODY_PREFIX + ",\"keywords\":[]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.keywordOrigin").value("AI"))
                .andExpect(jsonPath("$.data.keywords.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        org.mockito.Mockito.verify(analyzerSpy).analyze(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(analyzerSpy, org.mockito.Mockito.never()).analyzeCategory(org.mockito.ArgumentMatchers.any());
        org.junit.jupiter.api.Assertions.assertEquals(2, mapper.readTree(res).get("data").get("keywords").size());
    }

    @Test
    void 키워드_검증_실패는_G004() throws Exception {
        String token = signUpAndLogin("kwbad@cue.com");
        for (String bad : new String[] {
                "[\"a\",\"b\",\"c\"]", "[\"" + "x".repeat(51) + "\"]", "[\"\"]", "[\"  \"]"}) {
            mvc.perform(post("/api/ideas").header("Authorization", token)
                            .contentType(MediaType.APPLICATION_JSON).content(FULL_BODY_PREFIX + ",\"keywords\":" + bad + "}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("G004"));
        }
        long id = createIdea(token, FULL);
        patchIdea(token, id, "{\"keywords\":[\"a\",\"b\",\"c\"]}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("G004"));
    }

    @Test
    void 수정_시_키워드_null은_출처에_따라_유지_또는_재추출_빈_배열은_AI_재추출_교체는_USER() throws Exception {
        String token = signUpAndLogin("kwpatch@cue.com");
        long id = createIdea(token, FULL_BODY_PREFIX + ",\"keywords\":[\"my keyword\"]}");
        org.junit.jupiter.api.Assertions.assertEquals(3, sourceCount(id)); // 3 providers x 1 keyword

        // USER 출처: 내용이 바뀌어도 키워드 유지, AI 키워드 추출 없음
        org.mockito.Mockito.clearInvocations(analyzerSpy);
        patchIdea(token, id, "{\"solution\":\"완전히 다른 해결 방식\"}")
                .andExpect(jsonPath("$.data.keywords[0]").value("my keyword"))
                .andExpect(jsonPath("$.data.keywordOrigin").value("USER"));
        org.mockito.Mockito.verify(analyzerSpy, org.mockito.Mockito.never()).analyze(org.mockito.ArgumentMatchers.any());

        // 빈 배열: 사용자 키워드를 지우고 AI로 재추출
        patchIdea(token, id, "{\"keywords\":[]}")
                .andExpect(jsonPath("$.data.keywordOrigin").value("AI"))
                .andExpect(jsonPath("$.data.keywords.length()").value(2));
        org.mockito.Mockito.verify(analyzerSpy).analyze(org.mockito.ArgumentMatchers.any());
        org.junit.jupiter.api.Assertions.assertEquals(6, sourceCount(id));

        // AI 출처 + keywords null: 내용 변경 시 재추출
        org.mockito.Mockito.clearInvocations(analyzerSpy);
        patchIdea(token, id, "{\"problem\":\"문제가 바뀌었다\"}").andExpect(jsonPath("$.data.keywordOrigin").value("AI"));
        org.mockito.Mockito.verify(analyzerSpy).analyze(org.mockito.ArgumentMatchers.any());

        // 교체: USER, sources도 새 키워드로
        patchIdea(token, id, "{\"keywords\":[\"replaced one\",\"replaced two\"]}")
                .andExpect(jsonPath("$.data.keywordOrigin").value("USER"));
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("replaced one", "replaced two"), sourceKeywords(id));
    }

    @Test
    void HOLD는_키워드를_저장만_하고_ACTIVE가_될_때_적용한다() throws Exception {
        String token = signUpAndLogin("kwhold@cue.com");
        long id = createIdea(token, "{\"problem\":\"p\",\"target\":\"t\",\"keywords\":[\"saved kw\"]}");
        mvc.perform(get("/api/ideas/" + id).header("Authorization", token))
                .andExpect(jsonPath("$.data.status").value("HOLD"))
                .andExpect(jsonPath("$.data.keywords[0]").value("saved kw"))
                .andExpect(jsonPath("$.data.keywordOrigin").value("USER"));
        org.junit.jupiter.api.Assertions.assertEquals(0, sourceCount(id));
        org.mockito.Mockito.verifyNoInteractions(analyzerSpy);

        patchIdea(token, id, "{\"solution\":\"s\",\"expectation\":\"e\"}")
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("saved kw"), sourceKeywords(id));
        org.mockito.Mockito.verify(analyzerSpy, org.mockito.Mockito.never()).analyze(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 수정으로_모두_채우면_ACTIVE가_되고_키워드를_추출하며_비우면_다시_HOLD() throws Exception {
        String token = signUpAndLogin("edit@cue.com");
        long id = createIdea(token, "{\"problem\":\"개발 질문이 흩어져 있다\",\"target\":\"주니어 개발자\",\"solution\":\"질문 정리 도구\"}");

        mvc.perform(patch("/api/ideas/" + id).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectation\":\"시간 절약\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.problem").value("개발 질문이 흩어져 있다"));
        org.junit.jupiter.api.Assertions.assertEquals(6, sourceCount(id));

        // 다시 수정해도 수집 설정이 중복되지 않는다
        mvc.perform(patch("/api/ideas/" + id).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"solution\":\"새로운 해결 방법 제안\"}"))
                .andExpect(status().isOk());
        org.junit.jupiter.api.Assertions.assertEquals(6, sourceCount(id));

        mvc.perform(patch("/api/ideas/" + id).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"target\":\"\"}"))
                .andExpect(jsonPath("$.data.status").value("HOLD"));
    }

    @Test
    void 점수가_바뀌면_평가_이력_총점_이력_새변화가_기록되고_확인하면_해제된다() throws Exception {
        String token = signUpAndLogin("eval@cue.com");
        long id = createIdea(token, FULL);

        // 변화가 없으면 아무것도 기록하지 않는다 (날짜도 갱신 안 함)
        org.junit.jupiter.api.Assertions.assertFalse(evaluationService.recordChanges(id, java.util.List.of()));
        mvc.perform(get("/api/ideas/" + id).header("Authorization", token))
                .andExpect(jsonPath("$.data.hasNewChange").value(false))
                .andExpect(jsonPath("$.data.lastEvaluatedAt").doesNotExist());

        evaluationService.recordChanges(id, java.util.List.of(
                new com.cue.evaluation.dto.ScoreChange(com.cue.score.entity.ScoreType.MARKET_DEMAND,
                        new java.math.BigDecimal("80"), "수요가 크다",
                        java.util.List.of(new com.cue.evaluation.dto.ReferenceInput(
                                "https://github.com/trending", "GitHub Trending", com.cue.source.entity.ApiProvider.GITHUB))),
                new com.cue.evaluation.dto.ScoreChange(com.cue.score.entity.ScoreType.TECH_FEASIBILITY,
                        new java.math.BigDecimal("40"), "구현 가능", null)));

        // 총점 = (80 + 40) * 0.25 = 30, 변화량 30
        mvc.perform(get("/api/ideas/" + id).header("Authorization", token))
                .andExpect(jsonPath("$.data.totalScore").value(30.0))
                .andExpect(jsonPath("$.data.lastDifference").value(30.0))
                .andExpect(jsonPath("$.data.hasNewChange").value(true));

        mvc.perform(get("/api/ideas/" + id + "/evaluations").header("Authorization", token))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[?(@.scoreType=='MARKET_DEMAND')].difference").value(80.0))
                .andExpect(jsonPath("$.data[?(@.scoreType=='MARKET_DEMAND')].weightPercent").value(25))
                .andExpect(jsonPath("$.data[?(@.scoreType=='MARKET_DEMAND')].references[0].url")
                        .value("https://github.com/trending"));

        mvc.perform(get("/api/ideas/" + id + "/history").header("Authorization", token))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].totalScore").value(30.0))
                .andExpect(jsonPath("$.data[0].totalDifference").value(30.0));

        mvc.perform(post("/api/ideas/" + id + "/check").header("Authorization", token))
                .andExpect(jsonPath("$.data.hasNewChange").value(false));

        // 두 번째 평가: 총점은 35로 오르지만 변화량은 5로 작다
        evaluationService.recordChanges(id, java.util.List.of(
                new com.cue.evaluation.dto.ScoreChange(com.cue.score.entity.ScoreType.MARKET_DEMAND,
                        new java.math.BigDecimal("20"), "수요가 더 커짐", null)));
        mvc.perform(get("/api/ideas/" + id + "/history").header("Authorization", token))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[1].totalScore").value(35.0))
                .andExpect(jsonPath("$.data[1].totalDifference").value(5.0));

        // 최근 상승 정렬은 변화량(25 > 5), 총점 정렬은 총점(35 > 25) 기준이다
        long other = createIdea(token, FULL);
        evaluationService.recordChanges(other, java.util.List.of(
                new com.cue.evaluation.dto.ScoreChange(com.cue.score.entity.ScoreType.MARKET_DEMAND,
                        new java.math.BigDecimal("100"), "아주 큰 수요", null)));
        mvc.perform(get("/api/ideas?sort=RECENT_RISE").header("Authorization", token))
                .andExpect(jsonPath("$.data[0].id").value(other));
        mvc.perform(get("/api/ideas?sort=TOTAL").header("Authorization", token))
                .andExpect(jsonPath("$.data[0].id").value(id));
    }

    @Test
    void 보류_아이디어는_평가할_수_없다() throws Exception {
        String token = signUpAndLogin("holdeval@cue.com");
        long id = createIdea(token, "{\"problem\":\"p\"}");
        org.junit.jupiter.api.Assertions.assertThrows(com.cue.global.error.CustomException.class,
                () -> evaluationService.recordChanges(id, java.util.List.of()));
    }

    @Test
    void 잘못된_정렬_값은_400() throws Exception {
        String token = signUpAndLogin("badsort@cue.com");
        mvc.perform(get("/api/ideas?sort=NOPE").header("Authorization", token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("G004"));
    }

    @Test
    void 이메일은_대소문자를_구분하지_않는다() throws Exception {
        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"kj\",\"email\":\"Case.User@Cue.com\",\"password\":\"password123\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.email").value("case.user@cue.com"));
        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"kj\",\"email\":\"CASE.USER@cue.com\",\"password\":\"password123\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("U001"));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"CASE.user@CUE.com\",\"password\":\"password123\"}"))
                .andExpect(status().isOk());
    }
}
