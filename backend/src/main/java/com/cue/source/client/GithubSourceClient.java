package com.cue.source.client;

import com.cue.source.dto.SourceItem;
import com.cue.source.entity.ApiProvider;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/** GitHub 저장소 검색 (stars 순). 토큰은 선택이지만 없으면 rate limit이 매우 낮다. */
public class GithubSourceClient implements SourceClient {
    static final int PAGE_SIZE = 5;
    private final RestClient restClient;

    public GithubSourceClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public ApiProvider provider() {
        return ApiProvider.GITHUB;
    }

    @Override
    public List<SourceItem> search(String keyword) {
        JsonNode root = SourceClientSupport.getJson(restClient.get().uri(
                uri -> uri.path("/search/repositories")
                        .queryParam("q", "{q}").queryParam("sort", "stars").queryParam("order", "desc")
                        .queryParam("per_page", PAGE_SIZE).build(keyword)));
        List<SourceItem> items = new ArrayList<>();
        for (JsonNode n : root.path("items")) {
            items.add(new SourceItem(
                    SourceClientSupport.text(n, "full_name"),
                    SourceClientSupport.text(n, "html_url"),
                    SourceClientSupport.text(n, "description")));
        }
        return items;
    }
}
