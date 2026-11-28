package com.cue.integration;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cue.global.ai.GeminiClient;
import com.cue.source.service.SourceCheckService;
import com.cue.trend.service.GlobalTrendService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/** 하루 제한을 끄면 아이디어별 대기 시간(E008)만 적용한다. (Docker 필요) */
@TestPropertySource(properties = {"cue.evaluation.daily-limit-enabled=false", "cue.evaluation.manual-cooldown-seconds=600"})
class EvaluationCooldownTest extends PostgresIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean GeminiClient gemini;
    @MockitoBean SourceCheckService sourceCheck;
    @MockitoBean GlobalTrendService globalTrend;

    @Test
    void 대기_시간_안의_재요청은_E008이고_지나거나_수정하면_받는다() throws Exception {
        when(gemini.available()).thenReturn(true);
        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"kj\",\"email\":\"cool@cue.com\",\"password\":\"password123\"}"));
        String login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"cool@cue.com\",\"password\":\"password123\"}"))
                .andReturn().getResponse().getContentAsString();
        String token = "Bearer " + mapper.readTree(login).get("data").get("accessToken").asString();
        String created = mvc.perform(post("/api/ideas").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"problem\":\"p\",\"target\":\"t\",\"solution\":\"s\",\"expectation\":\"e\",\"keywords\":[\"kw\"]}"))
                .andReturn().getResponse().getContentAsString();
        long id = mapper.readTree(created).get("data").get("id").asLong();
        // 시각은 JVM 기준으로 넣는다 (테스트 DB는 시간대가 다를 수 있다)
        jdbc.update("update ideas set needs_full_evaluation = false, evaluation_completed_at = ? where id = ?",
                java.time.LocalDateTime.now(), id);

        mvc.perform(post("/api/ideas/" + id + "/evaluate").header("Authorization", token))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("E008"));

        // 대기 시간이 지나면 같은 날에도 받는다
        jdbc.update("update ideas set evaluation_completed_at = ? where id = ?",
                java.time.LocalDateTime.now().minusMinutes(11), id);
        mvc.perform(post("/api/ideas/" + id + "/evaluate").header("Authorization", token)).andExpect(status().isAccepted());
    }
}
