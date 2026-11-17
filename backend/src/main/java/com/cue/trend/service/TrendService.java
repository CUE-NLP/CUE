package com.cue.trend.service;

import com.cue.global.ai.GeminiClient;
import com.cue.global.ai.PromptLoader;
import com.cue.source.dto.SourceCheckResult;
import com.cue.source.dto.SourceItem;
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
    static final Map<String, Object> SCHEMA = Map.of(
            "type", "OBJECT",
            "properties", Map.of("summary", Map.of("type", "STRING")),
            "required", List.of("summary"));
    private static final int MAX_SUMMARY_CHARS = 200;

    private final GeminiClient gemini;
    private final PromptLoader prompts;
    private final TrendRepository trends;

    /**
     * 요약을 만들어 저장한다. 요약은 부가 정보라서 실패해도 평가를 막지 않도록 null을 돌려주고 로그만 남긴다.
     *
     * @return 저장된 Trend, 실패하면 null
     */
    public Trend summarize(List<SourceCheckResult> changed) {
        if (changed.isEmpty()) {
            return null;
        }
        try {
            String prompt = prompts.render("trend-summary", Map.of(
                    "keywords", changed.stream().map(SourceCheckResult::keyword).distinct().collect(Collectors.joining(", ")),
                    "items", formatSources(changed)));
            JsonNode result = gemini.generateJson(prompt, SCHEMA);
            String summary = result.path("summary").asString("").trim();
            if (summary.isEmpty()) {
                log.warn("트렌드 요약이 비어 있어 연결하지 않습니다.");
                return null;
            }
            List<ApiProvider> providers = changed.stream().map(SourceCheckResult::provider).distinct().toList();
            return trends.save(new Trend(summary, providers));
        } catch (RuntimeException e) {
            log.warn("트렌드 요약 실패, 트렌드 없이 평가를 계속합니다: {}", e.toString());
            return null;
        }
    }

    /** 변경된 source별 상위 자료 목록 텍스트. 프롬프트의 {@code {{items}}} 자리에 들어간다. */
    public static String formatSources(List<SourceCheckResult> changed) {
        StringBuilder sb = new StringBuilder();
        for (SourceCheckResult r : changed) {
            sb.append("[").append(r.provider()).append(" / 키워드: ").append(r.keyword()).append("]\n");
            for (SourceItem item : r.items()) {
                sb.append("- ").append(item.title()).append(" (").append(item.url()).append(")");
                String summary = item.summary() == null ? "" : item.summary().strip();
                if (!summary.isEmpty()) {
                    sb.append(": ").append(summary.length() > MAX_SUMMARY_CHARS
                            ? summary.substring(0, MAX_SUMMARY_CHARS) + "…" : summary);
                }
                sb.append("\n");
            }
        }
        return sb.toString().strip();
    }
}
