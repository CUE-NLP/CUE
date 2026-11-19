package com.cue.idea.dto;

import com.cue.score.entity.ScoreType;
import com.cue.source.entity.ApiProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 항목별 평가 이력 한 건: 변화량, 변경 후 점수, 당시 가중치(%), 근거 요약, 출처. */
@Schema(description = "항목별 평가 이력 한 건 (점수가 바뀐 평가만 존재)")
public record EvaluationResponse(
        @Schema(description = "평가 ID", example = "10") Long id,
        @Schema(description = "평가 항목", example = "MARKET_DEMAND") ScoreType scoreType,
        @Schema(description = "점수 변화량", example = "2.50") BigDecimal difference,
        @Schema(description = "변경 후 점수", example = "62.50") BigDecimal scoreAfter,
        @Schema(description = "평가 당시 가중치(%)", example = "25") Integer weightPercent,
        @Schema(description = "변화의 근거 요약", example = "관련 저장소의 스타 수가 지난주 대비 증가했다.") String evidenceSummary,
        @Schema(description = "재평가 시각", example = "2026-11-05T15:00:00") LocalDateTime evaluatedAt,
        @Schema(description = "근거 출처 목록") List<ReferenceResponse> references) {

    @Schema(description = "평가 근거 출처")
    public record ReferenceResponse(
            @Schema(description = "출처 URL", example = "https://github.com/example/repo") String url,
            @Schema(description = "출처 제목", example = "example/repo") String title,
            @Schema(description = "수집한 외부 API", example = "GITHUB") ApiProvider provider) {}
}
