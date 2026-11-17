package com.cue.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cue.evaluation.service.EvaluationBatchService;
import com.cue.evaluation.service.EvaluationScheduler;
import com.cue.global.ai.GeminiClient;
import com.cue.source.dto.SourceCheckResult;
import com.cue.source.dto.SourceItem;
import com.cue.source.entity.ApiProvider;
import com.cue.source.service.SourceCheckService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/** 일일 재평가 파이프라인 통합 테스트. Gemini와 외부 자료 조회만 목으로 바꾸고 DB·서비스·API는 실제로 쓴다. (Docker 필요) */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {"spring.docker.compose.enabled=false", "cue.gemini.enabled=false",
        "cue.evaluation.enabled=false", "cue.evaluation.delay-ms=0"})
class DailyEvaluationFlowTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationContext context;
    @Autowired EvaluationBatchService batch;
    @MockitoBean GeminiClient gemini;
    @MockitoBean SourceCheckService sourceCheck;
    @MockitoBean com.cue.source.service.SourceSummaryService sourceSummary;

    private static final String FULL = "{\"problem\":\"개발 질문이 흩어져 있다\",\"target\":\"주니어 개발자\","
            + "\"solution\":\"질문 정리 도구\",\"expectation\":\"시간 절약\"}";
    private static final String INITIAL = "{\"scores\":["
            + "{\"type\":\"PROBLEM_IMPORTANCE\",\"score\":70,\"evidenceSummary\":\"문제가 크다\"},"
            + "{\"type\":\"MARKET_DEMAND\",\"score\":60,\"evidenceSummary\":\"수요가 있다\"},"
            + "{\"type\":\"MARKET_DIFFERENTIATION\",\"score\":40,\"evidenceSummary\":\"경쟁이 많다\"},"
            + "{\"type\":\"TECH_FEASIBILITY\",\"score\":80,\"evidenceSummary\":\"구현 가능\"}]}";

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE users, trends RESTART IDENTITY CASCADE");
        org.mockito.Mockito.reset(gemini, sourceCheck, sourceSummary);
        when(gemini.available()).thenReturn(true);
        when(sourceCheck.checkAll(anyLong())).thenReturn(List.of());
        when(sourceSummary.summarize(any())).thenAnswer(inv ->
                new com.cue.source.dto.SourceSummary(inv.getArgument(0), "소스 단위 요약"));
        // 프롬프트에 따라 응답 선택: 초기 평가 / 재평가 / 트렌드 요약
        when(gemini.generateJson(anyString(), anyMap())).thenAnswer(inv -> {
            Map<?, ?> props = (Map<?, ?>) ((Map<?, ?>) inv.getArgument(1)).get("properties");
            if (props.containsKey("scores")) return mapper.readTree(INITIAL);
            if (props.containsKey("changes")) return mapper.readTree(
                    "{\"changes\":[{\"type\":\"MARKET_DEMAND\",\"difference\":35,\"evidenceSummary\":\"관심이 급증했다\","
                            + "\"referenceUrls\":[\"https://github.com/a/b\"]},"
                            + "{\"type\":\"TECH_FEASIBILITY\",\"difference\":-3,\"evidenceSummary\":\"성숙도 하락\"}]}");
            return mapper.readTree("{\"summary\":\"에이전트 도구가 늘고 있다\"}");
        });
    }

    private String signUpAndLogin(String email) throws Exception {
        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"kj\",\"email\":\"" + email + "\",\"password\":\"password123\"}"));
        String res = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"password123\"}"))
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + mapper.readTree(res).get("data").get("accessToken").asString();
    }

    private long createIdea(String token, String body) throws Exception {
        String res = mvc.perform(post("/api/ideas").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(res).get("data").get("id").asLong();
    }

    private tools.jackson.databind.JsonNode evaluate(String token, long id) throws Exception {
        return mapper.readTree(mvc.perform(post("/api/ideas/" + id + "/evaluate").header("Authorization", token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private int evaluationCount(long ideaId) {
        return count("select count(*) from evaluations e join scores s on s.id = e.score_id where s.idea_id = ?", ideaId);
    }

    private static SourceCheckResult changed() {
        return SourceCheckResult.changed(1L, ApiProvider.GITHUB, "agent",
                List.of(new SourceItem("a/b", "https://github.com/a/b", "desc")), LocalDateTime.now());
    }

    // ===== 첫 평가 / 변경 없음 =====

    @Test
    void 점수가_없는_ACTIVE_아이디어는_자료_변화가_없어도_초기_평가로_점수를_채우고_이후엔_변경_없음() throws Exception {
        String token = signUpAndLogin("first@cue.com");
        long id = createIdea(token, FULL);

        var first = evaluate(token, id);
        assertEquals("EVALUATED", first.get("status").asString());
        assertTrue(first.get("changed").asBoolean());
        // (70+60+40+80) * 0.25 = 62.5
        assertEquals(62.5, first.get("idea").get("totalScore").asDouble());
        assertTrue(first.get("idea").get("hasNewChange").asBoolean());
        assertEquals(4, evaluationCount(id));                       // 기준마다 0 → 점수로의 변화가 평가 행으로 남는다
        assertEquals(1, count("select count(*) from idea_score_histories where idea_id = ?", id));
        assertFalse(jdbc.queryForObject("select needs_full_evaluation from ideas where id = ?", Boolean.class, id));

        // 두 번째: 자료 변화 없음 → Gemini 추가 호출·평가·날짜 갱신 없음
        var lastAt = first.get("idea").get("lastEvaluatedAt").asString();
        clearInvocations(gemini);
        var second = evaluate(token, id);
        assertEquals("NO_CHANGE", second.get("status").asString());
        assertFalse(second.get("changed").asBoolean());
        assertEquals(lastAt, second.get("idea").get("lastEvaluatedAt").asString());
        verify(gemini, never()).generateJson(anyString(), anyMap());
        assertEquals(4, evaluationCount(id));
        assertEquals(1, count("select count(*) from idea_score_histories where idea_id = ?", id));
    }

    @Test
    void 내용을_수정하면_다음_평가에서_전체_재평가가_다시_일어난다() throws Exception {
        String token = signUpAndLogin("edit@cue.com");
        long id = createIdea(token, FULL);
        evaluate(token, id);
        assertFalse(jdbc.queryForObject("select needs_full_evaluation from ideas where id = ?", Boolean.class, id));

        mvc.perform(patch("/api/ideas/" + id).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"solution\":\"완전히 다른 해결\"}")).andExpect(status().isOk());
        assertTrue(jdbc.queryForObject("select needs_full_evaluation from ideas where id = ?", Boolean.class, id));

        clearInvocations(gemini);
        var again = evaluate(token, id);
        assertEquals("EVALUATED", again.get("status").asString());
        assertFalse(again.get("changed").asBoolean()); // 같은 점수를 돌려줬으므로 점수 변화 없음 → 기록 없음
        verify(gemini, times(1)).generateJson(anyString(), anyMap());
        assertEquals(4, evaluationCount(id));
        assertFalse(jdbc.queryForObject("select needs_full_evaluation from ideas where id = ?", Boolean.class, id));
    }

    // ===== 자료 변화 → 재평가 =====

    @Test
    void 자료가_바뀌면_트렌드를_저장하고_변화량을_10으로_제한해_평가에_연결한다() throws Exception {
        String token = signUpAndLogin("change@cue.com");
        long id = createIdea(token, FULL);
        evaluate(token, id); // 초기 평가: 70/60/40/80

        when(sourceCheck.checkAll(id)).thenReturn(List.of(changed()));
        var result = evaluate(token, id);

        assertEquals("EVALUATED", result.get("status").asString());
        assertTrue(result.get("changed").asBoolean());
        var scores = result.get("idea").get("scores");
        double demand = 0, tech = 0;
        for (var s : scores) {
            if (s.get("type").asString().equals("MARKET_DEMAND")) demand = s.get("score").asDouble();
            if (s.get("type").asString().equals("TECH_FEASIBILITY")) tech = s.get("score").asDouble();
        }
        assertEquals(70.0, demand); // 60 + min(35, 10)
        assertEquals(77.0, tech);   // 80 - 3

        assertEquals(1, count("select count(*) from trends"));
        assertEquals("GITHUB", jdbc.queryForObject("select providers from trends", String.class));
        assertEquals(2, count("select count(*) from evaluations where trend_id is not null"));
        assertEquals(2, count("select count(*) from idea_score_histories where idea_id = ?", id));
        mvc.perform(get("/api/ideas/" + id + "/evaluations").header("Authorization", token))
                .andExpect(jsonPath("$.data[?(@.scoreType=='MARKET_DEMAND' && @.difference==10.0)].references[0].url")
                        .value("https://github.com/a/b"));
    }

    @Test
    void Gemini가_실패하면_해시를_되돌리고_아무것도_기록하지_않으며_전체_평가_플래그는_유지된다() throws Exception {
        String token = signUpAndLogin("fail@cue.com");
        long id = createIdea(token, FULL);
        evaluate(token, id);
        when(sourceCheck.checkAll(id)).thenReturn(List.of(changed()));
        doThrow(new com.cue.global.error.CustomException(com.cue.global.error.ErrorCode.EXTERNAL_SERVER_ERROR))
                .when(gemini).generateJson(anyString(), anyMap());

        mvc.perform(post("/api/ideas/" + id + "/evaluate").header("Authorization", token))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("G002"));

        verify(sourceCheck).restoreHash(eq(1L), any(), any());
        assertEquals(4, evaluationCount(id));
        assertEquals(0, count("select count(*) from trends"));
    }

    // ===== 가중치 예약 =====

    @Test
    void 예약된_가중치는_평가_턴_시작에_적용되고_총점이_바뀌면_이력_스냅샷만_남긴다() throws Exception {
        String token = signUpAndLogin("weight@cue.com");
        long id = createIdea(token, FULL);
        evaluate(token, id); // 총점 62.5, 이력 1건
        String lastAt = mapper.readTree(mvc.perform(get("/api/ideas/" + id).header("Authorization", token))
                .andReturn().getResponse().getContentAsString()).get("data").get("lastEvaluatedAt").asString();

        mvc.perform(patch("/api/ideas/" + id + "/weights").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"weights\":{\"PROBLEM_IMPORTANCE\":100,\"MARKET_DEMAND\":0,"
                                + "\"MARKET_DIFFERENTIATION\":0,\"TECH_FEASIBILITY\":0}}"))
                .andExpect(status().isOk());

        var result = evaluate(token, id); // 자료 변화 없음
        assertEquals("NO_CHANGE", result.get("status").asString());
        assertEquals(70.0, result.get("idea").get("totalScore").asDouble());
        assertEquals(lastAt, result.get("idea").get("lastEvaluatedAt").asString()); // 평가가 아니므로 날짜는 그대로
        assertEquals(4, evaluationCount(id));
        assertEquals(2, count("select count(*) from idea_score_histories where idea_id = ?", id));
        mvc.perform(get("/api/ideas/" + id + "/history").header("Authorization", token))
                .andExpect(jsonPath("$.data[1].totalScore").value(70.0))
                .andExpect(jsonPath("$.data[1].totalDifference").value(7.5));
    }

    // ===== 수동 API 권한·에러 =====

    @Test
    void 수동_평가_API는_인증과_소유자_보류_상태를_검사한다() throws Exception {
        mvc.perform(post("/api/ideas/1/evaluate"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("A006"));

        String owner = signUpAndLogin("owner@cue.com");
        String other = signUpAndLogin("other@cue.com");
        long id = createIdea(owner, FULL);
        long hold = createIdea(owner, "{\"problem\":\"p\",\"target\":\"t\"}");

        mvc.perform(post("/api/ideas/" + id + "/evaluate").header("Authorization", other))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("I002"));
        mvc.perform(post("/api/ideas/999999/evaluate").header("Authorization", owner))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("I001"));
        mvc.perform(post("/api/ideas/" + hold + "/evaluate").header("Authorization", owner))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("I004"));
        verify(gemini, never()).generateJson(anyString(), anyMap());
    }

    @Test
    void Gemini를_쓸_수_없으면_E001이고_아무것도_기록하지_않는다() throws Exception {
        String token = signUpAndLogin("noai@cue.com");
        long id = createIdea(token, FULL);
        when(gemini.available()).thenReturn(false);

        mvc.perform(post("/api/ideas/" + id + "/evaluate").header("Authorization", token))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("E001"));
        assertEquals(0, evaluationCount(id));
        verify(sourceCheck, never()).checkAll(anyLong());
    }

    // ===== 배치 =====

    @Test
    void 배치는_HOLD를_제외하고_한_아이디어가_실패해도_다음을_계속한다() throws Exception {
        String token = signUpAndLogin("batch@cue.com");
        long bad = createIdea(token, FULL);
        long hold = createIdea(token, "{\"problem\":\"p\",\"target\":\"t\"}");
        long good = createIdea(token, FULL);
        when(sourceCheck.checkAll(bad)).thenThrow(new IllegalStateException("boom"));

        EvaluationBatchService.Summary summary = batch.runAll();

        assertEquals(new EvaluationBatchService.Summary(2, 1, 1, 0, 1), summary);
        verify(sourceCheck, never()).checkAll(hold);
        assertEquals(4, evaluationCount(good));
        assertEquals(0, evaluationCount(bad));
        assertEquals(0, evaluationCount(hold));
    }

    @Test
    void 배치는_Gemini를_쓸_수_없으면_아무것도_하지_않는다() throws Exception {
        String token = signUpAndLogin("batchnoai@cue.com");
        long id = createIdea(token, FULL);
        when(gemini.available()).thenReturn(false);

        assertEquals(new EvaluationBatchService.Summary(1, 0, 0, 0, 1), batch.runAll());
        verify(sourceCheck, never()).checkAll(anyLong());
        assertEquals(0, evaluationCount(id));
    }

    @Test
    void 스케줄러는_enabled가_false면_빈으로_등록되지_않는다() {
        assertEquals(0, context.getBeanNamesForType(EvaluationScheduler.class).length);
    }
}
