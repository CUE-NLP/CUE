package com.cue.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cue.global.error.ErrorCode;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** /v3/api-docs 가 열리고, 보안 스키마·주요 경로·에러 응답이 문서에 들어 있으며 실제 인증 정책과 일치하는지 검증한다. */
class OpenApiDocsTest extends PostgresIntegrationTest {

    static final Set<String> PUBLIC_OPERATIONS = Set.of(
            "post /api/users", "post /api/auth/login", "post /api/auth/refresh");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    JsonNode docs;

    @BeforeEach
    void load() throws Exception {
        String body = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        docs = mapper.readTree(body);
    }

    @Test
    void 제목과_bearerAuth_보안_스키마가_있다() {
        assertEquals("CUE API", docs.at("/info/title").asString());
        JsonNode scheme = docs.at("/components/securitySchemes/bearerAuth");
        assertEquals("http", scheme.get("type").asString());
        assertEquals("bearer", scheme.get("scheme").asString());
        assertEquals("JWT", scheme.get("bearerFormat").asString());
    }

    @Test
    void 모든_컨트롤러_경로가_문서에_있다() {
        for (String p : List.of("/api/users", "/api/users/me", "/api/auth/login", "/api/auth/refresh", "/api/auth/logout",
                "/api/ideas", "/api/ideas/{id}", "/api/ideas/{id}/weights", "/api/ideas/{id}/check",
                "/api/ideas/{id}/history", "/api/ideas/{id}/evaluations", "/api/ideas/{id}/evaluate", "/api/demo/evaluations/run")) {
            assertTrue(docs.at("/paths").has(p), p + " 가 문서에 없다");
        }
    }

    @Test
    void 모든_API에_Tag_Operation요약_설명_에러응답이_있다() {
        docs.at("/paths").propertyStream().forEach(path -> path.getValue().propertyStream().forEach(op -> {
            String name = op.getKey() + " " + path.getKey();
            JsonNode o = op.getValue();
            assertFalse(o.path("tags").isEmpty(), name + " Tag 없음");
            assertFalse(o.path("summary").asString().isBlank(), name + " summary 없음");
            assertFalse(o.path("description").asString().isBlank(), name + " description 없음");
            assertTrue(o.at("/responses/500").has("description"), name + " 500 에러 설명 없음");
            // 에러 응답 설명에는 code 와 message 가 함께 나열된다.
            assertTrue(o.at("/responses/500/description").asString().contains(ErrorCode.INTERNAL_SERVER_ERROR.getCode()));
        }));
    }

    @Test
    void 인증_불필요_API만_보안_요구가_비어_있다() {
        docs.at("/paths").propertyStream().forEach(path -> path.getValue().propertyStream().forEach(op -> {
            String key = op.getKey() + " " + path.getKey();
            JsonNode security = op.getValue().get("security");
            boolean open = security != null && security.isArray() && security.isEmpty();
            boolean secured = security == null || security.toString().contains("bearerAuth"); // null이면 전역 요구 상속
            if (PUBLIC_OPERATIONS.contains(key)) {
                assertTrue(open, key + " 는 보안 요구가 비어야 한다");
                assertFalse(op.getValue().at("/responses/401/description").asString().contains("A006"), key + " 에 토큰 없음(A006)이 있으면 안 된다");
            } else {
                assertTrue(secured && !open, key + " 는 bearerAuth 가 필요해야 한다");
                String r401 = op.getValue().at("/responses/401/description").asString();
                for (String c : List.of("A001", "A003", "A004", "A005", "A006")) {
                    assertTrue(r401.contains(c), key + " 401 설명에 " + c + " 없음");
                }
            }
        }));
        assertEquals(1, docs.at("/security").size(), "전역 보안 요구는 bearerAuth 하나");
    }

