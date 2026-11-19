package com.cue.source.dto;

/**
 * 외부 API 검색 결과 한 건. 평가 근거로 쓰이는 정보를 담는다.
 *
 * @param publishedAt 발행/갱신일(yyyy-MM-dd, 모르면 빈 문자열)
 * @param metrics     인기 지표 한 줄(예: "stars=1200, forks=30", 없으면 빈 문자열)
 * 변경 감지 해시는 title·url·summary만 쓴다(날짜·지표는 자주 변해 제외).
 */
public record SourceItem(String title, String url, String summary, String publishedAt, String metrics) {
    public SourceItem(String title, String url, String summary) {
        this(title, url, summary, "", "");
    }
}
