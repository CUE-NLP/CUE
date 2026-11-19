package com.cue.source.service;

import com.cue.source.dto.SourceCheckResult;
import com.cue.source.dto.SourceItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Gemini 프롬프트에 넣을 자료 목록 텍스트와 "summary 하나" 응답 스키마. */
public final class SourceTextFormat {
    private SourceTextFormat() {}

    /** {@code {"summary": "..."}} 한 필드 응답 스키마 (항목·소스·트렌드 요약 공통). */
    public static final Map<String, Object> SUMMARY_SCHEMA = Map.of(
            "type", "OBJECT",
            "properties", Map.of("summary", Map.of("type", "STRING")),
            "required", List.of("summary"));
    static final int MAX_SUMMARY_CHARS = 300;
    static final int MAX_TITLE_CHARS = 120;
    /** 프롬프트에 넣는 자료 항목 최대 개수 (토큰 절약). */
    public static final int MAX_ITEMS = 15;

    /**
     * 자료 목록 텍스트. 프롬프트의 {@code {{items}}}/{@code {{changedItems}}} 자리에 들어간다.
     * 한 줄에 한 건: {@code [PROVIDER] 제목 | URL | 날짜 | 지표 | 요약} (없는 값은 "-"). 항목당 길이와 전체 개수에 상한이 있다.
     */
    public static String formatSources(List<SourceCheckResult> sources) {
        List<String> lines = new ArrayList<>();
        outer:
        for (SourceCheckResult r : sources) {
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
