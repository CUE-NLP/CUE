package com.cue.source.client;

import com.cue.source.dto.SourceItem;
import com.cue.source.entity.ApiProvider;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * Semantic Scholar 논문 검색 (Graph API {@code /graph/v1/paper/search}). 키워드로 <b>기간 제한 없이</b> 검색하고, 받은 상위 N편을 <b>인용 수 내림차순</b>(같으면 제목순)으로 정리한다.
 * 원문은 PDF를 읽지 않고 초록(abstract)을 쓴다: {@link SourceItem#summary()}에 초록 앞부분({@value #MAX_ABSTRACT_CHARS}자)을 담고 {@link #fetchContent}가 그대로 돌려준다.
 * 인용 수는 자주 변하므로 metrics로만 전달하고 해시에는 넣지 않는다(해시는 url 정렬 후 제목·URL만 쓰므로 인용 수 순위가 바뀌어도 변경으로 잡히지 않는다).
 * API 키는 선택이다({@code x-api-key} 헤더). 키가 없으면 익명 한도(공유 풀, 매우 낮음)라 429가 잦을 수 있고 모든 실패는 G002로 처리한다.
 */
public class SemanticScholarSourceClient implements SourceClient {
    static final int MAX_ABSTRACT_CHARS = 1500;
    static final String PAPER_URL = "https://www.semanticscholar.org/paper/";
    private final RestClient restClient;
    private final String apiKey;
    private final SearchSettings settings;

    public SemanticScholarSourceClient(RestClient restClient, String apiKey, SearchSettings settings) {
        this.restClient = restClient;
        this.apiKey = apiKey;
        this.settings = settings;
    }

    @Override
    public ApiProvider provider() {
        return ApiProvider.SEMANTIC_SCHOLAR;
    }

    @Override
    public List<SourceItem> search(String keyword) {
        var request = restClient.get().uri(uri -> uri.path("/graph/v1/paper/search")
                .queryParam("query", "{q}")
                .queryParam("limit", settings.topN())
                .queryParam("fields", "title,abstract,url,year,citationCount,publicationDate")
                .build(keyword));
        if (apiKey != null && !apiKey.isBlank()) {
            request = request.header("x-api-key", apiKey);
        }
        JsonNode root = SourceClientSupport.getJson(request);

        record Paper(SourceItem item, long citations) {}
        List<Paper> papers = new ArrayList<>();
        for (JsonNode n : root.path("data")) {
            String title = SourceClientSupport.text(n, "title");
            if (title.isBlank()) {
                continue;
            }
            String url = SourceClientSupport.text(n, "url");
            if (url.isBlank()) {
                String id = SourceClientSupport.text(n, "paperId");
                url = id.isBlank() ? "" : PAPER_URL + id;
            }
            if (url.isBlank()) {
                continue;
            }
            String abstractText = SourceClientSupport.text(n, "abstract").replaceAll("\\s+", " ").strip();
            if (abstractText.length() > MAX_ABSTRACT_CHARS) {
                abstractText = abstractText.substring(0, MAX_ABSTRACT_CHARS);
            }
            String date = SourceClientSupport.text(n, "publicationDate");
            if (date.isBlank() && n.path("year").isNumber()) {
                date = String.valueOf(n.path("year").asInt());
            }
            long citations = n.path("citationCount").isNumber() ? n.path("citationCount").asLong() : 0;
            papers.add(new Paper(new SourceItem(title, url, abstractText, date.length() > 10 ? date.substring(0, 10) : date,
                    SourceClientSupport.metrics(n, "citations", "citationCount")), citations));
        }
        papers.sort(Comparator.comparingLong(Paper::citations).reversed().thenComparing(p -> p.item().title()));
        return papers.stream().map(Paper::item).toList();
    }

    /** 초록이 원문이다 (이미 검색 응답에 들어 있어 추가 호출이 없다). 초록이 없는 논문은 빈 문자열이라 호출한 쪽이 건너뛴다. */
    @Override
    public String fetchContent(SourceItem item) {
        return item.summary() == null ? "" : item.summary();
    }
}
