package com.cue.source.client;

import com.cue.global.error.CustomException;
import com.cue.global.error.ErrorCode;
import com.cue.source.dto.SourceItem;
import com.cue.source.entity.ApiProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * Hugging Face 모델 검색 — 급상승 기준: {@code sort=trendingScore}(허브의 최근 인기 점수). 원문은 모델 카드(README.md). 토큰은 선택.
 */
public class HuggingFaceSourceClient implements SourceClient {
    private static final Pattern MODEL_ID = Pattern.compile("[\\w.-]+(/[\\w.-]+)?");
    private final RestClient restClient;
    private final String webBaseUrl;
    private final SearchSettings settings;

    public HuggingFaceSourceClient(RestClient restClient, String webBaseUrl, SearchSettings settings) {
        this.restClient = restClient;
        this.webBaseUrl = webBaseUrl;
        this.settings = settings;
    }

    @Override
    public ApiProvider provider() {
        return ApiProvider.HUGGING_FACE;
    }

    @Override
    public List<SourceItem> search(String keyword) {
        return query(keyword, settings);
    }

    /** 글로벌 트렌드: 검색어 없이 허브의 trendingScore 순 상위 모델. */
    @Override
    public List<SourceItem> trending(SearchSettings s) {
        return query("", s);
    }

    private List<SourceItem> query(String keyword, SearchSettings settings) {
        JsonNode root = SourceClientSupport.getJson(restClient.get().uri(uri -> {
            var b = uri.path("/api/models");
            if (!keyword.isBlank()) {
                b.queryParam("search", "{q}");
            }
            return b.queryParam("sort", "trendingScore").queryParam("direction", -1)
                    .queryParam("limit", settings.topN()).build(keyword.isBlank() ? new Object[0] : new Object[]{keyword});
        }));
        List<SourceItem> items = new ArrayList<>();
        for (JsonNode n : root) {
            String id = SourceClientSupport.text(n, "id");
            String date = SourceClientSupport.date(n, "lastModified");
            items.add(new SourceItem(id, webBaseUrl + "/" + id, SourceClientSupport.text(n, "pipeline_tag"),
                    date.isEmpty() ? SourceClientSupport.date(n, "createdAt") : date,
                    SourceClientSupport.metrics(n, "downloads", "downloads", "likes", "likes", "trending", "trendingScore")));
        }
        return items;
    }

    /** 모델 카드 README (YAML 머리말 제외). */
    @Override
    public String fetchContent(SourceItem item) {
        if (!MODEL_ID.matcher(item.title()).matches()) {
            throw new CustomException(ErrorCode.EXTERNAL_SERVER_ERROR);
        }
        String card = SourceClientSupport.getText(restClient.get().uri("/" + item.title() + "/raw/main/README.md"));
        return card.startsWith("---") && card.indexOf("\n---", 3) > 0
                ? card.substring(card.indexOf("\n---", 3) + 4).strip() : card;
    }
}
