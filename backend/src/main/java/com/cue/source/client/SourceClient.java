package com.cue.source.client;

import com.cue.source.dto.SourceItem;
import com.cue.source.entity.ApiProvider;
import java.util.List;

/**
 * 외부 자료 API 클라이언트. provider 하나당 구현체 하나이며 빈으로 등록하면 {@code SourceCheckService}가 자동으로 찾는다.
 * 새 provider(예: HACKER_NEWS)는 이 인터페이스를 구현해 빈만 추가하면 된다.
 * 외부 오류는 {@code CustomException(ErrorCode.EXTERNAL_SERVER_ERROR)}로 던진다.
 */
public interface SourceClient {
    ApiProvider provider();

    /** 키워드로 검색한 상위 결과를 안정적인 순서로 반환한다 (같은 자료면 같은 순서여야 해시가 흔들리지 않는다). */
    List<SourceItem> search(String keyword);

    /**
     * 키워드 없이 "지금 뜨는" 상위 항목을 조회한다 (글로벌 트렌드용). 기간·개수는 호출하는 쪽이 넘긴 설정을 따른다.
     * 지원하지 않는 provider는 {@code CustomException(SOURCE_PROVIDER_NOT_SUPPORTED)}.
     */
    default List<SourceItem> trending(SearchSettings settings) {
        throw new com.cue.global.error.CustomException(com.cue.global.error.ErrorCode.SOURCE_PROVIDER_NOT_SUPPORTED);
    }

    /**
     * 항목의 원문(README·질문/답변 본문·기사 본문 등)을 텍스트로 읽어온다. 길이 상한은 호출하는 쪽이 적용한다.
     * 실패하면 {@code CustomException}을 던지고 호출하는 쪽이 그 항목을 건너뛴다. 기본 구현은 원문 없음(빈 문자열).
     */
    default String fetchContent(SourceItem item) {
        return "";
    }
}
