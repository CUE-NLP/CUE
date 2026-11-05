package com.cue.idea.entity;

import com.cue.source.entity.ApiProvider;
import java.util.List;

/** 아이디어 종류. 종류별로 검색에 쓰는 외부 API 조합이 달라진다. 현재는 IT만 지원. */
public enum IdeaType {
    // TODO: 팀 확정 후 조합 조정
    IT(List.of(ApiProvider.GITHUB, ApiProvider.HUGGING_FACE, ApiProvider.STACK_EXCHANGE));

    private final List<ApiProvider> searchProviders;

    IdeaType(List<ApiProvider> searchProviders) {
        this.searchProviders = searchProviders;
    }

    public List<ApiProvider> searchProviders() {
        return searchProviders;
    }
}
