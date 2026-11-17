package com.cue.trend.service;

import com.cue.global.ai.GeminiClient;
import com.cue.global.ai.PromptLoader;
import com.cue.source.dto.SourceCheckResult;
import com.cue.source.dto.SourceItem;
import com.cue.source.dto.SourceSummary;
import com.cue.source.entity.ApiProvider;
import com.cue.trend.entity.Trend;
import com.cue.trend.repository.TrendRepository;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 변경이 감지된 외부 자료를 Gemini로 요약해 {@link Trend}로 저장한다.
 * Gemini 호출 동안 DB 트랜잭션을 잡지 않으려고 @Transactional을 쓰지 않는다 (저장은 repository 호출 단위).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrendService {
    public static final Map<String, Object> SCHEMA = Map.of(
            "type", "OBJECT",
            "properties", Map.of("summary", Map.of("type", "STRING")),
            "required", List.of("summary"));
    static final int MAX_SUMMARY_CHARS = 300;
    static final int MAX_TITLE_CHARS = 120;
    /** 프롬프트에 넣는 자료 항목 최대 개수 (토큰 절약). */
    public static final int MAX_ITEMS = 15;

    private final GeminiClient gemini;
    private final PromptLoader prompts;
    private final TrendRepository trends;

    /**
     * 변경된 source들의 소스 단위 요약(없으면 항목 목록)을 바탕으로 최종 트렌드 요약을 만들어 저장한다.
     * 요약은 부가 정보라서 실패해도 평가를 막지 않도록 null을 돌려주고 로그만 남긴다.
     *
     * @return 저장된 Trend, 실패하면 null
     */
    public Trend summarize(List<SourceSummary> summaries) {
        if (summaries.isEmpty()) {
            return null;
        }
        try {
            StringBuilder items = new StringBuilder();
            for (SourceSummary s : summaries) {
                SourceCheckResult r = s.result();
                if (s.summary() != null) {
                    items.append("[").append(r.provider()).append(" / 키워드: ").append(r.keyword()).append("] ")
                            .append(s.summary().replaceAll("\\s+", " ")).append("\n");
                } else {
                    String list = formatSources(List.of(r));
                    if (!list.isEmpty()) items.append(list).append("\n");
                }
            }
            String keywords = summaries.stream().map(s -> s.result().keyword()).distinct().collect(Collectors.joining(", "));
            JsonNode result = gemini.generateJson(prompts.render("trend-summary",
                    Map.of("keywords", keywords, "items", items.toString().strip())), SCHEMA);
            String summary = result.path("summary").asString("").trim();
            if (summary.isEmpty()) {
                log.warn("트렌드 요약이 비어 있어 연결하지 않습니다.");
                return null;
            }
            List<ApiProvider> providers = summaries.stream().map(s -> s.result().provider()).distinct().toList();
            return trends.save(new Trend(summary, providers));
        } catch (RuntimeException e) {
            log.warn("트렌드 요약 실패, 트렌드 없이 평가를 계속합니다: {}", e.toString());
            return null;
        }
    }

    /**
     * 변경된(새로 나타난) 자료 목록 텍스트. 프롬프트의 {@code {{items}}}/{@code {{changedItems}}} 자리에 들어간다.
     * 한 줄에 한 건: {@code [PROVIDER] 제목 | URL | 날짜 | 지표 | 요약} (없는 값은 "-"). 항목당 길이와 전체 개수에 상한이 있다.
     */
    public static String formatSources(List<SourceCheckResult> changed) {
        List<String> lines = new java.util.ArrayList<>();
        outer:
        for (SourceCheckResult r : changed) {
            for (SourceItem item : r.items()) {
                if (lines.size() >= MAX_ITEMS) {
                    break outer;
                }
                lines.add("[" + r.provider() + "] " + field(item.title(), MAX_TITLE_CHARS) + " | " + field(item.url(), 300)
                        + " | " + field(item.publishedAt(), 10) + " | " + field(item.metrics(), 60)
                        + " | " + field(item.summary(), MAX_SUMMARY_CHARS));
            }
        }
        return String.join("\n", lines);
    }

    private static String field(String value, int max) {
        String v = value == null ? "" : value.replaceAll("\\s+", " ").strip().replace("|", "/");
        if (v.isEmpty()) {
            return "-";
        }
        return v.length() > max ? v.substring(0, max) + "…" : v;
    }
}
