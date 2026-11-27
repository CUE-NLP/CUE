package com.cue.evaluation.service;

import com.cue.evaluation.dto.ReferenceInput;
import com.cue.source.dto.SourceCheckResult;
import com.cue.source.dto.SourceItem;
import com.cue.source.service.SourceTextFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** 한 평가 요청에 노출한 자료와 인용 ID를 함께 만든다. ID는 그 요청에서만 유효하다. */
record EvaluationReferences(String lines, Map<Integer, ReferenceInput> byId) {
    static final Map<String, Object> IDS_SCHEMA = Map.of("type", "ARRAY", "items", Map.of("type", "INTEGER"));

    static EvaluationReferences from(List<SourceCheckResult> sources, int maxItems, int maxSummaryChars) {
        List<String> lines = new ArrayList<>();
        Map<Integer, ReferenceInput> byId = new HashMap<>();
        outer:
        for (SourceCheckResult source : sources) {
            for (SourceItem item : source.items()) {
                if (lines.size() >= maxItems) {
                    break outer;
                }
                int id = lines.size() + 1;
                boolean citable = item.url() != null && !item.url().isBlank();
                if (citable) {
                    byId.put(id, new ReferenceInput(item.url(), item.title(), source.provider()));
                }
                lines.add((citable ? Integer.toString(id) : "-") + " | "
                        + SourceTextFormat.formatItem(source.provider(), item, maxSummaryChars));
            }
        }
        return new EvaluationReferences(String.join("\n", lines), Map.copyOf(byId));
    }

    String linesOr(String emptyText) {
        return lines.isEmpty() ? emptyText : lines;
    }

    List<ReferenceInput> resolve(JsonNode ids) {
        List<ReferenceInput> refs = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (ids.isArray()) {
            for (JsonNode id : ids) {
                if (id.isIntegralNumber() && id.canConvertToInt()) {
                    ReferenceInput ref = byId.get(id.intValue());
                    if (ref != null && seen.add(ref.url())) {
                        refs.add(ref);
                    }
                }
            }
        }
        return refs;
    }
}
