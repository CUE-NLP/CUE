package com.cue.idea.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/** 아이디어 수정. null이면 그대로 두고, 빈 문자열이면 해당 항목을 비운다 (비우면 보류가 된다). */
@Schema(description = "아이디어 수정 요청. 보낸 항목만 바꾼다: 생략/null = 유지, 빈 문자열 = 비움(비우면 HOLD가 된다).")
public record UpdateIdeaRequest(
        @Schema(description = "문제 (생략=유지, \"\"=비움)", example = "수정된 문제 설명") @Size(max = 5000) String problem,
        @Schema(description = "타깃 (생략=유지, \"\"=비움)", example = "수정된 타깃") @Size(max = 5000) String target,
        @Schema(description = "해결 (생략=유지, \"\"=비움)", example = "수정된 해결 방식") @Size(max = 5000) String solution,
        @Schema(description = "기대효과 (생략=유지, \"\"=비움)", example = "수정된 기대효과") @Size(max = 5000) String expectation) {}
