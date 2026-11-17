package com.cue.source.client;

import com.cue.source.dto.SourceItem;
import com.cue.source.entity.ApiProvider;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.HtmlUtils;
import tools.jackson.databind.JsonNode;

/** Stack Overflow 질문 검색 (최근 활동 순). 키(key)는 선택이며 있으면 일일 쿼터가 늘어난다. */
public class StackExchangeSourceClient implements SourceClient {
    static final int PAGE_SIZE = 5;
    private final RestClient restClient;
    private final String apiKey;

    public StackExchangeSourceClient(RestClient restClient, String apiKey) {
        this.restClient = restClient;
        this.apiKey = apiKey;
    }

    @Override
    public ApiProvider provider() {
        return ApiProvider.STACK_EXCHANGE;
    }

    @Override
    public List<SourceItem> search(String keyword) {
        JsonNode root = SourceClientSupport.getJson(restClient.get().uri(uri -> {
            uri.path("/2.3/search/advanced")
                    .queryParam("q", "{q}").queryParam("site", "stackoverflow")
                    .queryParam("sort", "activity").queryParam("order", "desc")
                    .queryParam("pagesize", PAGE_SIZE);
            if (apiKey != null && !apiKey.isBlank()) {
                uri.queryParam("key", apiKey);
            }
            return uri.build(keyword);
        }));
        List<SourceItem> items = new ArrayList<>();
        for (JsonNode n : root.path("items")) {
            List<String> tags = new ArrayList<>();
            n.path("tags").forEach(t -> tags.add(t.asString()));
            items.add(new SourceItem(
                    HtmlUtils.htmlUnescape(SourceClientSupport.text(n, "title")),
                    SourceClientSupport.text(n, "link"),
                    String.join(",", tags),
                    SourceClientSupport.epochDate(n, "last_activity_date"),
                    SourceClientSupport.metrics(n, "votes", "score", "answers", "answer_count")));
        }
        return items;
    }
}
