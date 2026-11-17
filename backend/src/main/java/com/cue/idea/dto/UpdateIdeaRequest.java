package com.cue.idea.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 아이디어 수정. null이면 그대로 두고, 빈 문자열이면 해당 항목을 비운다 (비우면 보류가 된다).
 * keywords: null이면 유지, 빈 배열이면 사용자 키워드를 지우고 AI로 다시 추출, 1개 이상이면 그 값으로 교체(최대 2개, 각 1~50자).
 */
public record UpdateIdeaRequest(
        String problem, String target, String solution, String expectation,
        @Size(max = 2) List<@NotBlank @Size(max = 50) String> keywords) {}
