package com.cue.source.client;

import com.cue.source.dto.SourceItem;
import com.cue.source.entity.ApiProvider;
import com.cue.source.fetch.HtmlText;
import com.cue.source.fetch.WebContentFetcher;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * Hacker News 스토리 검색 (Algolia, 키 불필요). 키워드 근거 조회는 <b>기간 제한 없이</b> 관련도 뒤에 points·댓글 수를 반영하는 순위({@code /search})로 본다.
 * 글로벌 트렌드(키워드 없음)만 최근 N일 안의 스토리({@code numericFilters=created_at_i>…})로 제한한다.
 * 원문은 연결된 기사 URL 본문(안전장치가 있는 {@link WebContentFetcher}), 외부 링크가 없는 글(Ask HN 등)은 글 텍스트.
 * 점수(points)·댓글 수는 자주 변하므로 해시에는 넣지 않고 metrics로만 전달한다.
 */
public class HackerNewsSourceClient implements SourceClient {
    static final String ITEM_URL = "https://news.ycombinator.com/item?id=";
    private final RestClient restClient;
    private final SearchSettings settings;
    private final WebContentFetcher webFetcher;

    public HackerNewsSourceClient(RestClient restClient, SearchSettings settings, WebContentFetcher webFetcher) {
        this.restClient = restClient;
        this.settings = settings;
        this.webFetcher = webFetcher;
    }

    @Override
    public ApiProvider provider() {
        return ApiProvider.HACKER_NEWS;
    }

    @Override
    public List<SourceItem> search(String keyword) {
        return query(keyword, settings, false);
    }

    /** 글로벌 트렌드: 키워드 없이 최근 N일 스토리를 points 순으로 (빈 질의 + 날짜 필터는 인기순으로 정렬된다). */
    @Override
    public List<SourceItem> trending(SearchSettings s) {
        return query("", s, true);
    }

    /** @param recentOnly true면 최근 N일 안의 글만(트렌드). 키워드 근거 조회는 기간을 두지 않고 관련도·points 순으로 본다. */
    private List<SourceItem> query(String keyword, SearchSettings settings, boolean recentOnly) {
        JsonNode root = SourceClientSupport.getJson(restClient.get().uri(
                uri -> {
                    var b = uri.path("/api/v1/search").queryParam("query", "{q}").queryParam("tags", "story");
                    if (recentOnly) {
                        b.queryParam("numericFilters", "created_at_i>" + settings.cutoffEpochSecond());
                    }
                    return b.queryParam("hitsPerPage", settings.topN()).build(keyword);
                }));
        List<SourceItem> items = new ArrayList<>();
        for (JsonNode n : root.path("hits")) {
            String url = SourceClientSupport.text(n, "url");
            if (url.isBlank()) { // Ask HN 등 외부 링크가 없는 글은 HN 토론 페이지로 대체한다.
                url = ITEM_URL + SourceClientSupport.text(n, "objectID");
            }
            items.add(new SourceItem(SourceClientSupport.text(n, "title"), url, "",
                    SourceClientSupport.date(n, "created_at"),
                    SourceClientSupport.metrics(n, "points", "points", "comments", "num_comments")));
        }
        return items;
    }

    @Override
    public String fetchContent(SourceItem item) {
        if (item.url().startsWith(ITEM_URL)) {
            String id = item.url().substring(ITEM_URL.length());
            if (!id.matches("\\d+")) {
                throw SourceClientSupport.failure("글 번호 형식이 아님");
            }
            JsonNode story = SourceClientSupport.getJson(restClient.get().uri("/api/v1/items/" + id));
            return HtmlText.toText(SourceClientSupport.text(story, "text"));
        }
        return webFetcher.fetch(item.url());
    }
}
