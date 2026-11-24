package com.cue.evaluation.service;

import com.cue.evaluation.dto.IdeaSnapshot;
import com.cue.evaluation.dto.ScoreChange;
import com.cue.global.ai.GeminiClient;
import com.cue.global.ai.PromptLoader;
import com.cue.score.entity.ScoreType;
import com.cue.source.service.SourceTextFormat;
import com.cue.trend.dto.TrendSnapshot;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * 5번째 평가 기준 {@link ScoreType#TREND}(트렌디함)를 평가한다. 아이디어의 4개 항목을 그날의 글로벌 트렌드 요약에 대입해
 * Gemini에 판단시키고 결과를 {@link ScoreChange}로 바꾼다 (트랜잭션과 무관한 호출·변환 계층).
 *
 * <p>모드는 아이디어의 TREND 평가 이력으로 가른다: {@code lastTrendId == null}이면 <b>첫 평가</b>(응답의 {@code score} 0~100을 현재 점수와의
 * 차이로 바꿔 기록), 아니면 <b>재평가</b>(응답의 {@code difference}를 ±10으로 서버에서 다시 제한).
 * 근거 요약은 300자로 자르고, 근거 URL은 그 트렌드의 항목 URL만 받아들인다.
 * 만든 {@link ScoreChange}에는 그 트렌드 id가 들어 있어 {@code evaluations.trend_id}로 연결된다.
 * 프롬프트: {@code trend-evaluation} (플레이스홀더 {@code problem, target, solution, expectation, currentTrendScore, trendSummary, trendItems}).
 */
@Component
@RequiredArgsConstructor
public class TrendEvaluator {
    static final Map<String, Object> SCHEMA = Map.of(
            "type", "OBJECT",
            "properties", Map.of(
                    "score", Map.of("type", "NUMBER"),
                    "difference", Map.of("type", "NUMBER"),
                    "evidenceSummary", Map.of("type", "STRING"),
                    "referenceUrls", Map.of("type", "ARRAY", "items", Map.of("type", "STRING"))),
            "required", List.of("evidenceSummary"));

    private final GeminiClient gemini;
    private final PromptLoader prompts;

    /** @return 점수가 바뀌면 TREND 변화 1건, 바뀌지 않으면 빈 목록 */
    public List<ScoreChange> evaluate(IdeaSnapshot idea, TrendSnapshot trend) {
        boolean initial = idea.lastTrendId() == null;
        BigDecimal current = idea.scores().getOrDefault(ScoreType.TREND, BigDecimal.ZERO);
        JsonNode n = gemini.generateJson(prompts.render("trend-evaluation", Map.of(
                "problem", EvidenceEvaluator.nullToEmpty(idea.problem()),
                "target", EvidenceEvaluator.nullToEmpty(idea.target()),
                "solution", EvidenceEvaluator.nullToEmpty(idea.solution()),
                "expectation", EvidenceEvaluator.nullToEmpty(idea.expectation()),
                "currentTrendScore", initial ? "없음 (첫 평가)" : current.toPlainString(),
                "trendSummary", trend.summary(),
                "trendItems", trend.sources().isEmpty() ? "(항목 없음)" : SourceTextFormat.formatSources(trend.sources()))), SCHEMA);

        BigDecimal diff;
        if (initial) {
            BigDecimal score = EvidenceEvaluator.number(n.path("score"));
            if (score == null) {
                return List.of();
            }
            diff = EvidenceEvaluator.clamp(score, BigDecimal.ZERO, EvidenceEvaluator.MAX_SCORE).subtract(current);
        } else {
            BigDecimal d = EvidenceEvaluator.number(n.path("difference"));
            if (d == null || n.path("evidenceSummary").asString("").isBlank()) {
                return List.of();
            }
            diff = EvidenceEvaluator.clamp(d, EvidenceEvaluator.MAX_DIFFERENCE.negate(), EvidenceEvaluator.MAX_DIFFERENCE);
        }
        if (diff.signum() == 0) {
            return List.of();
        }
        return List.of(EvidenceEvaluator.toChange(ScoreType.TREND, diff, n, EvidenceEvaluator.indexByUrl(trend.sources()))
                .withTrendId(trend.id()));
    }
}
