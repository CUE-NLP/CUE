package com.cue.idea.dto;

import com.cue.idea.entity.IdeaType;
import jakarta.validation.constraints.NotBlank;

/** 사용자가 AI 구조화 결과를 검증한 뒤 저장하는 요청. 작성자는 인증 정보에서 가져온다. */
public record CreateIdeaRequest(
        IdeaType type,
        @NotBlank String problem,
        @NotBlank String target,
        @NotBlank String solution,
        @NotBlank String expectation) {}
