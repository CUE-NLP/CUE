package com.cue.source.client;

import com.cue.source.dto.SourceItem;
import com.cue.source.entity.ApiProvider;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * Hacker News 스토리 검색 (Algolia, 관련도 순). 키가 필요 없다.
 * 점수(points)·댓글 수는 자주 변해 해시가 흔들리므로 요약에 넣지 않는다.
 */
public class HackerNewsSourceClient implements SourceClient {
    static final int PAGE_SIZE = 5;
    static final String ITEM_URL = "https://news.ycombinator.com/item?id=";
    private final RestClient restClient;

    public HackerNewsSourceClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public ApiProvider provider() {
        return ApiProvider.HACKER_NEWS;
    }

    @Override
    public List<SourceItem> search(String keyword) {
        JsonNode root = SourceClientSupport.getJson(restClient.get().uri(
                uri -> uri.path("/api/v1/search")
                        .queryParam("query", "{q}").queryParam("tags", "story")
                        .queryParam("hitsPerPage", PAGE_SIZE).build(keyword)));
        List<SourceItem> items = new ArrayList<>();
        for (JsonNode n : root.path("hits")) {
            String url = SourceClientSupport.text(n, "url");
            if (url.isBlank()) { // Ask HN 등 외부 링크가 없는 글은 HN 토론 페이지로 대체한다.
                url = ITEM_URL + SourceClientSupport.text(n, "objectID");
            }
            items.add(new SourceItem(SourceClientSupport.text(n, "title"), url, ""));
        }
        return items;
    }
}
