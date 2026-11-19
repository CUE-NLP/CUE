package com.cue.idea.entity;

import io.swagger.v3.oas.annotations.media.Schema;

/** ACTIVE: 구조화 4개 항목이 모두 채워져 평가 대상. HOLD: 하나라도 비어 있어 보류 (평가·키워드 추출 안 함). */
@Schema(description = "상태. ACTIVE=구조화 4개 항목이 모두 채워져 평가 대상, HOLD=하나라도 비어 보류(평가·AI 호출 없음)")
public enum IdeaStatus {
    ACTIVE,
    HOLD
}
