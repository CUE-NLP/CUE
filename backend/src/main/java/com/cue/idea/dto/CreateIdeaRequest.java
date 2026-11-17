package com.cue.idea.dto;

import com.cue.idea.entity.IdeaType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 프론트가 구조화한 결과를 저장하는 요청. 작성자는 인증 정보에서 가져온다.
 * 4개 항목은 비어 있어도 되며, 하나라도 비면 서버가 보류(HOLD)로 저장한다.
 * keywords(선택): 사용자가 직접 정한 검색 키워드(최대 2개, 각 1~50자). 보내면 AI 키워드 추출 대신 이 값을 쓴다.
 */
public record CreateIdeaRequest(
        IdeaType type, String problem, String target, String solution, String expectation,
        @Size(max = 2) List<@NotBlank @Size(max = 50) String> keywords) {}
