package com.cue.evaluation.service;

import com.cue.evaluation.dto.IdeaSnapshot;
import com.cue.evaluation.dto.PastEvaluation;
import com.cue.evaluation.dto.ReferenceInput;
import com.cue.evaluation.dto.ScoreChange;
import com.cue.global.ai.GeminiClient;
import com.cue.global.ai.PromptLoader;
import com.cue.global.error.CustomException;
import com.cue.global.error.ErrorCode;
import com.cue.global.error.ErrorDetail;
import com.cue.global.error.ErrorReason;
import com.cue.score.entity.ScoreType;
import com.cue.source.dto.EvaluatedItem;
import com.cue.source.dto.SourceCheckResult;
import com.cue.source.service.SourceTextFormat;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Gemini로 아이디어를 평가해 {@link ScoreChange} 목록으로 바꾼다. 트랜잭션과 무관한 순수 호출·변환 계층이다.
 *
 * <p>키워드 근거 자료로 <b>기존 4개 기준</b>({@link ScoreType#EVIDENCE_TYPES})만 평가한다. TREND는 {@link TrendEvaluator}가 맡는다.
 * 프롬프트(resources/prompts, 설계는 docs/ai-prompts.md) 플레이스홀더: {@code problem, target, solution, expectation}(아이디어 4항목),
 * 전체 평가는 {@code items}(조회한 근거 자료 전부), 재평가는 {@code currentScores}(현재 점수 "TYPE: 점수" 줄바꿈 목록),
 * {@code pastEvaluations}(기존 평가 내역), {@code newItems}(새로 추가된 자료), {@code removedItems}(삭제된 자료). 비면 "(… 없음)".
 * <ul>
 *   <li>re-evaluation: {@code {"changes":[{"type","difference","evidenceSummary","referenceIds":[]}]}} — 기준당 ±{@value #MAX_DIFFERENCE}로 서버에서 다시 제한</li>
 *   <li>initial-evaluation: {@code {"scores":{"PROBLEM_IMPORTANCE":{"score","evidenceSummary","referenceIds":[]}, ...}}} — 4개 기준을 검사한 뒤 현재 점수와의 차이로 기록</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class EvidenceEvaluator {
    /** 재평가 한 번에서 기준 하나가 움직일 수 있는 최대 점수(정수). 프롬프트가 어겨도 서버가 한 번 더 제한한다. */
    public static final BigDecimal MAX_DIFFERENCE = BigDecimal.TEN;
    static final BigDecimal MAX_SCORE = new BigDecimal("100");
    /** 근거 요약 최대 길이(자름). */
    public static final int MAX_SUMMARY_CHARS = 300;
    private static final String NO_SUMMARY = "AI가 근거 요약을 제공하지 않았습니다.";

    private static final List<String> TYPE_NAMES = ScoreType.EVIDENCE_TYPES.stream().map(Enum::name).toList();

    static final Map<String, Object> RE_EVALUATION_SCHEMA = Map.of(
            "type", "OBJECT",
            "properties", Map.of("changes", Map.of("type", "ARRAY", "maxItems", ScoreType.EVIDENCE_TYPES.size(),
                    "items", Map.of("type", "OBJECT",
                            "properties", Map.of(
                                    "type", Map.of("type", "STRING", "enum", TYPE_NAMES),
                                    "difference", Map.of("type", "NUMBER"),
                                    "evidenceSummary", Map.of("type", "STRING"),
                                    "referenceIds", EvaluationReferences.IDS_SCHEMA),
                            "required", List.of("type", "difference", "evidenceSummary", "referenceIds")))),
            "required", List.of("changes"));

    private static final Map<String, Object> INITIAL_SCORE_SCHEMA = Map.of(
            "type", "OBJECT",
            "properties", Map.of(
                    "score", Map.of("type", "NUMBER"),
                    "evidenceSummary", Map.of("type", "STRING"),
                    "referenceIds", EvaluationReferences.IDS_SCHEMA),
            "required", List.of("score", "evidenceSummary", "referenceIds"));

    static final Map<String, Object> INITIAL_EVALUATION_SCHEMA = Map.of(
            "type", "OBJECT",
            "properties", Map.of("scores", Map.of("type", "OBJECT",
                    "properties", TYPE_NAMES.stream().collect(Collectors.toMap(Function.identity(), type -> INITIAL_SCORE_SCHEMA)),
                    "required", TYPE_NAMES)),
            "required", List.of("scores"));

    private final GeminiClient gemini;
    private final PromptLoader prompts;

    /**
     * 기존 평가 내역에 새로 추가된 자료를 더하고 삭제된 자료를 빼서 기준별 점수 변화를 받는다. 변화가 0인 기준은 빠진다.
     * 그대로인 자료는 다시 보내지 않는다 (현재 점수와 기존 평가 내역에 반영돼 있다). 근거 URL은 새로 추가된 자료의 것만 받는다.
     *
     * @param history 기준별 최근 평가 내역
     * @param added   새로 추가된 항목만 담은 조회 결과
     * @param removed 검색 결과에서 사라진 항목
     */
    public List<ScoreChange> reEvaluate(IdeaSnapshot idea, List<PastEvaluation> history, List<SourceCheckResult> added,
                                        List<EvaluatedItem> removed) {
        Map<String, String> vars = ideaVariables(idea);
        StringBuilder scores = new StringBuilder();
        for (ScoreType type : ScoreType.EVIDENCE_TYPES) {
            scores.append(type).append(": ").append(idea.scores().getOrDefault(type, BigDecimal.ZERO)).append("\n");
        }
        vars.put("currentScores", scores.toString().strip());
        vars.put("pastEvaluations", historyLines(history, removed));
        var referenceItems = EvaluationReferences.from(added, SourceTextFormat.EVIDENCE_MAX_ITEMS,
                SourceTextFormat.EVIDENCE_MAX_SUMMARY_CHARS);
        vars.put("newItems", referenceItems.linesOr("(새로 추가된 자료 없음)"));
        vars.put("removedItems", removed.isEmpty() ? "(삭제된 자료 없음)" : removed.stream()
                .limit(SourceTextFormat.EVIDENCE_MAX_ITEMS)
                .map(i -> SourceTextFormat.reference(i.title(), i.url())).collect(Collectors.joining("\n")));
        JsonNode result = gemini.generateJson(prompts.render("re-evaluation", vars), RE_EVALUATION_SCHEMA);
        JsonNode changes = result.path("changes");
        if (!changes.isArray()) {
            throw new CustomException(ErrorCode.AI_RESPONSE_INVALID,
                    ErrorDetail.of(gemini.api(), ErrorReason.MISSING_LIST, "changes"));
        }
        Map<ScoreType, ScoreChange> out = new LinkedHashMap<>();
        for (JsonNode n : changes) {
            ScoreType type = parseType(n.path("type").asString(null));
            BigDecimal diff = number(n.path("difference"));
            if (type == null || diff == null || out.containsKey(type)) {
                continue;
            }
            diff = clamp(diff, MAX_DIFFERENCE.negate(), MAX_DIFFERENCE);
            if (diff.signum() != 0 && !n.path("evidenceSummary").asString("").isBlank()) {
                out.put(type, toChange(type, diff, n, referenceItems));
            }
        }
        return List.copyOf(out.values());
    }

    /** 아이디어 내용 전체를 보고 기준별 0~100 점수를 받아, 현재 점수와의 차이를 {@link ScoreChange}로 돌려준다. */
    public List<ScoreChange> initialEvaluate(IdeaSnapshot idea, List<SourceCheckResult> evidence) {
        Map<String, String> vars = ideaVariables(idea);
        var referenceItems = EvaluationReferences.from(evidence, SourceTextFormat.EVIDENCE_MAX_ITEMS,
                SourceTextFormat.EVIDENCE_MAX_SUMMARY_CHARS);
        vars.put("items", referenceItems.linesOr("(수집된 자료 없음)"));
        JsonNode result = gemini.generateJson(prompts.render("initial-evaluation", vars), INITIAL_EVALUATION_SCHEMA);
        JsonNode scores = result.path("scores");
        if (!scores.isObject() || scores.size() != ScoreType.EVIDENCE_TYPES.size()) {
            throw new CustomException(ErrorCode.AI_RESPONSE_INVALID,
                    ErrorDetail.of(gemini.api(), ErrorReason.INVALID_FIELD, "scores"));
        }
        List<ScoreChange> out = new ArrayList<>();
        for (ScoreType type : ScoreType.EVIDENCE_TYPES) {
            JsonNode n = scores.path(type.name());
            BigDecimal score = number(n.path("score"));
            JsonNode summary = n.path("evidenceSummary");
            JsonNode references = n.path("referenceIds");
            if (score == null || !summary.isString() || summary.asString().isBlank()
                    || !references.isArray() || !references.valueStream().allMatch(JsonNode::isIntegralNumber)) {
                throw new CustomException(ErrorCode.AI_RESPONSE_INVALID,
                        ErrorDetail.of(gemini.api(), ErrorReason.INVALID_FIELD, "scores." + type));
            }
            BigDecimal current = idea.scores().getOrDefault(type, BigDecimal.ZERO);
            BigDecimal diff = clamp(score, BigDecimal.ZERO, MAX_SCORE).subtract(current);
            if (diff.signum() != 0) {
                out.add(toChange(type, diff, n, referenceItems));
            }
        }
        return List.copyOf(out);
    }

    private static Map<String, String> ideaVariables(IdeaSnapshot idea) {
        Map<String, String> vars = new HashMap<>();
        vars.put("problem", nullToEmpty(idea.problem()));
        vars.put("target", nullToEmpty(idea.target()));
        vars.put("solution", nullToEmpty(idea.solution()));
        vars.put("expectation", nullToEmpty(idea.expectation()));
        return vars;
    }

    /** 평가 한 건을 한 줄로 쓰고 그 아래에 근거 자료를 붙인다. 이번에 삭제된 자료는 표시해 근거에서 빼게 한다. */
    private static String historyLines(List<PastEvaluation> history, List<EvaluatedItem> removed) {
        if (history.isEmpty()) {
            return "(기존 평가 내역 없음)";
        }
        Set<String> removedUrls = removed.stream().map(EvaluatedItem::url).collect(Collectors.toSet());
        List<String> lines = new ArrayList<>();
        for (PastEvaluation past : history) {
            String sign = past.difference().signum() > 0 ? "+" : "";
            lines.add(past.type() + " | " + past.evaluatedAt().toLocalDate() + " | 변화 " + sign
                    + past.difference().stripTrailingZeros().toPlainString() + " → "
                    + past.scoreAfter().stripTrailingZeros().toPlainString() + "점 | "
                    + past.evidenceSummary().replaceAll("\\s+", " ").strip());
            for (ReferenceInput ref : past.references()) {
                lines.add("  - 근거 자료" + (removedUrls.contains(ref.url()) ? "(삭제됨)" : "") + ": "
                        + SourceTextFormat.reference(ref.title(), ref.url()));
            }
        }
        return String.join("\n", lines);
    }

    static ScoreChange toChange(ScoreType type, BigDecimal diff, JsonNode n, EvaluationReferences referenceItems) {
        String summary = n.path("evidenceSummary").asString("").trim();
        if (summary.length() > MAX_SUMMARY_CHARS) {
            summary = summary.substring(0, MAX_SUMMARY_CHARS - 1) + "…";
        }
        return new ScoreChange(type, diff, summary.isEmpty() ? NO_SUMMARY : summary,
                referenceItems.resolve(n.path("referenceIds")));
    }

    private static ScoreType parseType(String value) {
        if (value == null) {
            return null;
        }
        try {
            ScoreType type = ScoreType.valueOf(value.trim().toUpperCase());
            return ScoreType.EVIDENCE_TYPES.contains(type) ? type : null; // TREND는 글로벌 트렌드 평가에서만 다룬다
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static BigDecimal number(JsonNode n) {
        if (!n.isNumber()) {
            return null;
        }
        return n.decimalValue().setScale(0, java.math.RoundingMode.HALF_UP); // 점수·변화량은 정수
    }

    static BigDecimal clamp(BigDecimal v, BigDecimal min, BigDecimal max) {
        return v.max(min).min(max);
    }

    static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
