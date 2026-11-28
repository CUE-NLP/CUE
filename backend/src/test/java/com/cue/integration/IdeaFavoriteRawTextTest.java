package com.cue.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 원문 저장·수정, 즐겨찾기, 목록 정렬·필터. (Docker 필요) */
class IdeaFavoriteRawTextTest extends PostgresIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    private static final String FIELDS = "\"problem\":\"p\",\"target\":\"t\",\"solution\":\"s\",\"expectation\":\"e\"";

    private String login(String email) throws Exception {
        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"kj\",\"email\":\"" + email + "\",\"password\":\"password123\"}"))
                .andExpect(status().isCreated());
        String res = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"password123\"}"))
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + mapper.readTree(res).get("data").get("accessToken").asString();
    }

    private JsonNode create(String token, String body) throws Exception {
        return mapper.readTree(mvc.perform(post("/api/ideas").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("data");
    }

    private JsonNode patchOk(String token, String path, String body) throws Exception {
        return mapper.readTree(mvc.perform(patch(path).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
    }

    private List<Long> listIds(String token, String query) throws Exception {
        JsonNode items = mapper.readTree(mvc.perform(get("/api/ideas" + query).header("Authorization", token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data").get("items");
        List<Long> ids = new ArrayList<>();
        items.forEach(i -> ids.add(i.get("id").asLong()));
        return ids;
    }

    @Test
    void 원문은_저장하고_수정할_수_있고_단건에만_담기며_원문만_바꾸면_평가_상태는_그대로다() throws Exception {
        String token = login("raw@cue.com");
        JsonNode created = create(token, "{" + FIELDS + ",\"rawText\":\"첫 원문\\n둘째 줄\"}");
        long id = created.get("id").asLong();
        assertEquals("첫 원문\n둘째 줄", created.get("rawText").asString());
        jdbc.update("update ideas set needs_full_evaluation = false where id = ?", id);

        JsonNode updated = patchOk(token, "/api/ideas/" + id, "{\"rawText\":\"고친 원문\"}");

        assertEquals("고친 원문", updated.get("rawText").asString());
        assertEquals("p", updated.get("problem").asString()); // 구조화 항목은 자동으로 바꾸지 않는다
        assertFalse(jdbc.queryForObject("select needs_full_evaluation from ideas where id = ?", Boolean.class, id));
        assertEquals("고친 원문", patchOk(token, "/api/ideas/" + id, "{\"title\":\"제목\"}").get("rawText").asString()); // 생략=유지
        mvc.perform(get("/api/ideas").header("Authorization", token))
                .andExpect(jsonPath("$.data.items[0].rawText").doesNotExist());
        assertTrue(patchOk(token, "/api/ideas/" + id, "{\"rawText\":\"\"}").path("rawText").isNull());
    }

    @Test
    void 원문은_10000자까지다() throws Exception {
        String token = login("rawlimit@cue.com");
        create(token, "{" + FIELDS + ",\"rawText\":\"" + "가".repeat(10000) + "\"}");
        mvc.perform(post("/api/ideas").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content("{" + FIELDS + ",\"rawText\":\"" + "가".repeat(10001) + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("G004"));
    }

    @Test
    void 즐겨찾기는_기본_false이고_PATCH로_바꾸며_목록을_거른다() throws Exception {
        String token = login("fav@cue.com");
        JsonNode first = create(token, "{" + FIELDS + "}");
        long a = first.get("id").asLong();
        long b = create(token, "{" + FIELDS + "}").get("id").asLong();
        assertFalse(first.get("favorite").asBoolean());
        jdbc.update("update ideas set evaluation_status = 'RUNNING' where id = ?", a); // 평가 중에도 바꿀 수 있다

        assertTrue(patchOk(token, "/api/ideas/" + a + "/favorite", "{\"favorite\":true}").get("favorite").asBoolean());

        assertEquals(List.of(a), listIds(token, "?favorite=true"));
        assertEquals(List.of(b, a), listIds(token, "?favorite=false&sort=LATEST"));
        patchOk(token, "/api/ideas/" + a + "/favorite", "{\"favorite\":false}");
        assertTrue(listIds(token, "?favorite=true").isEmpty());
        mvc.perform(patch("/api/ideas/" + a + "/favorite").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("G004"));
    }

    @Test
    void 점수순_최신순_변화량순으로_하나씩_정렬한다() throws Exception {
        String token = login("sort@cue.com");
        long a = create(token, "{" + FIELDS + "}").get("id").asLong();
        long b = create(token, "{" + FIELDS + "}").get("id").asLong();
        long c = create(token, "{" + FIELDS + "}").get("id").asLong();
        jdbc.update("update ideas set total_score = 90, last_difference = -5 where id = ?", a);
        jdbc.update("update ideas set total_score = 50, last_difference = 20 where id = ?", b);
        jdbc.update("update ideas set total_score = 70 where id = ?", c);

        assertEquals(List.of(a, c, b), listIds(token, "?sort=TOTAL"));
        assertEquals(List.of(c, b, a), listIds(token, "?sort=LATEST"));
        assertEquals(List.of(b, a, c), listIds(token, "?sort=RECENT_RISE"));
        mvc.perform(get("/api/ideas?sort=LATEST,TOTAL").header("Authorization", token))
                .andExpect(status().isBadRequest());
    }
}
