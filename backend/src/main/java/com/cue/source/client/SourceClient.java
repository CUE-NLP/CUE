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
}
