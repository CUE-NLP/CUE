package com.cue.source;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.cue.global.error.CustomException;
import com.cue.global.error.ErrorCode;
import com.cue.source.client.*;
import com.cue.source.dto.SourceItem;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class SourceClientTest {

    private static MockRestServiceServer bind(RestClient.Builder b) {
        return MockRestServiceServer.bindTo(b).build();
    }

    @Test
    void GitHub_검색_결과를_제목_URL_요약으로_변환한다() {
        RestClient.Builder b = RestClient.builder().baseUrl("https://gh.test");
        MockRestServiceServer server = bind(b);
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://gh.test/search/repositories?q=ai%20idea")))
                .andExpect(queryParam("per_page", "5")).andExpect(queryParam("sort", "updated"))
                .andRespond(withSuccess("{\"items\":[{\"full_name\":\"a/b\",\"html_url\":\"https://github.com/a/b\",\"description\":null}]}",
                        MediaType.APPLICATION_JSON));
        List<SourceItem> items = new GithubSourceClient(b.build()).search("ai idea");
        assertEquals(List.of(new SourceItem("a/b", "https://github.com/a/b", "", "", "")), items);
        server.verify();
    }

    @Test
    void HuggingFace_모델_URL을_만든다() {
        RestClient.Builder b = RestClient.builder().baseUrl("https://hf.test");
        MockRestServiceServer server = bind(b);
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://hf.test/api/models?search=bert")))
                .andExpect(queryParam("sort", "lastModified"))
                .andRespond(withSuccess("[{\"id\":\"org/bert\",\"pipeline_tag\":\"fill-mask\",\"lastModified\":\"2026-03-04T05:06:07.000Z\",\"downloads\":1200,\"likes\":7}]", MediaType.APPLICATION_JSON));
        List<SourceItem> items = new HuggingFaceSourceClient(b.build(), "https://hf.test").search("bert");
        assertEquals(new SourceItem("org/bert", "https://hf.test/org/bert", "fill-mask", "2026-03-04", "downloads=1200, likes=7"), items.get(0));
    }

    @Test
    void StackExchange_gzip_응답을_풀고_제목_HTML을_복원한다() throws Exception {
        RestClient.Builder b = RestClient.builder().baseUrl("https://se.test");
        MockRestServiceServer server = bind(b);
        String json = "{\"items\":[{\"title\":\"What&#39;s up?\",\"link\":\"https://so/q/1\",\"tags\":[\"java\",\"spring\"],\"last_activity_date\":1767225600,\"score\":12,\"answer_count\":3}]}";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(json.getBytes(StandardCharsets.UTF_8));
        }
        server.expect(requestTo(org.hamcrest.Matchers.containsString("key=k1")))
                .andRespond(withSuccess(bos.toByteArray(), MediaType.APPLICATION_JSON));
        List<SourceItem> items = new StackExchangeSourceClient(b.build(), "k1").search("spring");
        assertEquals(new SourceItem("What's up?", "https://so/q/1", "java,spring", "2026-01-01", "votes=12, answers=3"), items.get(0));
    }

    @Test
    void HackerNews_스토리를_변환하고_외부_링크가_없으면_토론_페이지_URL을_쓴다() {
        RestClient.Builder b = RestClient.builder().baseUrl("https://hn.test");
        MockRestServiceServer server = bind(b);
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://hn.test/api/v1/search_by_date?query=ai%20agent")))
                .andExpect(queryParam("tags", "story"))
                .andExpect(queryParam("hitsPerPage", "5"))
                .andRespond(withSuccess("{\"hits\":[{\"title\":\"Show HN: Agent\",\"url\":\"https://a.dev\",\"objectID\":\"1\",\"points\":9,\"num_comments\":4,\"created_at\":\"2026-05-06T07:08:09Z\"},"
                        + "{\"title\":\"Ask HN: Agents?\",\"url\":null,\"objectID\":\"42\"}]}", MediaType.APPLICATION_JSON));
        List<SourceItem> items = new HackerNewsSourceClient(b.build()).search("ai agent");
        assertEquals(List.of(new SourceItem("Show HN: Agent", "https://a.dev", "", "2026-05-06", "points=9, comments=4"),
                new SourceItem("Ask HN: Agents?", "https://news.ycombinator.com/item?id=42", "", "", "")), items);
        server.verify();
    }

    @Test
    void HackerNews_서버_오류는_G002로_변환된다() {
        RestClient.Builder b = RestClient.builder().baseUrl("https://hn.test");
        MockRestServiceServer server = bind(b);
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://hn.test"))).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        CustomException e = assertThrows(CustomException.class, () -> new HackerNewsSourceClient(b.build()).search("x"));
        assertEquals(ErrorCode.EXTERNAL_SERVER_ERROR, e.getErrorCode());
    }

    @Test
    void 외부_서버_오류는_G002로_변환된다() {
        RestClient.Builder b = RestClient.builder().baseUrl("https://gh.test");
        MockRestServiceServer server = bind(b);
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://gh.test"))).andRespond(withStatus(HttpStatus.FORBIDDEN));
        CustomException e = assertThrows(CustomException.class, () -> new GithubSourceClient(b.build()).search("x"));
        assertEquals(ErrorCode.EXTERNAL_SERVER_ERROR, e.getErrorCode());
    }

    @Test
    void JSON이_아닌_응답도_G002로_변환된다() {
        RestClient.Builder b = RestClient.builder().baseUrl("https://gh.test");
        MockRestServiceServer server = bind(b);
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://gh.test"))).andRespond(withSuccess("<html>", MediaType.TEXT_HTML));
        CustomException e = assertThrows(CustomException.class, () -> new GithubSourceClient(b.build()).search("x"));
        assertEquals(ErrorCode.EXTERNAL_SERVER_ERROR, e.getErrorCode());
    }
}
