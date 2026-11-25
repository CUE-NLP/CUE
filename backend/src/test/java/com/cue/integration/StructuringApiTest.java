package com.cue.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cue.global.ai.AiLane;
import com.cue.global.ai.GeminiClient;
import com.cue.global.error.CustomException;
import com.cue.global.error.ErrorCode;
import com.cue.idea.entity.ItCategory;
import com.cue.idea.service.IdeaAnalysis;
import com.cue.idea.service.IdeaAnalyzer;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/** 구조화 API(POST /api/ideas/structure)와 "고치지 않고 저장하면 키워드·종류 재추출 생략" 흐름. Gemini·분석기는 목. (Docker 필요) */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {"spring.docker.compose.enabled=false", "cue.gemini.enabled=false", "cue.evaluation.enabled=false",
        "cue.evaluation.auto-on-save=false", "cue.structuring.cooldown-seconds=3"})
class StructuringApiTest extends DemoTestSupport {
    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @MockitoBean GeminiClient gemini;
    @MockitoBean IdeaAnalyzer analyzer;

    private static final String AI = "{\"title\":\"아이디어 기록 도구\",\"target\":\"개발자\",\"problem\":\"아이디어가 잊힌다\",\"solution\":\"구조화해 저장한다\","
            + "\"expectation\":\"우선순위를 정한다\",\"itCategory\":\"AI_ML\",\"keywords\":[\"idea scoring\",\"startup validation\"]}";

    @BeforeEach
    void reset() throws Exception {
        truncate();
        org.mockito.Mockito.reset(gemini, analyzer);
        when(gemini.available()).thenReturn(true);
        when(gemini.generateJson(eq(AiLane.INTERACTIVE), anyString(), anyString(), anyMap())).thenReturn(mapper.readTree(AI));
        when(analyzer.analyze(any())).thenReturn(new IdeaAnalysis(ItCategory.DEV_TOOLS_INFRA, List.of("fallback one", "fallback two")));
        when(analyzer.analyzeCategory(any())).thenReturn(ItCategory.DEV_TOOLS_INFRA);
    }

    private org.springframework.test.web.servlet.ResultActions structure(String token, String body) throws Exception {
        var req = post("/api/ideas/structure").contentType(MediaType.APPLICATION_JSON).content(body);
        if (token != null) req.header("Authorization", token);
        return mvc.perform(req);
    }

    private static String text(String t) {
        return "{\"text\":\"" + t + "\"}";
    }

    @Test
    void 토큰이_없으면_A006() throws Exception {
        structure(null, text("원문")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("A006"));
    }

    @Test
    void 빈_원문과_너무_긴_원문은_G004() throws Exception {
        String token = signUpAndLogin("v@cue.com");
        structure(token, "{\"text\":\"   \"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("G004"));
        structure(token, "{\"text\":\"" + "가".repeat(5001) + "\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("G004"));
        verify(gemini, never()).generateJson(any(AiLane.class), anyString(), anyString(), anyMap());
    }

    @Test
    void 구조화_결과를_돌려준다() throws Exception {
        String token = signUpAndLogin("s@cue.com");
        structure(token, text("내 아이디어")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.complete").value(true))
                .andExpect(jsonPath("$.data.title").value("아이디어 기록 도구"))
                .andExpect(jsonPath("$.data.target").value("개발자"))
                .andExpect(jsonPath("$.data.missingFields.length()").value(0))
                .andExpect(jsonPath("$.data.itCategory").value("AI_ML"))
                .andExpect(jsonPath("$.data.keywords[0]").value("idea scoring"));
    }

    @Test
    void 연달아_다른_원문을_보내면_I006_429() throws Exception {
        String token = signUpAndLogin("c@cue.com");
        structure(token, text("첫번째")).andExpect(status().isOk());
        structure(token, text("두번째")).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("I006"));
    }

    @Test
    void AI가_바쁘면_E006_503이고_Gemini_불가면_E001() throws Exception {
        String token = signUpAndLogin("b@cue.com");
        when(gemini.generateJson(any(AiLane.class), anyString(), anyString(), anyMap())).thenThrow(new CustomException(ErrorCode.AI_BUSY));
        structure(token, text("원문")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("E006"));

        String other = signUpAndLogin("n@cue.com");
        when(gemini.available()).thenReturn(false);
        structure(other, text("원문")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("E001"));
    }

    @Test
    void 구조화_결과에서_제목만_고쳐_저장하면_키워드와_종류를_다시_추출하지_않는다() throws Exception {
        String token = signUpAndLogin("r@cue.com");
        JsonNode s = mapper.readTree(structure(token, text("원문")).andReturn().getResponse().getContentAsString()).get("data");

        String body = "{\"title\":\"나의 아이디어 노트\",\"target\":\"" + s.get("target").asString() + "\",\"problem\":\"" + s.get("problem").asString()
                + "\",\"solution\":\"" + s.get("solution").asString() + "\",\"expectation\":\"" + s.get("expectation").asString() + "\"}";
        JsonNode idea = idea(token, createIdea(token, body));

        verify(analyzer, never()).analyze(any());
        verify(analyzer, never()).analyzeCategory(any());
        assertEquals("나의 아이디어 노트", idea.get("title").asString());
        assertEquals("AI_ML", idea.get("itCategory").asString());
        assertEquals("idea scoring", idea.get("keywords").get(0).asString());
        assertEquals("AI", idea.get("keywordOrigin").asString());
    }

    @Test
    void 구조화_결과를_고쳐서_저장하면_평소처럼_다시_추출한다() throws Exception {
        String token = signUpAndLogin("e@cue.com");
        structure(token, text("원문")).andExpect(status().isOk());

        String body = "{\"target\":\"개발자\",\"problem\":\"내가 고친 문제\",\"solution\":\"구조화해 저장한다\",\"expectation\":\"우선순위를 정한다\"}";
        JsonNode idea = idea(token, createIdea(token, body));

        verify(analyzer).analyze(any());
        assertEquals("DEV_TOOLS_INFRA", idea.get("itCategory").asString());
        assertEquals("fallback one", idea.get("keywords").get(0).asString());
    }

    @Test
    void 사용자_키워드를_보내도_종류는_캐시를_쓴다() throws Exception {
        String token = signUpAndLogin("k@cue.com");
        structure(token, text("원문")).andExpect(status().isOk());

        String body = "{\"target\":\"개발자\",\"problem\":\"아이디어가 잊힌다\",\"solution\":\"구조화해 저장한다\",\"expectation\":\"우선순위를 정한다\","
                + "\"keywords\":[\"my keyword\"]}";
        JsonNode idea = idea(token, createIdea(token, body));

        verify(analyzer, never()).analyzeCategory(any());
        assertEquals("AI_ML", idea.get("itCategory").asString());
        assertEquals("USER", idea.get("keywordOrigin").asString());
    }
}
