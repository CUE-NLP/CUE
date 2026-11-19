package com.cue.source.client;

import com.cue.source.dto.SourceItem;
import com.cue.source.entity.ApiProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import com.cue.global.error.CustomException;
import com.cue.global.error.ErrorCode;
import com.cue.source.fetch.HtmlText;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.HtmlUtils;
import tools.jackson.databind.JsonNode;

/**
 * Stack Overflow 질문 검색 — 급상승 기준: 최근 N일 안에 생성된 질문을 득표 순으로(search/advanced는 hot·week 정렬을 지원하지 않아
 * {@code fromdate} + {@code sort=votes}로 대체). 원문은 질문 본문 + 최고 득표 답변 (filter=withbody). 키(key)는 선택이며 있으면 일일 쿼터가 늘어난다.
 */
public class StackExchangeSourceClient implements SourceClient {
    private static final java.util.regex.Pattern QUESTION_ID = java.util.regex.Pattern.compile("/questions/(\\d+)");
    private final RestClient restClient;
    private final String apiKey;
    private final SearchSettings settings;

    public StackExchangeSourceClient(RestClient restClient, String apiKey, SearchSettings settings) {
        this.restClient = restClient;
        this.apiKey = apiKey;
        this.settings = settings;
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
                    .queryParam("sort", "votes").queryParam("order", "desc")
                    .queryParam("pagesize", settings.topN()).queryParam("fromdate", settings.cutoffEpochSecond());
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

    /** 질문 본문 + 최고 득표 답변 본문 (HTML은 텍스트로). */
    @Override
    public String fetchContent(SourceItem item) {
        Matcher m = QUESTION_ID.matcher(item.url());
        if (!m.find()) {
            throw new CustomException(ErrorCode.EXTERNAL_SERVER_ERROR);
        }
        String id = m.group(1);
        JsonNode question = SourceClientSupport.getJson(restClient.get().uri(uri -> withKey(uri
                .path("/2.3/questions/" + id).queryParam("site", "stackoverflow").queryParam("filter", "withbody")).build()));
        JsonNode answers = SourceClientSupport.getJson(restClient.get().uri(uri -> withKey(uri
                .path("/2.3/questions/" + id + "/answers").queryParam("site", "stackoverflow")
                .queryParam("sort", "votes").queryParam("order", "desc").queryParam("pagesize", 1)
                .queryParam("filter", "withbody")).build()));
        StringBuilder sb = new StringBuilder("Question: ")
                .append(HtmlText.toText(SourceClientSupport.text(question.path("items").path(0), "body")));
        String answer = HtmlText.toText(SourceClientSupport.text(answers.path("items").path(0), "body"));
        if (!answer.isEmpty()) {
            sb.append("\n\nTop answer: ").append(answer);
        }
        return sb.toString();
    }

    private org.springframework.web.util.UriBuilder withKey(org.springframework.web.util.UriBuilder uri) {
        if (apiKey != null && !apiKey.isBlank()) {
            uri.queryParam("key", apiKey);
        }
        return uri;
    }
}
