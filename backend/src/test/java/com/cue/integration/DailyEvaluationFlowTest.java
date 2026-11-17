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
import com.cue.trend.dto.TrendSnapshot;
import com.cue.trend.service.GlobalTrendService;
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
    @MockitoBean GlobalTrendService globalTrend;

    private static final String FULL = "{\"problem\":\"개발 질문이 흩어져 있다\",\"target\":\"주니어 개발자\","
            + "\"solution\":\"질문 정리 도구\",\"expectation\":\"시간 절약\"}";
    private static final String INITIAL = "{\"scores\":["
            + "{\"type\":\"PROBLEM_IMPORTANCE\",\"score\":70,\"evidenceSummary\":\"문제가 크다\"},"
            + "{\"type\":\"MARKET_DEMAND\",\"score\":60,\"evidenceSummary\":\"수요가 있다\"},"
            + "{\"type\":\"MARKET_DIFFERENTIATION\",\"score\":40,\"evidenceSummary\":\"경쟁이 많다\"},"
            + "{\"type\":\"TECH_FEASIBILITY\",\"score\":80,\"evidenceSummary\":\"구현 가능\"}]}";

    /** TREND 평가 응답 (첫 평가는 score, 재평가는 difference를 읽는다). */
    private static final String TREND_REPLY = "{\"score\":65,\"difference\":35,\"evidenceSummary\":\"에이전트 흐름과 맞닿아 있다\","
            + "\"referenceUrls\":[\"https://news.ycombinator.com/item?id=1\",\"https://not-in-trend.test/x\"]}";
    private String trendReply = TREND_REPLY;

    /** trends에 실제 행을 넣고 그 행을 가리키는 스냅샷을 만든다 (evaluations.trend_id FK 때문에 실제 행이 필요하다). */
    private TrendSnapshot newTrend(String summary) {
        Long id = jdbc.queryForObject("insert into trends (summary, providers, content_hash) values (?, 'HACKER_NEWS', ?) returning id",
                Long.class, summary, "hash-" + System.nanoTime());
        return new TrendSnapshot(id, summary, LocalDateTime.now(), List.of(SourceCheckResult.changed(null, ApiProvider.HACKER_NEWS, "",
                List.of(new SourceItem("Agents everywhere", "https://news.ycombinator.com/item?id=1", "항목 요약", "2026-06-01", "points=900")),
                LocalDateTime.now())));
    }

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE users, trends RESTART IDENTITY CASCADE");
        org.mockito.Mockito.reset(gemini, sourceCheck, sourceSummary, globalTrend);
        trendReply = TREND_REPLY;
        when(globalTrend.today()).thenReturn(newTrend("에이전트 도구가 뜬다"));
        when(gemini.available()).thenReturn(true);
        when(sourceCheck.checkAll(anyLong())).thenReturn(List.of());
        when(sourceSummary.summarize(any())).thenAnswer(inv ->
                new com.cue.source.dto.SourceSummary(inv.getArgument(0), "소스 단위 요약"));
        // 프롬프트에 따라 응답 선택: 초기 평가 / 재평가 / 트렌드 요약
        when(gemini.generateJson(anyString(), anyMap())).thenAnswer(inv -> {
            Map<?, ?> props = (Map<?, ?>) ((Map<?, ?>) inv.getArgument(1)).get("properties");
            if (props.containsKey("scores")) return mapper.readTree(INITIAL);
            if (props.containsKey("difference")) return mapper.readTree(trendReply);
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
        // (70+60+40+80+65) * 0.2 = 63 — 4개 기준(키워드 근거) + TREND 첫 평가
        assertEquals(63.0, first.get("idea").get("totalScore").asDouble());
        assertTrue(first.get("idea").get("hasNewChange").asBoolean());
        assertEquals(5, evaluationCount(id));                       // 기준마다 0 → 점수로의 변화가 평가 행으로 남는다
        assertEquals(1, count("select count(*) from idea_score_histories where idea_id = ?", id)); // 하루 평가는 이력 1행
        assertFalse(jdbc.queryForObject("select needs_full_evaluation from ideas where id = ?", Boolean.class, id));
        // TREND 평가만 그날 트렌드 행에 연결되고, 근거 URL은 트렌드 항목에 있는 것만 남는다
        Long trendId = jdbc.queryForObject("select id from trends", Long.class);
        assertEquals(trendId, jdbc.queryForObject("select last_trend_id from ideas where id = ?", Long.class, id));
        assertEquals(1, count("select count(*) from evaluations where trend_id is not null"));
        assertEquals(trendId, jdbc.queryForObject("select e.trend_id from evaluations e join scores s on s.id = e.score_id "
                + "where s.type = 'TREND' and s.idea_id = ?", Long.class, id));
        assertEquals(List.of("https://news.ycombinator.com/item?id=1"), jdbc.queryForList(
                "select r.url from evaluation_references r join evaluations e on e.id = r.evaluation_id "
                        + "join scores s on s.id = e.score_id where s.type = 'TREND'", String.class));

        // 두 번째: 자료 변화 없음 → Gemini 추가 호출·평가·날짜 갱신 없음
        var lastAt = first.get("idea").get("lastEvaluatedAt").asString();
        clearInvocations(gemini);
        var second = evaluate(token, id);
        assertEquals("NO_CHANGE", second.get("status").asString());
        assertFalse(second.get("changed").asBoolean());
        assertEquals(lastAt, second.get("idea").get("lastEvaluatedAt").asString());
        verify(gemini, never()).generateJson(anyString(), anyMap());
        assertEquals(5, evaluationCount(id));
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

        assertNull(jdbc.queryForObject("select last_trend_id from ideas where id = ?", Long.class, id)); // TREND도 첫 평가부터 다시

        clearInvocations(gemini);
        var again = evaluate(token, id);
        assertEquals("EVALUATED", again.get("status").asString());
        assertFalse(again.get("changed").asBoolean()); // 같은 점수를 돌려줬으므로 점수 변화 없음 → 기록 없음
        verify(gemini, times(2)).generateJson(anyString(), anyMap()); // 4개 기준 초기 평가 1 + TREND 초기 평가 1
        assertNotNull(jdbc.queryForObject("select last_trend_id from ideas where id = ?", Long.class, id));
        assertEquals(5, evaluationCount(id));
        assertFalse(jdbc.queryForObject("select needs_full_evaluation from ideas where id = ?", Boolean.class, id));
    }

    // ===== 자료 변화 → 재평가 =====

    @Test
    void 근거_자료가_바뀌면_4개_기준만_재평가하고_변화량을_10으로_제한하며_트렌드와는_연결하지_않는다() throws Exception {
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

        assertEquals(1, count("select count(*) from evaluations where trend_id is not null")); // 첫 평가의 TREND 1건뿐, 키워드 근거 평가는 연결 없음
        assertEquals(2, count("select count(*) from idea_score_histories where idea_id = ?", id));
        verify(gemini, times(3)).generateJson(anyString(), anyMap()); // 첫 평가(A·B) 2회 + 재평가는 A만 1회 (소스 요약은 목)
        
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
        assertEquals(5, evaluationCount(id));
    }

    // ===== 글로벌 트렌드(TREND) 평가 =====

    private double trendScore(tools.jackson.databind.JsonNode idea) {
        for (var s : idea.get("scores")) {
            if (s.get("type").asString().equals("TREND")) return s.get("score").asDouble();
        }
        throw new AssertionError("TREND 점수 없음");
    }

    @Test
    void 새_트렌드가_오면_TREND만_재평가하고_변화량을_10으로_제한해_새_트렌드에_연결한다() throws Exception {
        String token = signUpAndLogin("trend@cue.com");
        long id = createIdea(token, FULL);
        evaluate(token, id); // TREND 65

        TrendSnapshot next = newTrend("새로운 흐름");
        when(globalTrend.today()).thenReturn(next);
        clearInvocations(gemini);
        var result = evaluate(token, id);

        assertEquals("EVALUATED", result.get("status").asString());
        assertTrue(result.get("changed").asBoolean());
        assertEquals(75.0, trendScore(result.get("idea"))); // 65 + min(35, 10)
        verify(gemini, times(1)).generateJson(anyString(), anyMap()); // 4개 기준(A)은 호출하지 않는다
        assertEquals(6, evaluationCount(id));
        assertEquals(next.id(), jdbc.queryForObject("select last_trend_id from ideas where id = ?", Long.class, id));
        assertEquals(next.id(), jdbc.queryForObject("select e.trend_id from evaluations e join scores s on s.id = e.score_id "
                + "where s.type = 'TREND' and s.idea_id = ? order by e.id desc limit 1", Long.class, id));
        assertEquals(2, count("select count(*) from idea_score_histories where idea_id = ?", id));

        // 같은 트렌드로는 다시 호출하지 않는다
        clearInvocations(gemini);
        assertEquals("NO_CHANGE", evaluate(token, id).get("status").asString());
        verify(gemini, never()).generateJson(anyString(), anyMap());
    }

    @Test
    void TREND_평가가_실패해도_키워드_근거_평가는_저장되고_다음_실행에서_TREND만_다시_한다() throws Exception {
        String token = signUpAndLogin("trendfail@cue.com");
        long id = createIdea(token, FULL);
        doAnswer(inv -> {
            Map<?, ?> props = (Map<?, ?>) ((Map<?, ?>) inv.getArgument(1)).get("properties");
            if (props.containsKey("difference")) throw new com.cue.global.error.CustomException(com.cue.global.error.ErrorCode.EXTERNAL_SERVER_ERROR);
            return mapper.readTree(INITIAL);
        }).when(gemini).generateJson(anyString(), anyMap());

        mvc.perform(post("/api/ideas/" + id + "/evaluate").header("Authorization", token))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("G002"));

        assertEquals(4, evaluationCount(id)); // 4개 기준은 저장됨
        assertFalse(jdbc.queryForObject("select needs_full_evaluation from ideas where id = ?", Boolean.class, id));
        assertNull(jdbc.queryForObject("select last_trend_id from ideas where id = ?", Long.class, id)); // TREND는 아직

        doAnswer(inv -> mapper.readTree(trendReply)).when(gemini).generateJson(anyString(), anyMap());
        clearInvocations(gemini);
        var retry = evaluate(token, id);
        verify(gemini, times(1)).generateJson(anyString(), anyMap()); // A는 끝났으니 TREND 호출만
        assertEquals(65.0, trendScore(retry.get("idea")));
        assertEquals(5, evaluationCount(id));
    }

    @Test
    void 키워드_근거_평가가_실패해도_TREND_평가는_저장되고_실패한_쪽은_다음에_다시_한다() throws Exception {
        String token = signUpAndLogin("evidencefail@cue.com");
        long id = createIdea(token, FULL);
        doAnswer(inv -> {
            Map<?, ?> props = (Map<?, ?>) ((Map<?, ?>) inv.getArgument(1)).get("properties");
            if (props.containsKey("scores")) throw new com.cue.global.error.CustomException(com.cue.global.error.ErrorCode.EXTERNAL_SERVER_ERROR);
            return mapper.readTree(trendReply);
        }).when(gemini).generateJson(anyString(), anyMap());

        mvc.perform(post("/api/ideas/" + id + "/evaluate").header("Authorization", token))
                .andExpect(status().isBadGateway());

        assertEquals(1, evaluationCount(id)); // TREND만 저장됨
        assertTrue(jdbc.queryForObject("select needs_full_evaluation from ideas where id = ?", Boolean.class, id)); // A는 재시도 대상
        assertNotNull(jdbc.queryForObject("select last_trend_id from ideas where id = ?", Long.class, id));
    }

    @Test
    void 글로벌_트렌드를_만들지_못한_날은_TREND_평가를_건너뛰고_키워드_근거만_평가한다() throws Exception {
        String token = signUpAndLogin("notrend@cue.com");
        long id = createIdea(token, FULL);
        when(globalTrend.today()).thenReturn(null);

        var result = evaluate(token, id);
        assertEquals("EVALUATED", result.get("status").asString());
        assertEquals(4, evaluationCount(id));
        assertNull(jdbc.queryForObject("select last_trend_id from ideas where id = ?", Long.class, id));

        // 트렌드도 없고 키워드 자료 변화도 없으면 아무것도 하지 않는다
        clearInvocations(gemini);
        assertEquals("NO_CHANGE", evaluate(token, id).get("status").asString());
        verify(gemini, never()).generateJson(anyString(), anyMap());
    }

    // ===== 가중치 예약 =====

    @Test
    void 예약된_가중치는_평가_턴_시작에_적용되고_총점이_바뀌면_이력_스냅샷만_남긴다() throws Exception {
        String token = signUpAndLogin("weight@cue.com");
        long id = createIdea(token, FULL);
        evaluate(token, id); // 총점 63, 이력 1건
        String lastAt = mapper.readTree(mvc.perform(get("/api/ideas/" + id).header("Authorization", token))
                .andReturn().getResponse().getContentAsString()).get("data").get("lastEvaluatedAt").asString();

        mvc.perform(patch("/api/ideas/" + id + "/weights").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"weights\":{\"PROBLEM_IMPORTANCE\":100,\"MARKET_DEMAND\":0,"
                                + "\"MARKET_DIFFERENTIATION\":0,\"TECH_FEASIBILITY\":0,\"TREND\":0}}"))
                .andExpect(status().isOk());

        var result = evaluate(token, id); // 자료 변화 없음
        assertEquals("NO_CHANGE", result.get("status").asString());
        assertEquals(70.0, result.get("idea").get("totalScore").asDouble());
        assertEquals(lastAt, result.get("idea").get("lastEvaluatedAt").asString()); // 평가가 아니므로 날짜는 그대로
        assertEquals(5, evaluationCount(id));
        assertEquals(2, count("select count(*) from idea_score_histories where idea_id = ?", id));
        mvc.perform(get("/api/ideas/" + id + "/history").header("Authorization", token))
                .andExpect(jsonPath("$.data[1].totalScore").value(70.0))
                .andExpect(jsonPath("$.data[1].totalDifference").value(7.0));
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
        assertEquals(5, evaluationCount(good));
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