    @Test
    void 문서의_공개_API가_실제_permitAll과_일치한다() throws Exception {
        // 문서상 공개 API는 토큰 없이 인증 에러(A00x) 없이 검증 단계(G004)까지 가고, 그 외는 A006이어야 한다.
        for (var path : docs.at("/paths").propertyStream().toList()) {
            for (var op : path.getValue().propertyStream().toList()) {
                String key = op.getKey() + " " + path.getKey();
                boolean documentedOpen = op.getValue().get("security") != null && op.getValue().get("security").isEmpty();
                String url = path.getKey().replace("{id}", "1");
                var builder = op.getKey().equals("get") ? get(url)
                        : op.getKey().equals("post") ? post(url).contentType(MediaType.APPLICATION_JSON).content("{}")
                        : op.getKey().equals("delete") ? org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(url)
                        : org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .patch(url).contentType(MediaType.APPLICATION_JSON).content("{}");
                String code = mapper.readTree(mvc.perform(builder).andReturn().getResponse().getContentAsString())
                        .get("code").asString();
                assertEquals(documentedOpen, !code.equals("A006"), key + " 문서의 보안 요구와 SecurityConfig가 다르다 (code=" + code + ")");
            }
        }
    }

    @Test
    void 에러코드가_code와_message로_문서에_나열된다() {
        String r404 = docs.at("/paths/~1api~1ideas~1{id}/get/responses/404/description").asString();
        assertTrue(r404.contains("`I001`") && r404.contains(ErrorCode.IDEA_NOT_FOUND.getMessage()));
        String r403 = docs.at("/paths/~1api~1ideas~1{id}/get/responses/403/description").asString();
        assertTrue(r403.contains("`I002`"));
        String r400 = docs.at("/paths/~1api~1ideas~1{id}~1weights/patch/responses/400/description").asString();
        assertTrue(r400.contains("`I003`") && r400.contains("`G004`") && r400.contains("`G003`"));
        String r401 = docs.at("/paths/~1api~1auth~1refresh/post/responses/401/description").asString();
        for (String c : List.of("A008", "A009", "A010")) {
            assertTrue(r401.contains(c), c);
        }
        assertTrue(docs.at("/paths/~1api~1users/post/responses/409/description").asString().contains("`U001`"));
        // 실패 응답 예시가 CommonResponse 형식으로 들어 있다.
        assertTrue(docs.at("/paths/~1api~1ideas~1{id}/get/responses/404/content/application~1json/examples").has("I001 IDEA_NOT_FOUND"));
    }

    @Test
    void 정렬_상태_파라미터와_가중치_예시가_문서에_있다() {
        JsonNode params = docs.at("/paths/~1api~1ideas/get/parameters");
        boolean status = false, sort = false;
        for (JsonNode p : params) {
            String name = p.get("name").asString();
            if (name.equals("status")) {
                status = true;
                assertTrue(p.get("description").asString().contains("HOLD"));
                assertEquals(List.of("ACTIVE", "HOLD"), enumValues(p.get("schema")));
            }
            if (name.equals("sort")) {
                sort = true;
                assertTrue(p.get("description").asString().contains("RECENT_RISE"));
                assertEquals(List.of("TOTAL", "LATEST", "RECENT_RISE"), enumValues(p.get("schema")));
            }
        }
        assertTrue(status && sort);
        assertTrue(docs.at("/components/schemas/UpdateWeightsRequest").toString().contains("PROBLEM_IMPORTANCE"));
    }

    /** 파라미터 스키마의 enum 값. $ref 로 분리돼 있으면 components 에서 찾는다. */
    private List<String> enumValues(JsonNode schema) {
        JsonNode node = schema.has("$ref")
                ? docs.at("/components/schemas/" + schema.get("$ref").asString().substring(schema.get("$ref").asString().lastIndexOf('/') + 1))
                : schema;
        return node.get("enum").valueStream().map(JsonNode::asString).toList();
    }

    @Test
    void 응답은_CommonResponse_래퍼_구조로_문서화된다() {
        String ref = docs.at("/paths/~1api~1auth~1login/post/responses/200/content/*~1*/schema/$ref").asString();
        JsonNode wrapper = docs.at("/components/schemas/" + ref.substring(ref.lastIndexOf('/') + 1));
        for (String f : List.of("result", "code", "message", "data", "timestamp")) {
            assertTrue(wrapper.at("/properties").has(f), "래퍼에 " + f + " 없음");
        }
    }

