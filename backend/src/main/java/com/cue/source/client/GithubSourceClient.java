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
 * GitHub 저장소 검색 — 급상승 기준: 최근 N일 안에 push된 저장소를 stars 순으로. (신규 저장소만 보면 좋은 기존 프로젝트의 활동을 놓치고,
 * 전체 stars 순은 거의 안 변하므로 "최근 활동한 것 중 인기 있는 것"을 택했다.) 원문은 README. 토큰은 선택이지만 없으면 rate limit이 매우 낮다.
 */
public class GithubSourceClient implements SourceClient {
    private static final Pattern FULL_NAME = Pattern.compile("[\\w.-]+/[\\w.-]+");
    private final RestClient restClient;
    private final SearchSettings settings;

    public GithubSourceClient(RestClient restClient, SearchSettings settings) {
        this.restClient = restClient;
        this.settings = settings;
    }

    @Override
    public ApiProvider provider() {
        return ApiProvider.GITHUB;
    }

    @Override
    public List<SourceItem> search(String keyword) {
        return query(keyword + " pushed:>" + settings.cutoffDate(), settings);
    }

    /** 글로벌 트렌드: 최근 N일 안에 <b>새로 만들어진</b> 저장소를 stars 순으로 (키워드 없음). 갓 뜨는 프로젝트를 잡는다. */
    @Override
    public List<SourceItem> trending(SearchSettings s) {
        return query("created:>" + s.cutoffDate(), s);
    }

    private List<SourceItem> query(String query, SearchSettings settings) {
        JsonNode root = SourceClientSupport.getJson(restClient.get().uri(
                uri -> uri.path("/search/repositories")
                        .queryParam("q", "{q}").queryParam("sort", "stars").queryParam("order", "desc")
                        .queryParam("per_page", settings.topN()).build(query)));
        List<SourceItem> items = new ArrayList<>();
        for (JsonNode n : root.path("items")) {
            items.add(new SourceItem(
                    SourceClientSupport.text(n, "full_name"),
                    SourceClientSupport.text(n, "html_url"),
                    SourceClientSupport.text(n, "description"),
                    SourceClientSupport.date(n, "pushed_at"),
                    SourceClientSupport.metrics(n, "stars", "stargazers_count", "forks", "forks_count")));
        }
        return items;
    }

    /** README 원문 (GitHub API raw 미디어 타입). */
    @Override
    public String fetchContent(SourceItem item) {
        if (!FULL_NAME.matcher(item.title()).matches()) {
            throw new CustomException(ErrorCode.EXTERNAL_SERVER_ERROR);
        }
        return SourceClientSupport.getText(restClient.get().uri("/repos/" + item.title() + "/readme")
                .header("Accept", "application/vnd.github.raw+json"));
    }
}
