package com.cue.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cue.evaluation.service.IdeaEvaluationPipeline;
import com.cue.global.ai.GeminiClient;
import com.cue.global.error.CustomException;
import com.cue.global.error.ErrorCode;
import com.cue.source.client.SourceClient;
import com.cue.source.dto.SourceItem;
import com.cue.source.entity.ApiProvider;
import com.cue.source.repository.SourceRepository;
import com.cue.source.service.SourceCheckService;
import com.cue.source.service.SourceExcerptService;
import com.cue.trend.dto.TrendSnapshot;
import com.cue.trend.service.GlobalTrendService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 첫 평가에 외부 자료가 들어가는지 검증하는 회귀 테스트. 다른 통합 테스트는 {@code SourceCheckService}를 목으로 바꿔서
 * "첫 조회를 변경 없음으로 처리해 항목을 버리는" 버그(첫 평가의 근거 문서가 0개)를 잡지 못했다. 여기서는 <b>실제 SourceCheckService</b>를 쓰고
 * 외부 API(SourceClient)와 Gemini만 가짜로 바꾼다. (Docker 필요)
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {"spring.docker.compose.enabled=false", "cue.gemini.enabled=false",
        "cue.evaluation.enabled=false", "cue.evaluation.delay-ms=0", "cue.evaluation.auto-on-save=false"})
class FirstEvaluationEvidenceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    /** GitHub 검색 결과를 테스트에서 바꾸기 위한 값. 비어 있으면 기본 항목 1개. */
    private static final List<String> githubNames = new CopyOnWriteArrayList<>();

    /** provider마다 항목을 돌려주는 가짜 외부 API로 실제 SourceCheckService를 만든다. */
    @TestConfiguration
    static class FakeSources {
        @Bean
        @Primary
        SourceCheckService fakeSourceCheckService(SourceRepository sources) {
            List<SourceClient> clients = new ArrayList<>();
            for (ApiProvider provider : ApiProvider.values()) {
                clients.add(new SourceClient() {
                    public ApiProvider provider() { return provider; }
                    public List<SourceItem> search(String keyword) {
                        if (provider == ApiProvider.GITHUB && !githubNames.isEmpty()) {
                            return githubNames.stream().map(n -> new SourceItem("저장소 " + n, "https://fake.test/repo_" + n, "설명 " + n))
                                    .toList();
                        }
                        return List.of(new SourceItem("자료 " + provider, "https://fake.test/" + provider, "설명 " + provider));
                    }
                });
            }
            return new SourceCheckService(sources, clients);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired IdeaEvaluationPipeline pipeline;
    @MockitoBean GeminiClient gemini;
    @MockitoBean GlobalTrendService globalTrend;
    @MockitoBean SourceExcerptService sourceExcerpt;

    private final List<String> prompts = new CopyOnWriteArrayList<>();
    private volatile boolean reEvaluationFails;
    private String token;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE users, trends RESTART IDENTITY CASCADE");
        prompts.clear();
        githubNames.clear();
        reEvaluationFails = false;
        when(gemini.available()).thenReturn(true);
        Long trendId = jdbc.queryForObject(
                "insert into trends (summary, providers, content_hash) values ('요약', 'HACKER_NEWS', 'h') returning id", Long.class);
        when(globalTrend.today()).thenReturn(new TrendSnapshot(trendId, "요약", LocalDateTime.now(), List.of()));
        when(sourceExcerpt.excerpt(any())).thenAnswer(inv -> inv.getArgument(0));
        // 초기 평가: 프롬프트에 들어온 첫 자료 URL을 근거로 인용한다 (입력에 없던 URL은 서버가 버린다)
        when(gemini.generateJson(anyString(), anyMap())).thenAnswer(inv -> {
            String prompt = inv.getArgument(0);
            Map<String, Object> schema = inv.getArgument(1);
            if (mapper.valueToTree(schema).at("/properties/score").isObject()) { // TREND: prompts에는 근거 평가만 담는다
                return mapper.readTree("{\"score\":50,\"evidenceSummary\":\"흐름과 맞닿아 있다\",\"referenceUrls\":[]}");
            }
            if (mapper.valueToTree(schema).at("/properties/changes").isObject()) { // 재평가
                prompts.add(prompt);
                if (reEvaluationFails) {
                    throw new CustomException(ErrorCode.EXTERNAL_SERVER_ERROR);
                }
                return mapper.readTree("{\"changes\":[{\"type\":\"MARKET_DEMAND\",\"score\":64,"
                        + "\"evidenceSummary\":\"새 저장소가 나타났어요.\",\"referenceUrls\":[\"https://fake.test/repo_f\"]}]}");
            }
            JsonNode required = mapper.valueToTree(schema).at("/properties/scores/items/required");
            assertTrue(required.valueStream().anyMatch(field -> field.asString().equals("referenceUrls")),
                    "Gemini 초기 평가 응답에 referenceUrls 배열을 요구해야 한다");
            prompts.add(prompt);
            Matcher m = Pattern.compile("https://fake\\.test/\\w+").matcher(prompt);
            String cited = m.find() ? m.group() : "";
            return mapper.readTree("{\"scores\":["
                    + "{\"type\":\"PROBLEM_IMPORTANCE\",\"score\":70,\"evidenceSummary\":\"문제가 크다\","
                    + "\"referenceUrls\":[\"" + cited + "\",\"https://not-in-input.test\"]},"
                    + "{\"type\":\"MARKET_DEMAND\",\"score\":60,\"evidenceSummary\":\"수요가 있다\",\"referenceUrls\":[]},"
                    + "{\"type\":\"MARKET_DIFFERENTIATION\",\"score\":40,\"evidenceSummary\":\"경쟁이 많다\",\"referenceUrls\":[]},"
                    + "{\"type\":\"TECH_FEASIBILITY\",\"score\":80,\"evidenceSummary\":\"구현 가능\",\"referenceUrls\":[]}]}");
        });
    }

    private long createIdea() throws Exception {
        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"kj\",\"email\":\"first@cue.com\",\"password\":\"password123\"}"));
        String login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"first@cue.com\",\"password\":\"password123\"}"))
                .andReturn().getResponse().getContentAsString();
        token = "Bearer " + mapper.readTree(login).get("data").get("accessToken").asString();
        String res = mvc.perform(post("/api/ideas").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"problem\":\"개발 질문이 흩어져 있다\",\"target\":\"주니어 개발자\",\"solution\":\"질문 정리 도구\",\"expectation\":\"시간 절약\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(res).get("data").get("id").asLong();
    }

    @Test
    void 첫_평가_프롬프트에_외부_자료가_들어가고_근거_문서가_평가에_연결된다() throws Exception {
        long id = createIdea();
        assertTrue(jdbc.queryForObject("select count(*) from sources where idea_id = ?", Integer.class, id) > 0);

        pipeline.evaluate(id);

        String evaluationPrompt = prompts.stream().filter(p -> p.contains("[자료 목록]")).findFirst().orElseThrow();
        assertFalse(evaluationPrompt.contains("(수집된 자료 없음)"), "첫 평가에 외부 자료가 하나도 들어가지 않았다");
        assertTrue(evaluationPrompt.contains("https://fake.test/"), "조회한 자료의 URL이 프롬프트에 없다");

        List<String> references = jdbc.queryForList("select r.url from evaluation_references r join evaluations e on e.id = r.evaluation_id "
                + "join scores s on s.id = e.score_id where s.idea_id = ?", String.class, id);
        Matcher cited = Pattern.compile("https://fake\\.test/\\w+").matcher(evaluationPrompt);
        assertTrue(cited.find());
        assertEquals(List.of(cited.group()), references);

        // 해시도 기록돼 두 번째 조회부터는 변경 없음이다
        assertEquals(0, jdbc.queryForObject("select count(*) from sources where idea_id = ? and content_hash is null", Integer.class, id));
        prompts.clear();
        nextDay();
        assertEquals("NO_CHANGE", pipeline.evaluate(id).status().name());
        assertTrue(prompts.isEmpty());
    }

    @Test
    void 수동_재평가는_평가_내역과_추가_삭제_그대로인_항목을_나눠_보내고_성공해야_반영_기록을_바꾼다() throws Exception {
        githubNames.addAll(List.of("a", "b", "c", "d", "e"));
        long id = createIdea();
        evaluateManually(id); // 첫(전체) 평가: 자료 전부
        assertEquals(1, prompts.size());
        for (String name : githubNames) {
            assertTrue(prompts.get(0).contains("https://fake.test/repo_" + name));
        }
        String recorded = recordedItems(id);
        assertTrue(recorded.contains("repo_d") && recorded.contains("repo_e"));

        // 같은 날 다시 요청하면 거절한다
        mvc.perform(post("/api/ideas/" + id + "/evaluate").header("Authorization", token))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("E007"));

        // 다음 날, 상위 5개 중 2개 교체. AI가 실패하면 반영 기록은 그대로다
        nextDay();
        githubNames.clear();
        githubNames.addAll(List.of("a", "b", "c", "f", "g"));
        reEvaluationFails = true;
        prompts.clear();
        evaluateManually(id);
        assertEquals("FAILED", evaluationStatus(id));
        assertEquals(recorded, recordedItems(id));

        // 실패한 날은 다시 요청할 수 있고, 같은 추가·삭제가 평가된다
        reEvaluationFails = false;
        prompts.clear();
        evaluateManually(id);
        assertEquals("IDLE", evaluationStatus(id));
        assertEquals(1, prompts.size());
        String prompt = prompts.get(0);
        String history = prompt.substring(prompt.lastIndexOf("[기존 평가 내역]"), prompt.lastIndexOf("[새로 추가된 자료]"));
        String added = prompt.substring(prompt.lastIndexOf("[새로 추가된 자료]"), prompt.lastIndexOf("[그대로인 자료]"));
        String unchanged = prompt.substring(prompt.lastIndexOf("[그대로인 자료]"), prompt.lastIndexOf("[삭제된 자료]"));
        String removed = prompt.substring(prompt.lastIndexOf("[삭제된 자료]"));
        assertTrue(history.contains("문제가 크다"), "첫 평가의 근거 요약이 재평가에 들어가야 한다");
        assertTrue(added.contains("repo_f") && added.contains("repo_g"));
        assertTrue(removed.contains("repo_d") && removed.contains("repo_e"));
        for (String kept : List.of("repo_a", "repo_b", "repo_c")) {
            assertFalse(added.contains(kept) || removed.contains(kept), kept);
            assertTrue(unchanged.contains(kept), kept);
        }
        assertTrue(recordedItems(id).contains("repo_g") && !recordedItems(id).contains("repo_d"));
        assertEquals(1, jdbc.queryForObject("select count(*) from evaluation_references where url = 'https://fake.test/repo_f'",
                Integer.class));

        // 그다음 날 바뀐 것이 없어도 사용자가 요청하면 다시 평가한다
        nextDay();
        prompts.clear();
        evaluateManually(id);
        assertEquals("IDLE", evaluationStatus(id));
        assertEquals(1, prompts.size());
        assertTrue(prompts.get(0).contains("(새로 추가된 자료 없음)"));
    }

    @Test
    void 오늘_평가했어도_내용을_수정하면_같은_날_전체_평가를_다시_요청할_수_있다() throws Exception {
        long id = createIdea();
        evaluateManually(id);
        mvc.perform(post("/api/ideas/" + id + "/evaluate").header("Authorization", token))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("E007"));

        mvc.perform(patch("/api/ideas/" + id).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"solution\":\"완전히 다른 해결\"}")).andExpect(status().isOk());
        prompts.clear();
        evaluateManually(id);

        assertEquals(1, prompts.size());
        assertTrue(prompts.get(0).contains("[자료 목록]") && prompts.get(0).contains("완전히 다른 해결"));
        mvc.perform(post("/api/ideas/" + id + "/evaluate").header("Authorization", token))
                .andExpect(status().isConflict());
    }

    private void nextDay() {
        jdbc.update("update ideas set evaluated_on = evaluated_on - 1");
    }

    /** POST /api/ideas/{id}/evaluate를 호출하고 비동기 평가가 끝날 때까지 기다린다 (최대 10초). */
    private void evaluateManually(long id) throws Exception {
        mvc.perform(post("/api/ideas/" + id + "/evaluate").header("Authorization", token)).andExpect(status().isAccepted());
        long deadline = System.currentTimeMillis() + 10_000;
        while (List.of("QUEUED", "RUNNING").contains(evaluationStatus(id))) {
            assertTrue(System.currentTimeMillis() < deadline, "평가가 끝나지 않음");
            Thread.sleep(25);
        }
    }

    private String evaluationStatus(long id) {
        return jdbc.queryForObject("select evaluation_status from ideas where id = ?", String.class, id);
    }

    private String recordedItems(long id) {
        return jdbc.queryForObject("select string_agg(evaluated_items, ',' order by id) from sources "
                + "where idea_id = ? and provider = 'GITHUB'", String.class, id);
    }
}
