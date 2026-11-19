package com.cue.idea.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 아이디어 수정. null이면 그대로 두고, 빈 문자열이면 해당 항목을 비운다 (비우면 보류가 된다).
 * keywords: null이면 유지, 빈 배열이면 사용자 키워드를 지우고 AI로 다시 추출, 1개 이상이면 그 값으로 교체(최대 2개, 각 1~50자).
 */
@Schema(description = "아이디어 수정 요청. 보낸 항목만 바꾼다: 생략/null = 유지, 빈 문자열 = 비움(비우면 HOLD가 된다).")
public record UpdateIdeaRequest(
        @Schema(description = "문제 (생략=유지, \"\"=비움)", example = "수정된 문제 설명") @Size(max = 5000) String problem,
        @Schema(description = "타깃 (생략=유지, \"\"=비움)", example = "수정된 타깃") @Size(max = 5000) String target,
        @Schema(description = "해결 (생략=유지, \"\"=비움)", example = "수정된 해결 방식") @Size(max = 5000) String solution,
        @Schema(description = "기대효과 (생략=유지, \"\"=비움)", example = "수정된 기대효과") @Size(max = 5000) String expectation,
        @Schema(description = "검색 키워드 (최대 2개, 각 1~50자). 생략/null=유지, 빈 배열=사용자 키워드를 지우고 AI가 다시 추출, "
                + "1개 이상=그 값으로 교체(keywordOrigin=USER)", example = "[\"idea scoring\"]", nullable = true)
        @Size(max = 2) List<@NotBlank @Size(max = 50) String> keywords) {}
