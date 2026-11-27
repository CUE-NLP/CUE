package com.cue.source.dto;

/** 점수에 마지막으로 반영한 근거 자료 한 건. 다음 조회와 비교해 추가·삭제를 가려낸다 (원문·발췌는 저장하지 않는다). */
public record EvaluatedItem(String url, String title) {}
