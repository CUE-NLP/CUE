package com.cue.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
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
import tools.jackson.databind.ObjectMapper;

/** DELETE /api/ideas/{id}: 연결 데이터까지 지우고 다른 아이디어·트렌드는 남긴다. (Docker 필요) */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {"spring.docker.compose.enabled=false", "cue.gemini.enabled=false",
        "cue.evaluation.enabled=false", "cue.evaluation.auto-on-save=false"})
class IdeaDeleteTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    private static final String FULL = "{\"problem\":\"개발 질문이 흩어져 있다\",\"target\":\"주니어 개발자\","
            + "\"solution\":\"질문 정리 도구\",\"expectation\":\"시간 절약\"}";

    private String token;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.execute("TRUNCATE users, trends RESTART IDENTITY CASCADE");
        token = signUpAndLogin("delete@cue.com");
    }

    private String signUpAndLogin(String email) throws Exception {
        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"kj\",\"email\":\"" + email + "\",\"password\":\"password123\"}"));
        String res = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"password123\"}"))
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + mapper.readTree(res).get("data").get("accessToken").asString();
    }

    private long createIdea(String body) throws Exception {
        String res = mvc.perform(post("/api/ideas").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(res).get("data").get("id").asLong();
    }

    /** 평가가 끝난 아이디어처럼 평가·근거·총점 이력·트렌드 연결을 넣는다. */
    private void addEvaluationData(long ideaId, long trendId) {
        Long evaluationId = jdbc.queryForObject("insert into evaluations (score_id, trend_id, evidence_summary, difference, weight, score_after) "
                + "select id, ?, '근거', 10, 0.2, 10 from scores where idea_id = ? and type = 'TREND' returning id",
                Long.class, trendId, ideaId);
        jdbc.update("insert into evaluation_references (evaluation_id, url) values (?, 'https://example.test/a')", evaluationId);
        jdbc.update("insert into idea_score_histories (idea_id, total_score, total_difference, evaluated_at) values (?, 2, 2, now())", ideaId);
        jdbc.update("update sources set evaluation_id = ? where idea_id = ?", evaluationId, ideaId);
        jdbc.update("update ideas set last_trend_id = ? where id = ?", trendId, ideaId);
    }

    /** 아이디어에 딸린 행 수: 아이디어, 점수, 평가, 근거, 총점 이력, source. */
    private int rows(long ideaId) {
        return jdbc.queryForObject("select (select count(*) from ideas where id = ?)"
                + " + (select count(*) from scores where idea_id = ?)"
                + " + (select count(*) from evaluations e join scores s on s.id = e.score_id where s.idea_id = ?)"
                + " + (select count(*) from evaluation_references r join evaluations e on e.id = r.evaluation_id"
                + "    join scores s on s.id = e.score_id where s.idea_id = ?)"
                + " + (select count(*) from idea_score_histories where idea_id = ?)"
                + " + (select count(*) from sources where idea_id = ?)",
                Integer.class, ideaId, ideaId, ideaId, ideaId, ideaId, ideaId);
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    @Test
    void 삭제하면_연결_데이터가_모두_지워지고_다른_아이디어와_트렌드는_남는다() throws Exception {
        long id = createIdea(FULL);
        long other = createIdea(FULL);
        Long trendId = jdbc.queryForObject(
                "insert into trends (summary, providers, content_hash) values ('요약', 'HACKER_NEWS', 'h') returning id", Long.class);
        addEvaluationData(id, trendId);
        addEvaluationData(other, trendId);
        int otherRows = rows(other);

        mvc.perform(delete("/api/ideas/" + id).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("SUCCESS"))
                .andExpect(jsonPath("$.data").doesNotExist());

        assertEquals(0, rows(id));
        assertEquals(otherRows, rows(other));
        assertEquals(1, count("evaluations"));
        assertEquals(1, count("evaluation_references"));
        assertEquals(1, count("trends"));
        mvc.perform(get("/api/ideas/" + id).header("Authorization", token)).andExpect(jsonPath("$.code").value("I001"));
        mvc.perform(delete("/api/ideas/" + id).header("Authorization", token))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("I001"));
    }

    @Test
    void 평가_대기_진행_중이면_I005이고_아무것도_지우지_않는다() throws Exception {
        long id = createIdea(FULL);
        int before = rows(id);

        for (String running : new String[] {"QUEUED", "RUNNING"}) {
            jdbc.update("update ideas set evaluation_status = ? where id = ?", running, id);
            mvc.perform(delete("/api/ideas/" + id).header("Authorization", token))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("I005"));
            assertEquals(before, rows(id));
        }

        jdbc.update("update ideas set evaluation_status = 'FAILED' where id = ?", id);
        mvc.perform(delete("/api/ideas/" + id).header("Authorization", token)).andExpect(status().isOk());
        assertEquals(0, rows(id));
    }

    @Test
    void 보류_아이디어도_삭제할_수_있고_남의_아이디어는_I002로_남는다() throws Exception {
        long hold = createIdea("{\"problem\":\"아직 정리 중\"}");
        long mine = createIdea(FULL);
        String other = signUpAndLogin("other@cue.com");

        mvc.perform(delete("/api/ideas/" + mine).header("Authorization", other))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("I002"));
        mvc.perform(delete("/api/ideas/" + hold).header("Authorization", token)).andExpect(status().isOk());

        assertEquals(0, rows(hold));
        assertEquals(1, count("ideas"));
    }
}
