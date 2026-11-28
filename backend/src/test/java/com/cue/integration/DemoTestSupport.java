package com.cue.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 시연 API 통합 테스트들의 공통 도우미 (컨테이너·애노테이션은 각 테스트 클래스가 선언한다). */
abstract class DemoTestSupport {
    static final String FULL = "{\"problem\":\"개발 질문이 흩어져 있다\",\"target\":\"주니어 개발자\","
            + "\"solution\":\"질문 정리 도구\",\"expectation\":\"시간 절약\"}";
    static final String HOLD = "{\"problem\":\"p\",\"target\":\"t\"}";
    static final String INITIAL = """
            {"scores":{
              "PROBLEM_IMPORTANCE":{"score":70,"evidenceSummary":"문제가 크다","referenceIds":[]},
              "MARKET_DEMAND":{"score":0,"evidenceSummary":"자료 부족","referenceIds":[]},
              "MARKET_DIFFERENTIATION":{"score":0,"evidenceSummary":"자료 부족","referenceIds":[]},
              "TECH_FEASIBILITY":{"score":0,"evidenceSummary":"자료 부족","referenceIds":[]}
            }}
            """;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    /** 사용자 id가 테스트마다 달라지도록 RESTART IDENTITY 없이 비운다 (시연 쿨다운이 메모리에 사용자 id로 남기 때문). */
    void truncate() {
        jdbc.execute("TRUNCATE users, trends CASCADE");
    }

    String signUpAndLogin(String email) throws Exception {
        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"kj\",\"email\":\"" + email + "\",\"password\":\"password123\"}"));
        String res = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"password123\"}"))
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + mapper.readTree(res).get("data").get("accessToken").asString();
    }

    long createIdea(String token, String body) throws Exception {
        return mapper.readTree(mvc.perform(post("/api/ideas").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("data").get("id").asLong();
    }

    JsonNode idea(String token, long id) throws Exception {
        return mapper.readTree(mvc.perform(get("/api/ideas/" + id).header("Authorization", token))
                .andReturn().getResponse().getContentAsString()).get("data");
    }

    void awaitStatus(String token, long id, String expected) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (expected.equals(idea(token, id).get("evaluationStatus").asString())) return;
            Thread.sleep(25);
        }
        fail("상태가 " + expected + "가 되지 않음: " + idea(token, id).get("evaluationStatus"));
    }

    /** 시연 실행을 호출한다. body가 null이면 본문 없이. */
    JsonNode run(String token, String body, int expectedStatus) throws Exception {
        var req = post("/api/demo/evaluations/run").header("Authorization", token);
        if (body != null) req = req.contentType(MediaType.APPLICATION_JSON).content(body);
        return mapper.readTree(mvc.perform(req).andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString());
    }

    void awaitNoActive() throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline
                && jdbc.queryForObject("select count(*) from ideas where evaluation_status in ('QUEUED','RUNNING')", Integer.class) > 0) {
            Thread.sleep(25);
        }
    }
}
