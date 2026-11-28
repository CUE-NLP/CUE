package com.cue.source.entity;

import java.util.List;

/** 외부 자료 수집 API 6종. 아이디어 키워드 검색은 종류별 3~4개, 글로벌 트렌드는 고정 3개(키워드 없이)를 호출한다. */
public enum ApiProvider {
    HACKER_NEWS,
    GITHUB,
    HUGGING_FACE,
    WIKIPEDIA_PAGEVIEWS,
    SEMANTIC_SCHOLAR,
    STACK_EXCHANGE;

    /** 글로벌 트렌드(5번째 평가 기준 TREND)에 쓰는 고정 3개. 모두 키워드 없이 "급상승" 지표로 조회한다. */
    public static final List<ApiProvider> TREND_PROVIDERS = List.of(HACKER_NEWS, GITHUB, HUGGING_FACE);

    /** 에러 원인을 알릴 때 쓰는 이름. */
    public String displayName() {
        return switch (this) {
            case HACKER_NEWS -> "Hacker News";
            case GITHUB -> "GitHub";
            case HUGGING_FACE -> "Hugging Face";
            case WIKIPEDIA_PAGEVIEWS -> "Wikipedia";
            case SEMANTIC_SCHOLAR -> "Semantic Scholar";
            case STACK_EXCHANGE -> "Stack Exchange";
        };
    }
}
