package com.cue.idea.entity;

import io.swagger.v3.oas.annotations.media.Schema;

/** 리더보드 정렬: 총점순 / 최근 변화량(상승폭)순. */
@Schema(description = "리더보드 정렬. TOTAL=총점순, RECENT_RISE=최근 평가의 총점 변화량(상승폭)순")
public enum IdeaSort {
    TOTAL,
    RECENT_RISE
}
