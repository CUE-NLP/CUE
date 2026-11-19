package com.cue.idea.dto;

import com.cue.score.entity.ScoreType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

/** 항목별 가중치(%). 4개 항목 모두 필요하고 합이 100이어야 한다. 예: {"PROBLEM_IMPORTANCE": 30, ...} */
@Schema(description = "항목별 가중치(%) 변경 예약 요청. 4개 항목(ScoreType)을 모두 0~100 정수로 보내고 합이 정확히 100이어야 한다. "
        + "즉시 적용되지 않고 다음 평가 턴부터 적용된다.")
public record UpdateWeightsRequest(
        @Schema(description = "ScoreType → 가중치(%). 키: PROBLEM_IMPORTANCE(문제 중요성), MARKET_DEMAND(시장 수요), "
                + "MARKET_DIFFERENTIATION(시장 차별성), TECH_FEASIBILITY(기술적 실현 가능성)",
                example = "{\"PROBLEM_IMPORTANCE\": 30, \"MARKET_DEMAND\": 30, \"MARKET_DIFFERENTIATION\": 20, \"TECH_FEASIBILITY\": 20}")
        @NotNull Map<ScoreType, Integer> weights) {}
