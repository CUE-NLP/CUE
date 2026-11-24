package com.cue.source.service;

import com.cue.global.ai.GeminiClient;
import com.cue.global.ai.PromptLoader;
import com.cue.source.config.SourceApiProperties;
import com.cue.source.dto.SourceItem;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * source(또는 트렌드 provider) 하나의 항목 여러 개를 <b>한 번의 Gemini 호출</b>로 요약한다 (항목마다 호출하던 것을 대체).
 *
 * <p>입력: 번호·제목·URL·날짜·지표·원문 일부를 항목마다 적은 목록 {@code {{items}}} (+ 호출한 쪽이 주는 다른 플레이스홀더).
 * 항목당 원문은 {@code batch-content-chars}로, 한 호출의 원문 합계는 {@code batch-total-chars}로 자른다(넘으면 항목당 길이를 균등하게 줄임).
 * 출력: {@code {"items":[{"url","summary"}]}} — {@code responseSchema}로 형식을 강제하고 서버가 다시 검증한다:
 * url이 입력에 있던 것만, 같은 url 중복은 첫 번째만, 빈 요약은 버리고, 요약은 {@value #MAX_SUMMARY_CHARS}자로 자른다.
 * 빠진 항목은 결과 맵에 없으므로 호출한 쪽이 건너뛰거나 원래 조회한 짧은 요약을 쓴다. Gemini 호출 자체의 실패는 예외로 던진다.
 */
@Component
public class ItemBatchSummarizer {
    public static final int MAX_SUMMARY_CHARS = 500;
    static final int MIN_CONTENT_CHARS = 200;

    static final Map<String, Object> SCHEMA = Map.of(
            "type", "OBJECT",
            "properties", Map.of("items", Map.of("type", "ARRAY", "items", Map.of(
                    "type", "OBJECT",
                    "properties", Map.of("url", Map.of("type", "STRING"), "summary", Map.of("type", "STRING")),
                    "required", List.of("url", "summary")))),
            "required", List.of("items"));

    /** 요약할 항목과 읽어 온 원문(이미 길이 상한 이전의 전체 텍스트여도 됨). */
    public record Entry(SourceItem item, String content) {}

    private final GeminiClient gemini;
    private final PromptLoader prompts;
    private final SourceApiProperties props;

    public ItemBatchSummarizer(GeminiClient gemini, PromptLoader prompts, SourceApiProperties props) {
        this.gemini = gemini;
        this.prompts = prompts;
        this.props = props;
    }

    /**
     * @param promptName 프롬프트 이름 ({@code trend-item-summary-batch})
     * @param vars       {@code items} 외에 프롬프트가 쓰는 값 (없으면 빈 맵)
     * @return url → 항목 요약 (검증을 통과한 것만, 입력 순서)
     */
    public Map<String, String> summarize(String promptName, Map<String, String> vars, List<Entry> entries) {
        if (entries.isEmpty()) {
            return Map.of();
        }
        Map<String, String> all = new HashMap<>(vars);
        all.put("items", format(entries));
        JsonNode result = gemini.generateJson(prompts.render(promptName, all), SCHEMA);

        Set<String> allowed = new HashSet<>();
        entries.forEach(e -> allowed.add(e.item().url()));
        Map<String, String> out = new LinkedHashMap<>();
        for (JsonNode n : result.path("items")) {
            String url = n.path("url").asString("").strip();
            String summary = n.path("summary").asString("").strip();
            if (!allowed.contains(url) || out.containsKey(url) || summary.isEmpty()) {
                continue; // 입력에 없던 URL(환각)·중복·빈 요약은 버린다
            }
            out.put(url, summary.length() > MAX_SUMMARY_CHARS ? summary.substring(0, MAX_SUMMARY_CHARS - 1) + "…" : summary);
        }
        return out;
    }

    /** 항목 목록 텍스트. 원문은 항목당·전체 상한으로 자른다. */
    String format(List<Entry> entries) {
        int perItem = Math.min(props.batchContentChars(), Math.max(MIN_CONTENT_CHARS, props.batchTotalChars() / entries.size()));
        StringBuilder sb = new StringBuilder();
        int no = 1;
        for (Entry e : entries) {
            String content = e.content() == null ? "" : e.content().strip();
            if (content.length() > perItem) {
                content = content.substring(0, perItem);
            }
            SourceItem it = e.item();
            sb.append("[").append(no++).append("]\n제목: ").append(one(it.title()))
                    .append("\nURL: ").append(it.url())
                    .append("\n날짜·지표: ").append(one(it.publishedAt()).isEmpty() ? "-" : one(it.publishedAt()))
                    .append(" / ").append(one(it.metrics()).isEmpty() ? "-" : one(it.metrics()))
                    .append("\n원문:\n").append(content).append("\n\n");
        }
        return sb.toString().strip();
    }

    private static String one(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").strip();
    }
}
