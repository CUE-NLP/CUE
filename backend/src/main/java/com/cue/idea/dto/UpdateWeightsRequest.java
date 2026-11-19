package com.cue.idea.dto;

import com.cue.score.entity.ScoreType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

/** 항목별 가중치(%). 5개 항목(TREND 포함) 모두 필요하고 합이 100이어야 한다. */
@Schema(description = "항목별 가중치(%) 변경 예약 요청. 5개 항목(ScoreType)을 모두 0~100 정수로 보내고 합이 정확히 100이어야 한다. "
        + "즉시 적용되지 않고 다음 평가 턴부터 적용된다. 4개짜리 예전 형식(TREND 없음)은 I003으로 거절된다.")
public record UpdateWeightsRequest(
        @Schema(description = "ScoreType → 가중치(%). 키: PROBLEM_IMPORTANCE(문제 중요성), MARKET_DEMAND(시장 수요), "
                + "MARKET_DIFFERENTIATION(시장 차별성), TECH_FEASIBILITY(기술적 실현 가능성), TREND(트렌디함)",
                example = "{\"PROBLEM_IMPORTANCE\": 20, \"MARKET_DEMAND\": 20, \"MARKET_DIFFERENTIATION\": 20, \"TECH_FEASIBILITY\": 20, \"TREND\": 20}")
        @NotNull Map<ScoreType, Integer> weights) {}
