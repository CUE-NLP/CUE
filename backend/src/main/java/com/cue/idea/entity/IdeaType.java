package com.cue.idea.entity;

/**
 * 아이디어 대분류. 현재는 IT 하나뿐이며 {@code ideas.type}은 IT 고정이다 (나중에 IT 외 분야를 추가할 때를 위해 컬럼 유지).
 * 외부 API 조합은 대분류가 아니라 IT 내부의 세부 종류({@link ItCategory})가 결정한다.
 */
public enum IdeaType {
    IT
}
