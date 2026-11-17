package com.cue.idea.entity;

import com.cue.source.entity.ApiProvider;
import java.util.List;

/** IT 아이디어의 세부 종류. 종류마다 외부 자료 검색에 쓰는 API 3개가 정해져 있다. */
public enum ItCategory {
    /** AI·머신러닝 */
    AI_ML(List.of(ApiProvider.HUGGING_FACE, ApiProvider.SEMANTIC_SCHOLAR, ApiProvider.GITHUB)),
    /** 개발자 도구·인프라 */
    DEV_TOOLS_INFRA(List.of(ApiProvider.GITHUB, ApiProvider.STACK_EXCHANGE, ApiProvider.HACKER_NEWS)),
    /** 웹·앱 서비스 */
    WEB_APP_SERVICE(List.of(ApiProvider.HACKER_NEWS, ApiProvider.STACK_EXCHANGE, ApiProvider.WIKIPEDIA_PAGEVIEWS)),
    /** 데이터·분석 */
    DATA_ANALYTICS(List.of(ApiProvider.GITHUB, ApiProvider.HUGGING_FACE, ApiProvider.SEMANTIC_SCHOLAR));

    /** 분류 실패·알 수 없는 값일 때 쓰는 기본 종류. */
    public static final ItCategory DEFAULT = DEV_TOOLS_INFRA;

    private final List<ApiProvider> searchProviders;

    ItCategory(List<ApiProvider> searchProviders) {
        this.searchProviders = searchProviders;
    }

    public List<ApiProvider> searchProviders() {
        return searchProviders;
    }

    /** AI가 돌려준 문자열을 종류로 바꾼다. 알 수 없는 값은 {@link #DEFAULT}. */
    public static ItCategory fromAi(String value) {
        if (value == null) {
            return DEFAULT;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return DEFAULT;
        }
    }
}
