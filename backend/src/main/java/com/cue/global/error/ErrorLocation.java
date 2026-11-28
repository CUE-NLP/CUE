package com.cue.global.error;

/** 에러가 난 위치. 응답·DB·로그에 쓰는 문구는 여기에서만 정한다. */
public enum ErrorLocation {
    IDEA_STRUCTURING("아이디어 구조화"),
    KEYWORD_EXTRACTION("키워드·종류 추출"),
    CATEGORY_CLASSIFICATION("종류 분류"),
    EVALUATION_QUEUE("평가 대기열"),
    EVALUATION("아이디어 평가"),
    EVIDENCE_EVALUATION("근거 4개 기준 평가"),
    TREND_EVALUATION("트렌디함 평가"),
    EVALUATION_SAVE("평가 결과 저장"),
    SOURCE_SEARCH("소스 검색"),
    SOURCE_CONTENT("원문 읽기"),
    SOURCE_PAGEVIEWS("조회수 읽기"),
    TREND_SEARCH("글로벌 트렌드 조회"),
    TREND_CONTENT("트렌드 원문 읽기"),
    TREND_COLLECTION("글로벌 트렌드 수집");

    private final String label;

    ErrorLocation(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
