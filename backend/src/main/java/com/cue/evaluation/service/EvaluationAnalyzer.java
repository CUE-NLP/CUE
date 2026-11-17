package com.cue.evaluation.service;

import com.cue.evaluation.dto.IdeaSnapshot;
import com.cue.evaluation.dto.ReferenceInput;
import com.cue.evaluation.dto.ScoreChange;
import com.cue.global.ai.GeminiClient;
import com.cue.global.ai.PromptLoader;
import com.cue.global.error.CustomException;
import com.cue.global.error.ErrorCode;
import com.cue.score.entity.ScoreType;
import com.cue.source.dto.SourceCheckResult;
import com.cue.source.dto.SourceItem;
import com.cue.source.service.SourceTextFormat;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Gemini로 아이디어를 평가해 {@link ScoreChange} 목록으로 바꾼다. 트랜잭션과 무관한 순수 호출·변환 계층이다.
 *
 * <p>키워드 근거 자료로 <b>기존 4개 기준</b>({@link ScoreType#EVIDENCE_TYPES})만 평가한다. TREND는 {@link TrendEvaluator}가 맡는다.
 * 프롬프트(resources/prompts, 설계는 docs/ai-prompts.md) 플레이스홀더: {@code problem, target, solution, expectation}(아이디어 4항목),
 * {@code currentScores}(현재 점수 "TYPE: 점수" 줄바꿈 목록), {@code changedItems}/{@code items}(근거 자료 목록, 없으면 "(변경된 자료 없음)").
 * 프롬프트가 쓰지 않는 값은 무시된다.
 * <ul>
 *   <li>re-evaluation: {@code {"changes":[{"type","difference","evidenceSummary","referenceUrls":[]}]}} — 기준당 ±{@value #MAX_DIFFERENCE}로 서버에서 다시 제한</li>
 *   <li>initial-evaluation: {@code {"scores":[{"type","score","evidenceSummary","referenceUrls":[]}]}} — 0~100, 현재 점수와의 차이로 바꿔 기록</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class EvaluationAnalyzer {
    /** 재평가 한 번에서 기준 하나가 움직일 수 있는 최대 점수(정수). 프롬프트가 어겨도 서버가 한 번 더 제한한다. */
    public static final BigDecimal MAX_DIFFERENCE = BigDecimal.TEN;
    private static final BigDecimal MAX_SCORE = new BigDecimal("100");
    /** 근거 요약 최대 길이(자름). */
    public static final int MAX_SUMMARY_CHARS = 300;
    private static final String NO_SUMMARY = "AI가 근거 요약을 제공하지 않았습니다.";

    private static final List<String> TYPE_NAMES = ScoreType.EVIDENCE_TYPES.stream().map(Enum::name).toList();
    private static final Map<String, Object> REFERENCE_URLS = Map.of("type", "ARRAY", "items", Map.of("type", "STRING"));

    static final Map<String, Object> RE_EVALUATION_SCHEMA = Map.of(
            "type", "OBJECT",
            "properties", Map.of("changes", Map.of("type", "ARRAY", "maxItems", ScoreType.EVIDENCE_TYPES.size(),
                    "items", Map.of("type", "OBJECT",
                            "properties", Map.of(
                                    "type", Map.of("type", "STRING", "enum", TYPE_NAMES),
                                    "difference", Map.of("type", "NUMBER"),
                                    "evidenceSummary", Map.of("type", "STRING"),
                                    "referenceUrls", REFERENCE_URLS),
                            "required", List.of("type", "difference", "evidenceSummary")))),
            "required", List.of("changes"));

    static final Map<String, Object> INITIAL_EVALUATION_SCHEMA = Map.of(
            "type", "OBJECT",
            "properties", Map.of("scores", Map.of("type", "ARRAY", "maxItems", ScoreType.EVIDENCE_TYPES.size(),
                    "items", Map.of("type", "OBJECT",
                            "properties", Map.of(
                                    "type", Map.of("type", "STRING", "enum", TYPE_NAMES),
                                    "score", Map.of("type", "NUMBER"),
                                    "evidenceSummary", Map.of("type", "STRING"),
                                    "referenceUrls", REFERENCE_URLS),
                            "required", List.of("type", "score", "evidenceSummary")))),
            "required", List.of("scores"));

    private final GeminiClient gemini;
    private final PromptLoader prompts;

    /** 변경된 자료를 근거로 기준별 점수 변화를 받는다. 변화가 0인 기준은 빠진다. */
    public List<ScoreChange> reEvaluate(IdeaSnapshot idea, List<SourceCheckResult> changed) {
        JsonNode result = gemini.generateJson(
                prompts.render("re-evaluation", variables(idea, changed)), RE_EVALUATION_SCHEMA);
        JsonNode changes = result.path("changes");
        if (!changes.isArray()) {
            throw new CustomException(ErrorCode.AI_RESPONSE_INVALID);
        }
        Map<ScoreType, ScoreChange> out = new LinkedHashMap<>();
        Map<String, SourceCheckResult> byUrl = indexByUrl(changed);
        for (JsonNode n : changes) {
            ScoreType type = parseType(n.path("type").asString(null));
            BigDecimal diff = number(n.path("difference"));
            if (type == null || diff == null || out.containsKey(type)) {
                continue;
            }
            diff = clamp(diff, MAX_DIFFERENCE.negate(), MAX_DIFFERENCE);
            if (diff.signum() != 0 && !n.path("evidenceSummary").asString("").isBlank()) {
                out.put(type, toChange(type, diff, n, byUrl));
            }
        }
        return List.copyOf(out.values());
    }

    /** 아이디어 내용 전체를 보고 기준별 0~100 점수를 받아, 현재 점수와의 차이를 {@link ScoreChange}로 돌려준다. */
    public List<ScoreChange> initialEvaluate(IdeaSnapshot idea, List<SourceCheckResult> changed) {
        JsonNode result = gemini.generateJson(
                prompts.render("initial-evaluation", variables(idea, changed)), INITIAL_EVALUATION_SCHEMA);
        JsonNode scores = result.path("scores");
        if (!scores.isArray()) {
            throw new CustomException(ErrorCode.AI_RESPONSE_INVALID);
        }
        Map<ScoreType, ScoreChange> out = new LinkedHashMap<>();
        Map<String, SourceCheckResult> byUrl = indexByUrl(changed);
        for (JsonNode n : scores) {
            ScoreType type = parseType(n.path("type").asString(null));
            BigDecimal score = number(n.path("score"));
            if (type == null || score == null || out.containsKey(type)) {
                continue;
            }
            BigDecimal current = idea.scores().getOrDefault(type, BigDecimal.ZERO);
            BigDecimal diff = clamp(score, BigDecimal.ZERO, MAX_SCORE).subtract(current);
            if (diff.signum() != 0) {
                out.put(type, toChange(type, diff, n, byUrl));
            }
        }
        return List.copyOf(out.values());
    }

    private Map<String, String> variables(IdeaSnapshot idea, List<SourceCheckResult> changed) {
        StringBuilder scores = new StringBuilder();
        for (ScoreType type : ScoreType.EVIDENCE_TYPES) {
            scores.append(type).append(": ").append(idea.scores().getOrDefault(type, BigDecimal.ZERO)).append("\n");
        }
        Map<String, String> vars = new HashMap<>();
        vars.put("problem", nullToEmpty(idea.problem()));
        vars.put("target", nullToEmpty(idea.target()));
        vars.put("solution", nullToEmpty(idea.solution()));
        vars.put("expectation", nullToEmpty(idea.expectation()));
        String items = changed.isEmpty() ? "(변경된 자료 없음)" : SourceTextFormat.formatSources(changed);
        vars.put("currentScores", scores.toString().strip());
        vars.put("changedItems", items); // re-evaluation
        vars.put("items", items);        // initial-evaluation
        return vars;
    }

    private static ScoreChange toChange(ScoreType type, BigDecimal diff, JsonNode n, Map<String, SourceCheckResult> byUrl) {
        String summary = n.path("evidenceSummary").asString("").trim();
        List<ReferenceInput> refs = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (JsonNode u : n.path("referenceUrls")) {
            String url = u.asString("").trim();
            if (url.isEmpty() || !seen.add(url)) {
                continue;
            }
            SourceCheckResult from = byUrl.get(url);
            if (from == null) {
                continue; // 입력 자료에 없는 URL은 환각일 수 있어 버린다
            }
            refs.add(new ReferenceInput(url, titleOf(from, url), from.provider()));
        }
        if (summary.length() > MAX_SUMMARY_CHARS) {
            summary = summary.substring(0, MAX_SUMMARY_CHARS - 1) + "…";
        }
        return new ScoreChange(type, diff, summary.isEmpty() ? NO_SUMMARY : summary, refs);
    }

    private static Map<String, SourceCheckResult> indexByUrl(List<SourceCheckResult> changed) {
        Map<String, SourceCheckResult> map = new HashMap<>();
        for (SourceCheckResult r : changed) {
            for (SourceItem item : r.items()) {
                map.putIfAbsent(item.url(), r);
            }
        }
        return map;
    }

    private static String titleOf(SourceCheckResult r, String url) {
        return r.items().stream().filter(i -> url.equals(i.url())).map(SourceItem::title).findFirst().orElse(null);
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

    private static BigDecimal number(JsonNode n) {
        if (!n.isNumber()) {
            return null;
        }
        return n.decimalValue().setScale(0, java.math.RoundingMode.HALF_UP); // 점수·변화량은 정수
    }

    private static BigDecimal clamp(BigDecimal v, BigDecimal min, BigDecimal max) {
        return v.max(min).min(max);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
