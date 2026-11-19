package com.cue.source.dto;

/** 외부 API 검색 결과 한 건 (제목·URL·요약). 변경 감지 해시는 이 세 필드만으로 계산한다. */
public record SourceItem(String title, String url, String summary) {}
