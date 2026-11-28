package com.cue.source.client;

import com.cue.global.error.CustomException;
import com.cue.global.error.ExternalFailure;
import com.cue.source.dto.SourceItem;
import com.cue.source.entity.ApiProvider;
import com.cue.source.fetch.HtmlText;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriUtils;
import tools.jackson.databind.JsonNode;

/**
 * 영어 위키백과 문서 검색 + 최근 N일 조회수. 키워드로 {@code list=search}를 호출해 상위 N개 문서를 받고, 문서마다 Wikimedia REST pageviews API
 * ({@code per-article/en.wikipedia/all-access/user/{title}/daily/{start}/{end}})의 일별 조회수를 최근 {@code cue.source.pageviews-days}일(기본 30)
 * 합산해 metrics({@code views_30d=N})에 담는다. 조회수 호출이 실패하거나 데이터가 없으면 그 문서의 metrics만 비우고 계속한다.
 * 조회수·요약은 해시에 넣지 않는다(제목·URL만). 요약은 검색 snippet(HTML 태그 제거), 원문은 문서 소개({@code /api/rest_v1/page/summary/{title}}의 extract).
 * Wikimedia 정책상 User-Agent 헤더가 필수라 모든 요청에 보낸다 (개인 이메일을 넣지 않는 일반 식별 문자열).
 */
@Slf4j
public class WikipediaPageviewsSourceClient implements SourceClient {
    static final String ARTICLE_URL = "https://en.wikipedia.org/wiki/";
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;
    private final RestClient restClient;
    private final String userAgent;
    private final String pageviewsBaseUrl;
    private final SearchSettings settings;
    private final int pageviewsDays;

    public WikipediaPageviewsSourceClient(RestClient restClient, String userAgent, String pageviewsBaseUrl,
                                          SearchSettings settings, int pageviewsDays) {
        this.restClient = restClient;
        this.userAgent = userAgent;
        this.pageviewsBaseUrl = pageviewsBaseUrl;
        this.settings = settings;
        this.pageviewsDays = Math.max(1, pageviewsDays);
    }

    @Override
    public ApiProvider provider() {
        return ApiProvider.WIKIPEDIA_PAGEVIEWS;
    }

    @Override
    public List<SourceItem> search(String keyword) {
        JsonNode root = SourceClientSupport.getJson(restClient.get().uri(uri -> uri.path("/w/api.php")
                        .queryParam("action", "query").queryParam("list", "search")
                        .queryParam("srsearch", "{q}").queryParam("srnamespace", 0)
                        .queryParam("srlimit", settings.topN()).queryParam("format", "json")
                        .build(keyword))
                .header("User-Agent", userAgent));
        List<SourceItem> items = new ArrayList<>();
        for (JsonNode n : root.path("query").path("search")) {
            String title = SourceClientSupport.text(n, "title");
            if (title.isBlank()) {
                continue;
            }
            String snippet = HtmlText.toText(SourceClientSupport.text(n, "snippet"));
            long views = views30d(title);
            items.add(new SourceItem(title, ARTICLE_URL + UriUtils.encodePathSegment(title.replace(' ', '_'), java.nio.charset.StandardCharsets.UTF_8),
                    snippet, SourceClientSupport.date(n, "timestamp"), views < 0 ? "" : "views_" + pageviewsDays + "d=" + views));
        }
        return items;
    }

    /** 최근 N일(어제까지) 일별 조회수 합. 호출 실패·데이터 없음은 -1. */
    private long views30d(String title) {
        LocalDate end = LocalDate.now(settings.clock()).minusDays(1); // 오늘 치는 집계 전이라 어제까지
        LocalDate start = end.minusDays(pageviewsDays - 1L);
        try {
            JsonNode root = SourceClientSupport.getJson(restClient.get()
                    .uri(pageviewsBaseUrl + "/api/rest_v1/metrics/pageviews/per-article/en.wikipedia/all-access/user/{title}/daily/{start}/{end}",
                            title.replace(' ', '_'), DAY.format(start), DAY.format(end))
                    .header("User-Agent", userAgent));
            long sum = 0;
            boolean any = false;
            for (JsonNode d : root.path("items")) {
                if (d.path("views").isNumber()) {
                    sum += d.path("views").asLong();
                    any = true;
                }
            }
            return any ? sum : -1;
        } catch (CustomException e) {
            log.warn("조회수 읽기 실패, 지표 없이 씁니다 API=Wikipedia title={} 원인={}", title, ExternalFailure.reason(e));
            return -1;
        }
    }

    /** 문서 소개(extract). */
    @Override
    public String fetchContent(SourceItem item) {
        JsonNode root = SourceClientSupport.getJson(restClient.get()
                .uri("/api/rest_v1/page/summary/{title}", item.title().replace(' ', '_'))
                .header("User-Agent", userAgent));
        return SourceClientSupport.text(root, "extract");
    }
}
