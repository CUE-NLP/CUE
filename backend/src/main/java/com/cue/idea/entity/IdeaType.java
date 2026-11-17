package com.cue.idea.entity;

import com.cue.source.entity.ApiProvider;
import java.util.List;

/**
 * 아이디어 종류. 종류별로 검색에 쓰는 외부 API 조합이 달라진다.
 * 값은 {@code ideas.type}(VARCHAR 30)에 문자열로 저장되며, Gemini 분류 결과의 허용 집합이기도 하다.
 */
public enum IdeaType {
    // TODO: 팀 확정 후 종류별 API 조합 조정 (IT 외 조합은 임시 값)
    IT(List.of(ApiProvider.GITHUB, ApiProvider.HUGGING_FACE, ApiProvider.STACK_EXCHANGE, ApiProvider.HACKER_NEWS)),
    HEALTHCARE(List.of(ApiProvider.SEMANTIC_SCHOLAR, ApiProvider.WIKIPEDIA_PAGEVIEWS, ApiProvider.HACKER_NEWS)),
    EDUCATION(List.of(ApiProvider.SEMANTIC_SCHOLAR, ApiProvider.WIKIPEDIA_PAGEVIEWS, ApiProvider.HACKER_NEWS)),
    FINANCE(List.of(ApiProvider.HACKER_NEWS, ApiProvider.WIKIPEDIA_PAGEVIEWS, ApiProvider.SEMANTIC_SCHOLAR)),
    COMMERCE(List.of(ApiProvider.HACKER_NEWS, ApiProvider.WIKIPEDIA_PAGEVIEWS, ApiProvider.STACK_EXCHANGE)),
    CONTENT(List.of(ApiProvider.HACKER_NEWS, ApiProvider.WIKIPEDIA_PAGEVIEWS, ApiProvider.GITHUB)),
    OTHER(List.of(ApiProvider.HACKER_NEWS, ApiProvider.WIKIPEDIA_PAGEVIEWS, ApiProvider.SEMANTIC_SCHOLAR));

    private final List<ApiProvider> searchProviders;

    IdeaType(List<ApiProvider> searchProviders) {
        this.searchProviders = searchProviders;
    }

    public List<ApiProvider> searchProviders() {
        return searchProviders;
    }

    /** AI가 돌려준 문자열을 종류로 바꾼다. 알 수 없는 값은 {@link #OTHER}. */
    public static IdeaType fromAi(String value) {
        if (value == null) {
            return OTHER;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return OTHER;
        }
    }
}
