package com.cue.idea.dto;

import com.cue.idea.entity.IdeaType;

/**
 * 프론트가 구조화한 결과를 저장하는 요청. 작성자는 인증 정보에서 가져온다.
 * 4개 항목은 비어 있어도 되며, 하나라도 비면 서버가 보류(HOLD)로 저장한다.
 */
public record CreateIdeaRequest(IdeaType type, String problem, String target, String solution, String expectation) {}