    @Test
    void 목록_응답은_CommonResponse_ListData_DTO로_문서화된다() {
        record Case(String path, String dto) {}
        for (Case c : List.of(new Case("/api/ideas", "IdeaResponse"),
                new Case("/api/ideas/{id}/history", "IdeaHistoryResponse"),
                new Case("/api/ideas/{id}/evaluations", "EvaluationResponse"))) {
            String esc = c.path().replace("/", "~1");
            JsonNode schema = docs.at("/paths/" + esc + "/get/responses/200/content/*~1*/schema");
            JsonNode wrapper = resolve(schema);
            assertTrue(wrapper.at("/properties").has("data"), c.path() + " 래퍼에 data 없음");
            JsonNode listData = resolve(wrapper.at("/properties/data"));
            assertEquals("array", listData.at("/properties/items/type").asString(), c.path() + " data.items 는 배열이어야 한다");
            String itemRef = listData.at("/properties/items/items/$ref").asString();
            assertTrue(itemRef.endsWith("/" + c.dto()), c.path() + " items 항목은 " + c.dto() + " 이어야 한다: " + itemRef);
            assertEquals("integer", listData.at("/properties/count/type").asString(), c.path() + " count");
        }
        // 단건 응답은 ListData 가 아니다.
        JsonNode single = resolve(docs.at("/paths/~1api~1ideas~1{id}/get/responses/200/content/*~1*/schema"));
        assertFalse(resolve(single.at("/properties/data")).at("/properties").has("items"));
    }

    private JsonNode resolve(JsonNode node) {
        if (!node.has("$ref")) {
            return node;
        }
        String ref = node.get("$ref").asString();
        return docs.at("/components/schemas/" + ref.substring(ref.lastIndexOf('/') + 1));
    }

    @Test
    void 키워드와_세부_종류_필드가_스키마와_설명에_있다() {
        JsonNode create = docs.at("/components/schemas/CreateIdeaRequest/properties/keywords");
        assertTrue(create.get("type").toString().contains("array")); // nullable이면 ["array","null"]
        assertEquals(2, create.get("maxItems").asInt());
        assertTrue(create.get("description").asString().contains("AI"));
        assertTrue(docs.at("/components/schemas/UpdateIdeaRequest/properties").has("keywords"));

        JsonNode response = docs.at("/components/schemas/IdeaResponse/properties");
        for (String f : List.of("type", "itCategory", "keywords", "keywordOrigin")) {
            assertTrue(response.has(f), "IdeaResponse." + f + " 없음");
        }
        assertEquals(List.of("AI_ML", "DEV_TOOLS_INFRA", "WEB_APP_SERVICE", "DATA_ANALYTICS"), schemaEnum(response.get("itCategory")));
        assertEquals(List.of("USER", "AI"), schemaEnum(response.get("keywordOrigin")));
        assertTrue(response.at("/itCategory").toString().contains("AI_ML=AI"));

        for (String op : List.of("/paths/~1api~1ideas/post", "/paths/~1api~1ideas~1{id}/patch")) {
            assertTrue(docs.at(op + "/description").asString().contains("keywords"), op + " 설명에 keywords 없음");
            assertTrue(docs.at(op + "/responses/400/description").asString().contains("`G004`"), op + " G004 없음");
        }
        assertTrue(docs.at("/paths/~1api~1ideas/post/description").asString().contains("itCategory"));
    }

