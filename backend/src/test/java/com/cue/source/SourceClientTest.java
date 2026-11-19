package com.cue.source;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.cue.global.error.CustomException;
import com.cue.global.error.ErrorCode;
import com.cue.source.client.*;
import com.cue.source.dto.SourceItem;
import com.cue.source.fetch.WebContentFetcher;
import java.io.ByteArrayOutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class SourceClientTest {
    /** 2026-06-30 고정, 급상승 기준 30일 → 기준일 2026-05-31. */
    private static final SearchSettings SETTINGS =
            new SearchSettings(5, 30, Clock.fixed(Instant.parse("2026-06-30T12:00:00Z"), ZoneOffset.UTC));

    private static MockRestServiceServer bind(RestClient.Builder b) {
        return MockRestServiceServer.bindTo(b).build();
    }

    private static String decodedQuery(org.springframework.http.client.ClientHttpRequest r) {
        return URLDecoder.decode(r.getURI().getRawQuery(), StandardCharsets.UTF_8);
    }

    // ===== 검색: 급상승 정렬 파라미터 =====

    @Test
    void GitHub은_최근_N일_push된_저장소를_stars_순으로_조회한다() {
        RestClient.Builder b = RestClient.builder().baseUrl("https://gh.test");
        MockRestServiceServer server = bind(b);
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://gh.test/search/repositories")))
                .andExpect(request -> assertTrue(decodedQuery(request).contains("q=ai idea pushed:>2026-05-31"), decodedQuery(request)))
                .andExpect(queryParam("sort", "stars")).andExpect(queryParam("order", "desc"))
                .andExpect(queryParam("per_page", "5"))
                .andRespond(withSuccess("{\"items\":[{\"full_name\":\"a/b\",\"html_url\":\"https://github.com/a/b\",\"description\":null,"
                        + "\"pushed_at\":\"2026-06-20T01:02:03Z\",\"stargazers_count\":1200,\"forks_count\":30}]}", MediaType.APPLICATION_JSON));
        List<SourceItem> items = new GithubSourceClient(b.build(), SETTINGS).search("ai idea");
        assertEquals(List.of(new SourceItem("a/b", "https://github.com/a/b", "", "2026-06-20", "stars=1200, forks=30")), items);
        server.verify();
    }

    @Test
    void HuggingFace는_trendingScore_순으로_조회하고_top_n을_limit으로_쓴다() {
        RestClient.Builder b = RestClient.builder().baseUrl("https://hf.test");
        MockRestServiceServer server = bind(b);
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://hf.test/api/models?search=bert")))
                .andExpect(queryParam("sort", "trendingScore")).andExpect(queryParam("direction", "-1"))
                .andExpect(queryParam("limit", "3"))
                .andRespond(withSuccess("[{\"id\":\"org/bert\",\"pipeline_tag\":\"fill-mask\",\"lastModified\":\"2026-03-04T05:06:07.000Z\",\"downloads\":1200,\"likes\":7}]", MediaType.APPLICATION_JSON));
        List<SourceItem> items = new HuggingFaceSourceClient(b.build(), "https://hf.test", new SearchSettings(3, 30, SETTINGS.clock())).search("bert");
        assertEquals(new SourceItem("org/bert", "https://hf.test/org/bert", "fill-mask", "2026-03-04", "downloads=1200, likes=7"), items.get(0));
        server.verify();
    }

    @Test
    void StackExchange는_최근_N일_질문을_votes_순으로_조회하고_gzip을_푼다() throws Exception {
        RestClient.Builder b = RestClient.builder().baseUrl("https://se.test");
        MockRestServiceServer server = bind(b);
        String json = "{\"items\":[{\"title\":\"What&#39;s up?\",\"link\":\"https://so/questions/1/x\",\"tags\":[\"java\",\"spring\"],\"last_activity_date\":1767225600,\"score\":12,\"answer_count\":3}]}";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(json.getBytes(StandardCharsets.UTF_8));
        }
        server.expect(requestTo(org.hamcrest.Matchers.containsString("key=k1")))
                .andExpect(queryParam("sort", "votes")).andExpect(queryParam("pagesize", "5"))
                .andExpect(queryParam("fromdate", String.valueOf(SETTINGS.cutoffEpochSecond())))
                .andRespond(withSuccess(bos.toByteArray(), MediaType.APPLICATION_JSON));
        List<SourceItem> items = new StackExchangeSourceClient(b.build(), "k1", SETTINGS).search("spring");
        assertEquals(new SourceItem("What's up?", "https://so/questions/1/x", "java,spring", "2026-01-01", "votes=12, answers=3"), items.get(0));
    }

    @Test
    void HackerNews는_최근_N일_스토리만_조회하고_외부_링크가_없으면_토론_페이지_URL을_쓴다() {
        RestClient.Builder b = RestClient.builder().baseUrl("https://hn.test");
        MockRestServiceServer server = bind(b);
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://hn.test/api/v1/search?query=ai%20agent")))
                .andExpect(queryParam("tags", "story"))
                .andExpect(request -> assertTrue(decodedQuery(request).contains("numericFilters=created_at_i>" + SETTINGS.cutoffEpochSecond())))
                .andExpect(queryParam("hitsPerPage", "5"))
                .andRespond(withSuccess("{\"hits\":[{\"title\":\"Show HN: Agent\",\"url\":\"https://a.dev\",\"objectID\":\"1\",\"points\":9,\"num_comments\":4,\"created_at\":\"2026-05-06T07:08:09Z\"},"
                        + "{\"title\":\"Ask HN: Agents?\",\"url\":null,\"objectID\":\"42\"}]}", MediaType.APPLICATION_JSON));
        List<SourceItem> items = new HackerNewsSourceClient(b.build(), SETTINGS, null).search("ai agent");
        assertEquals(List.of(new SourceItem("Show HN: Agent", "https://a.dev", "", "2026-05-06", "points=9, comments=4"),
                new SourceItem("Ask HN: Agents?", "https://news.ycombinator.com/item?id=42", "", "", "")), items);
        server.verify();
    }

    // ===== 원문 읽기 =====

    @Test
    void GitHub_원문은_README_API로_읽는다() {
        RestClient.Builder b = RestClient.builder().baseUrl("https://gh.test");
        MockRestServiceServer server = bind(b);
        server.expect(requestTo("https://gh.test/repos/a/b/readme"))
                .andExpect(header("Accept", "application/vnd.github.raw+json"))
                .andRespond(withSuccess("# Title\nreadme body", MediaType.TEXT_PLAIN));
        assertEquals("# Title\nreadme body", new GithubSourceClient(b.build(), SETTINGS)
                .fetchContent(new SourceItem("a/b", "https://github.com/a/b", "")));
        // 저장소 이름이 아닌 값은 요청 전에 거부
        assertEquals(ErrorCode.EXTERNAL_SERVER_ERROR, assertThrows(CustomException.class, () -> new GithubSourceClient(b.build(), SETTINGS)
                .fetchContent(new SourceItem("../../etc", "u", ""))).getErrorCode());
    }

    @Test
    void HuggingFace_원문은_모델_카드_README이고_YAML_머리말은_뺀다() {
        RestClient.Builder b = RestClient.builder().baseUrl("https://hf.test");
        MockRestServiceServer server = bind(b);
        server.expect(requestTo("https://hf.test/org/bert/raw/main/README.md"))
                .andRespond(withSuccess("---\nlicense: mit\n---\n# BERT\ncard body", MediaType.TEXT_PLAIN));
        assertEquals("# BERT\ncard body", new HuggingFaceSourceClient(b.build(), "https://hf.test", SETTINGS)
                .fetchContent(new SourceItem("org/bert", "https://hf.test/org/bert", "")));
    }

    @Test
    void StackExchange_원문은_질문_본문과_최고_득표_답변_본문이다() {
        RestClient.Builder b = RestClient.builder().baseUrl("https://se.test");
        MockRestServiceServer server = bind(b);
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://se.test/2.3/questions/123?")))
                .andExpect(queryParam("filter", "withbody"))
                .andRespond(withSuccess("{\"items\":[{\"body\":\"<p>How to <b>retry</b>?</p>\"}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://se.test/2.3/questions/123/answers?")))
                .andExpect(queryParam("sort", "votes")).andExpect(queryParam("filter", "withbody"))
                .andRespond(withSuccess("{\"items\":[{\"body\":\"<p>Use <code>@Retryable</code></p>\"}]}", MediaType.APPLICATION_JSON));
        String content = new StackExchangeSourceClient(b.build(), "", SETTINGS)
                .fetchContent(new SourceItem("t", "https://stackoverflow.com/questions/123/how-to-retry", ""));
        assertEquals("Question: How to retry?\n\nTop answer: Use @Retryable", content);
        server.verify();
    }

    @Test
    void HackerNews_원문은_기사_본문_또는_글_텍스트이다() {
        RestClient.Builder b = RestClient.builder().baseUrl("https://hn.test");
        MockRestServiceServer server = bind(b);
        WebContentFetcher web = new WebContentFetcher(uri -> {
            throw new AssertionError("웹 가져오기는 호출되지 않아야 한다");
        }, 3, 1000) {
            @Override public String fetch(String url) { return "ARTICLE:" + url; }
        };
        HackerNewsSourceClient client = new HackerNewsSourceClient(b.build(), SETTINGS, web);
        assertEquals("ARTICLE:https://a.dev/post", client.fetchContent(new SourceItem("t", "https://a.dev/post", "")));

        server.expect(requestTo("https://hn.test/api/v1/items/42"))
                .andRespond(withSuccess("{\"text\":\"<p>Ask me <i>anything</i></p>\"}", MediaType.APPLICATION_JSON));
        assertEquals("Ask me anything", client.fetchContent(
                new SourceItem("Ask HN", "https://news.ycombinator.com/item?id=42", "")));
    }

    // ===== 오류 =====

    @Test
    void 외부_서버_오류는_G002로_변환된다() {
        RestClient.Builder b = RestClient.builder().baseUrl("https://gh.test");
        MockRestServiceServer server = bind(b);
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://gh.test"))).andRespond(withStatus(HttpStatus.FORBIDDEN));
        CustomException e = assertThrows(CustomException.class, () -> new GithubSourceClient(b.build(), SETTINGS).search("x"));
        assertEquals(ErrorCode.EXTERNAL_SERVER_ERROR, e.getErrorCode());
    }

    @Test
    void JSON이_아닌_응답도_G002로_변환된다() {
        RestClient.Builder b = RestClient.builder().baseUrl("https://gh.test");
        MockRestServiceServer server = bind(b);
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://gh.test"))).andRespond(withSuccess("<html>", MediaType.TEXT_HTML));
        CustomException e = assertThrows(CustomException.class, () -> new GithubSourceClient(b.build(), SETTINGS).search("x"));
        assertEquals(ErrorCode.EXTERNAL_SERVER_ERROR, e.getErrorCode());
    }

    @Test
    void HackerNews_서버_오류는_G002로_변환된다() {
        RestClient.Builder b = RestClient.builder().baseUrl("https://hn.test");
        MockRestServiceServer server = bind(b);
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://hn.test"))).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        CustomException e = assertThrows(CustomException.class, () -> new HackerNewsSourceClient(b.build(), SETTINGS, null).search("x"));
        assertEquals(ErrorCode.EXTERNAL_SERVER_ERROR, e.getErrorCode());
    }

    // ===== 글로벌 트렌드: 키워드 없는 급상승 조회 =====

    @Test
    void 트렌드_GitHub은_최근_N일_새로_만든_저장소를_stars_순으로_키워드_없이_조회한다() {
        RestClient.Builder b = RestClient.builder().baseUrl("https://gh.test");
        MockRestServiceServer server = bind(b);
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://gh.test/search/repositories")))
                .andExpect(request -> assertTrue(decodedQuery(request).contains("q=created:>2026-06-23"), decodedQuery(request)))
                .andExpect(queryParam("sort", "stars")).andExpect(queryParam("per_page", "3"))
                .andRespond(withSuccess("{\"items\":[{\"full_name\":\"a/b\",\"html_url\":\"https://github.com/a/b\",\"description\":\"d\","
                        + "\"pushed_at\":\"2026-06-29T01:02:03Z\",\"stargazers_count\":900}]}", MediaType.APPLICATION_JSON));
        List<SourceItem> items = new GithubSourceClient(b.build(), SETTINGS)
                .trending(new SearchSettings(3, 7, SETTINGS.clock()));
        assertEquals("a/b", items.get(0).title());
        server.verify();
    }

    @Test
    void 트렌드_HuggingFace는_검색어_없이_trendingScore_순으로_조회한다() {
        RestClient.Builder b = RestClient.builder().baseUrl("https://hf.test");
        MockRestServiceServer server = bind(b);
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://hf.test/api/models")))
                .andExpect(request -> assertFalse(request.getURI().getRawQuery().contains("search"), request.getURI().toString()))
                .andExpect(queryParam("sort", "trendingScore")).andExpect(queryParam("direction", "-1"))
                .andExpect(queryParam("limit", "3"))
                .andRespond(withSuccess("[{\"id\":\"org/m\",\"pipeline_tag\":\"text-generation\",\"downloads\":5,\"likes\":1}]", MediaType.APPLICATION_JSON));
        List<SourceItem> items = new HuggingFaceSourceClient(b.build(), "https://hf.test", SETTINGS)
                .trending(new SearchSettings(3, 7, SETTINGS.clock()));
        assertEquals("org/m", items.get(0).title());
        server.verify();
    }

    @Test
    void 트렌드_HackerNews는_빈_질의에_최근_N일_필터로_조회한다() {
        RestClient.Builder b = RestClient.builder().baseUrl("https://hn.test");
        MockRestServiceServer server = bind(b);
        // 2026-06-30 − 7일 = 2026-06-23 00:00 UTC = 1782172800
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://hn.test/api/v1/search?query=&")))
                .andExpect(queryParam("tags", "story")).andExpect(queryParam("hitsPerPage", "3"))
                .andExpect(request -> assertTrue(decodedQuery(request).contains("numericFilters=created_at_i>1782172800"), decodedQuery(request)))
                .andRespond(withSuccess("{\"hits\":[{\"title\":\"T\",\"url\":\"https://x.test/a\",\"points\":900,\"num_comments\":50,\"created_at\":\"2026-06-28T00:00:00Z\",\"objectID\":\"1\"}]}", MediaType.APPLICATION_JSON));
        List<SourceItem> items = new HackerNewsSourceClient(b.build(), SETTINGS, new WebContentFetcher(null, 3, 8000))
                .trending(new SearchSettings(3, 7, SETTINGS.clock()));
        assertEquals("https://x.test/a", items.get(0).url());
        server.verify();
    }

    @Test
    void 트렌드를_지원하지_않는_provider는_S002() {
        SourceClient stack = new StackExchangeSourceClient(RestClient.builder().baseUrl("https://se.test").build(), "", SETTINGS);
        assertEquals(ErrorCode.SOURCE_PROVIDER_NOT_SUPPORTED,
                assertThrows(CustomException.class, () -> stack.trending(SETTINGS)).getErrorCode());
    }

    // ===== Semantic Scholar =====

    private static final String PAPERS = "{\"total\":3,\"data\":["
            + "{\"paperId\":\"p1\",\"title\":\"Low cited\",\"abstract\":\"Short abstract.\",\"url\":\"https://www.semanticscholar.org/paper/p1\",\"year\":2026,\"citationCount\":3,\"publicationDate\":\"2026-02-03\"},"
            + "{\"paperId\":\"p2\",\"title\":\"Highly cited\",\"abstract\":\"Long  abstract\\nwith whitespace.\",\"url\":null,\"year\":2025,\"citationCount\":120,\"publicationDate\":null},"
            + "{\"paperId\":\"p3\",\"title\":\"No abstract\",\"abstract\":null,\"url\":\"https://www.semanticscholar.org/paper/p3\",\"year\":2026,\"citationCount\":50,\"publicationDate\":\"2026-01-01\"}]}";

    @Test
    void SemanticScholar는_최근_연도_범위로_검색하고_인용_수_순으로_정리하며_키가_있으면_헤더를_보낸다() {
        RestClient.Builder b = RestClient.builder().baseUrl("https://ss.test");
        MockRestServiceServer server = bind(b);
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://ss.test/graph/v1/paper/search?query=ai%20agent")))
                .andExpect(queryParam("limit", "5")).andExpect(queryParam("year", "2025-")) // 2026 − (2−1)
                .andExpect(queryParam("fields", "title,abstract,url,year,citationCount,publicationDate"))
                .andExpect(header("x-api-key", "secret"))
                .andRespond(withSuccess(PAPERS, MediaType.APPLICATION_JSON));
        List<SourceItem> items = new SemanticScholarSourceClient(b.build(), "secret", SETTINGS, 2).search("ai agent");

        assertEquals(List.of("Highly cited", "No abstract", "Low cited"), items.stream().map(SourceItem::title).toList()); // 인용 수 내림차순
        // url이 없으면 paperId 기반, 발행일이 없으면 연도, 초록 공백 정리, metrics는 인용 수
        assertEquals(new SourceItem("Highly cited", "https://www.semanticscholar.org/paper/p2", "Long abstract with whitespace.", "2025", "citations=120"), items.get(0));
        assertEquals(new SourceItem("Low cited", "https://www.semanticscholar.org/paper/p1", "Short abstract.", "2026-02-03", "citations=3"), items.get(2));
        server.verify();
    }

    @Test
    void SemanticScholar는_키가_없으면_헤더를_생략하고_초록을_원문으로_쓰며_연도_기간은_설정을_따른다() {
        RestClient.Builder b = RestClient.builder().baseUrl("https://ss.test");
        MockRestServiceServer server = bind(b);
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://ss.test/graph/v1/paper/search")))
                .andExpect(headerDoesNotExist("x-api-key")).andExpect(queryParam("year", "2024-")) // 3년
                .andRespond(withSuccess(PAPERS, MediaType.APPLICATION_JSON));
        SemanticScholarSourceClient client = new SemanticScholarSourceClient(b.build(), "", SETTINGS, 3);
        List<SourceItem> items = client.search("x");
        assertEquals("Short abstract.", client.fetchContent(items.get(2)));
        assertEquals("", client.fetchContent(items.get(1))); // 초록이 없는 논문은 원문이 없어 호출한 쪽이 건너뛴다
        server.verify();
    }

    @Test
    void SemanticScholar의_429와_4xx는_G002로_변환된다() {
        for (HttpStatus status : List.of(HttpStatus.TOO_MANY_REQUESTS, HttpStatus.BAD_REQUEST, HttpStatus.INTERNAL_SERVER_ERROR)) {
            RestClient.Builder b = RestClient.builder().baseUrl("https://ss.test");
            bind(b).expect(requestTo(org.hamcrest.Matchers.startsWith("https://ss.test/"))).andRespond(withStatus(status));
            assertEquals(ErrorCode.EXTERNAL_SERVER_ERROR,
                    assertThrows(CustomException.class, () -> new SemanticScholarSourceClient(b.build(), "", SETTINGS, 2).search("x")).getErrorCode(), status.toString());
        }
    }

    // ===== Wikipedia pageviews =====

    private static final String WIKI_SEARCH = "{\"query\":{\"search\":["
            + "{\"title\":\"Foo bar\",\"snippet\":\"A <span class=\\\"searchmatch\\\">foo</span> &quot;bar&quot; thing\",\"timestamp\":\"2026-06-01T10:00:00Z\"},"
            + "{\"title\":\"AC/DC\",\"snippet\":\"Band\",\"timestamp\":\"2026-05-01T00:00:00Z\"}]}}";

    @Test
    void Wikipedia는_검색_결과의_최근_30일_조회수를_합산하고_User_Agent를_보내며_HTML_snippet을_정리한다() {
        RestClient.Builder b = RestClient.builder().baseUrl("https://wiki.test");
        MockRestServiceServer server = bind(b);
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("https://wiki.test/w/api.php?action=query&list=search&srsearch=foo%20bar")))
                .andExpect(queryParam("srlimit", "5")).andExpect(queryParam("format", "json")).andExpect(queryParam("srnamespace", "0"))
                .andExpect(header("User-Agent", "test-ua"))
                .andRespond(withSuccess(WIKI_SEARCH, MediaType.APPLICATION_JSON));
        // 2026-06-30 기준 어제(06-29)까지 30일 = 05-31 ~ 06-29
        server.expect(requestTo("https://wm.test/api/rest_v1/metrics/pageviews/per-article/en.wikipedia/all-access/user/Foo_bar/daily/20260531/20260629"))
                .andExpect(header("User-Agent", "test-ua"))
                .andRespond(withSuccess("{\"items\":[{\"views\":100},{\"views\":250},{\"views\":50}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://wm.test/api/rest_v1/metrics/pageviews/per-article/en.wikipedia/all-access/user/AC%2FDC/daily/20260531/20260629"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)); // 조회수 데이터가 없어도 문서는 남는다

        List<SourceItem> items = new WikipediaPageviewsSourceClient(b.build(), "test-ua", "https://wm.test", SETTINGS, 30).search("foo bar");

        assertEquals(new SourceItem("Foo bar", "https://en.wikipedia.org/wiki/Foo_bar", "A foo \"bar\" thing", "2026-06-01", "views_30d=400"), items.get(0));
        assertEquals(new SourceItem("AC/DC", "https://en.wikipedia.org/wiki/AC%2FDC", "Band", "2026-05-01", ""), items.get(1));
        server.verify();
    }

    @Test
    void Wikipedia_원문은_문서_소개이고_검색_실패는_G002이다() {
        RestClient.Builder b = RestClient.builder().baseUrl("https://wiki.test");
        MockRestServiceServer server = bind(b);
        server.expect(requestTo("https://wiki.test/api/rest_v1/page/summary/Foo_bar")).andExpect(header("User-Agent", "test-ua"))
                .andRespond(withSuccess("{\"title\":\"Foo bar\",\"extract\":\"Foo bar is a placeholder name.\"}", MediaType.APPLICATION_JSON));
        WikipediaPageviewsSourceClient client = new WikipediaPageviewsSourceClient(b.build(), "test-ua", "https://wm.test", SETTINGS, 30);
        assertEquals("Foo bar is a placeholder name.", client.fetchContent(new SourceItem("Foo bar", "https://en.wikipedia.org/wiki/Foo_bar", "", "", "")));
        server.verify();

        for (HttpStatus status : List.of(HttpStatus.TOO_MANY_REQUESTS, HttpStatus.FORBIDDEN)) {
            RestClient.Builder b2 = RestClient.builder().baseUrl("https://wiki.test");
            bind(b2).expect(requestTo(org.hamcrest.Matchers.startsWith("https://wiki.test/"))).andRespond(withStatus(status));
            assertEquals(ErrorCode.EXTERNAL_SERVER_ERROR, assertThrows(CustomException.class,
                    () -> new WikipediaPageviewsSourceClient(b2.build(), "test-ua", "https://wm.test", SETTINGS, 30).search("x")).getErrorCode());
        }
    }
}
