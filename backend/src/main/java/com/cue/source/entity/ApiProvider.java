package com.cue.source.entity;

import java.util.List;

/** 외부 자료 수집 API 6종. 아이디어 검색은 종류별 3개, 트렌드는 고정 4개를 호출한다. */
public enum ApiProvider {
    HACKER_NEWS,
    GITHUB,
    HUGGING_FACE,
    WIKIPEDIA_PAGEVIEWS,
    SEMANTIC_SCHOLAR,
    STACK_EXCHANGE;

    /** 트렌드 조사에 쓰는 고정 4개 (TODO: 팀 확정 후 조정). */
    public static final List<ApiProvider> TREND_PROVIDERS =
            List.of(HACKER_NEWS, GITHUB, HUGGING_FACE, SEMANTIC_SCHOLAR);
}
