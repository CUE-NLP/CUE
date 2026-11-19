package com.cue.demo.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 시연 실행 요청 (본문 전체가 선택). {@code force}는 기본 false. */
@Schema(description = "시연 실행 요청. 본문 전체가 선택이다(없으면 force=false)")
public record DemoRunRequest(
        @Schema(description = "true면 글로벌 트렌드를 강제로 다시 수집하고 각 아이디어의 source 해시를 바꿔 자료를 다시 읽고 평가한다. 기본 false", example = "false", nullable = true) Boolean force) {
    public boolean forceOrDefault() {
        return Boolean.TRUE.equals(force);
    }
}