    @Test
    void 비동기_평가_API는_202와_에러코드_스키마가_문서에_있다() {
        JsonNode evaluate = docs.at("/paths/~1api~1ideas~1{id}~1evaluate/post");
        assertTrue(evaluate.at("/responses").has("202"), "evaluate 는 202 로 문서화돼야 한다");
        assertTrue(evaluate.get("description").asString().contains("202") && evaluate.get("description").asString().contains("폴링"));
        assertTrue(evaluate.at("/responses/404/description").asString().contains("`I001`"));
        assertTrue(evaluate.at("/responses/403/description").asString().contains("`I002`"));
        assertTrue(evaluate.at("/responses/409/description").asString().contains("`I004`"));
        String r503 = evaluate.at("/responses/503/description").asString();
        assertTrue(r503.contains("`E001`") && r503.contains("`E004`"));
        assertEquals(List.of("IDLE", "QUEUED", "RUNNING", "FAILED"), schemaEnum(
                docs.at("/components/schemas/EvaluateIdeaResponse/properties/status")));
        assertTrue(docs.at("/components/schemas/EvaluateIdeaResponse/properties").has("idea"));

        // 평가 중 수정 차단 I005 가 PATCH 에 문서화된다
        JsonNode patch = docs.at("/paths/~1api~1ideas~1{id}/patch");
        assertTrue(patch.at("/responses/409/description").asString().contains("`I005`"));
        assertTrue(patch.get("description").asString().contains("I005"));
    }

    @Test
    void 시연_API와_IdeaResponse_평가_상태_필드가_문서에_있다() {
        JsonNode demo = docs.at("/paths/~1api~1demo~1evaluations~1run/post");
        assertTrue(demo.at("/responses").has("202"));
        assertFalse(demo.get("tags").isEmpty());
        assertTrue(demo.at("/responses/403/description").asString().contains("`D001`"));
        assertTrue(demo.at("/responses/429/description").asString().contains("`D002`"));
        String r503 = demo.at("/responses/503/description").asString();
        assertTrue(r503.contains("`E001`") && r503.contains("`E004`"));
        JsonNode request = docs.at("/components/schemas/DemoRunRequest/properties/force");
        assertFalse(request.isMissingNode());
        JsonNode response = docs.at("/components/schemas/DemoRunResponse/properties");
        for (String f : List.of("queued", "skipped", "trendRefreshed")) {
            assertTrue(response.has(f), "DemoRunResponse." + f + " 없음");
        }
        assertTrue(docs.at("/components/schemas/Skipped/properties").has("reason") || docs.at("/components/schemas").toString().contains("ALREADY_QUEUED"));

        JsonNode idea = docs.at("/components/schemas/IdeaResponse/properties");
        assertTrue(idea.has("evaluationStatus") && idea.has("lastEvaluationErrorCode"));
        assertTrue(idea.at("/evaluationStatus").toString().contains("EvaluationStatus") || idea.at("/evaluationStatus").has("description"));
        assertEquals(List.of("IDLE", "QUEUED", "RUNNING", "FAILED"), schemaEnum(idea.get("evaluationStatus")));
    }

    @Test
    void 가중치_요청_예시는_5개_기준이다() {
        String schema = docs.at("/components/schemas/UpdateWeightsRequest").toString();
        assertTrue(schema.contains("TREND"));
        assertEquals(List.of("PROBLEM_IMPORTANCE", "MARKET_DEMAND", "MARKET_DIFFERENTIATION", "TECH_FEASIBILITY", "TREND"),
                schemaEnum(docs.at("/components/schemas/ScoreResponse/properties/type")));
        assertTrue(docs.at("/paths/~1api~1ideas~1{id}~1weights/patch/description").asString().contains("TREND"));
    }

    /** 프로퍼티 스키마의 enum 값. $ref(또는 allOf)로 분리돼 있으면 components 에서 찾는다. */
    private List<String> schemaEnum(JsonNode prop) {
        JsonNode ref = prop.has("$ref") ? prop.get("$ref") : prop.path("allOf").path(0).get("$ref");
        JsonNode node = ref != null
                ? docs.at("/components/schemas/" + ref.asString().substring(ref.asString().lastIndexOf('/') + 1))
                : prop;
        return node.get("enum").valueStream().map(JsonNode::asString).toList();
    }
}
