package com.cue.source.client;

import com.cue.source.dto.SourceItem;
import com.cue.source.entity.ApiProvider;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/** Hugging Face 모델 검색 (다운로드 순). 토큰은 선택. */
public class HuggingFaceSourceClient implements SourceClient {
    static final int PAGE_SIZE = 5;
    private final RestClient restClient;
    private final String webBaseUrl;

    public HuggingFaceSourceClient(RestClient restClient, String webBaseUrl) {
        this.restClient = restClient;
        this.webBaseUrl = webBaseUrl;
    }

    @Override
    public ApiProvider provider() {
        return ApiProvider.HUGGING_FACE;
    }

    @Override
    public List<SourceItem> search(String keyword) {
        JsonNode root = SourceClientSupport.getJson(restClient.get().uri(
                uri -> uri.path("/api/models")
                        .queryParam("search", "{q}").queryParam("sort", "downloads").queryParam("direction", -1)
                        .queryParam("limit", PAGE_SIZE).build(keyword)));
        List<SourceItem> items = new ArrayList<>();
        for (JsonNode n : root) {
            String id = SourceClientSupport.text(n, "id");
            items.add(new SourceItem(id, webBaseUrl + "/" + id, SourceClientSupport.text(n, "pipeline_tag")));
        }
        return items;
    }
}
