package com.cue.source.service;

import com.cue.global.error.CustomException;
import com.cue.global.error.ErrorCode;
import com.cue.source.client.SourceClient;
import com.cue.source.dto.SourceCheckResult;
import com.cue.source.dto.SourceItem;
import com.cue.source.entity.ApiProvider;
import com.cue.source.entity.Source;
import com.cue.source.repository.SourceRepository;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * source(provider × keyword)를 외부 API로 조회하고 content_hash로 변경을 감지한다.
 * 외부 HTTP 호출 동안 DB 트랜잭션을 잡지 않으려고 클래스에 @Transactional을 쓰지 않는다 (저장은 repository 호출 단위).
 */
@Slf4j
@Service
public class SourceCheckService {
    private final SourceRepository sourceRepository;
    private final Map<ApiProvider, SourceClient> clients = new EnumMap<>(ApiProvider.class);

    public SourceCheckService(SourceRepository sourceRepository, List<SourceClient> clientList) {
        this.sourceRepository = sourceRepository;
        clientList.forEach(c -> clients.put(c.provider(), c));
    }

    /** source 하나를 조회한다. 변경이면 결과의 items는 이번 조회의 상위 목록 전체다. 외부 오류·미지원 provider는 예외로 던진다 (해시·시각은 그대로). */
    public SourceCheckResult check(Long sourceId) {
        Source source = sourceRepository.findById(sourceId)
                .orElseThrow(() -> new CustomException(ErrorCode.SOURCE_NOT_FOUND));
        return check(source);
    }

    /** 아이디어의 모든 source를 조회한다. 한 source가 실패해도 나머지는 계속하고 실패는 FAILED 결과로 담는다. */
    public List<SourceCheckResult> checkAll(Long ideaId) {
        return sourceRepository.findByIdeaId(ideaId).stream().map(this::checkSafely).toList();
    }

    /** 평가가 실패해 변경을 반영하지 못했을 때, 해시·시각을 조회 이전 값으로 되돌린다 (다음 조회에서 다시 변경으로 감지). */
    public void restoreHash(Long sourceId, String contentHash, java.time.LocalDateTime checkedAt) {
        sourceRepository.findById(sourceId).ifPresent(s -> {
            s.restore(contentHash, checkedAt);
            sourceRepository.save(s);
        });
    }

    private SourceCheckResult checkSafely(Source source) {
        try {
            return check(source);
        } catch (CustomException e) {
            log.warn("source 조회 실패 id={} provider={} code={}", source.getId(), source.getProvider(), e.getErrorCode().getCode());
            return SourceCheckResult.failed(source.getId(), source.getProvider(), source.getKeyword(),
                    e.getErrorCode().getMessage());
        } catch (RuntimeException e) {
            log.warn("source 조회 중 예기치 못한 오류 id={}", source.getId(), e);
            return SourceCheckResult.failed(source.getId(), source.getProvider(), source.getKeyword(),
                    ErrorCode.EXTERNAL_SERVER_ERROR.getMessage());
        }
    }

    private SourceCheckResult check(Source source) {
        SourceClient client = clients.get(source.getProvider());
        if (client == null) {
            throw new CustomException(ErrorCode.SOURCE_PROVIDER_NOT_SUPPORTED);
        }
        List<SourceItem> items = client.search(source.getKeyword());
        // 변경 = 상위 항목의 해시가 달라짐 (첫 조회는 기준만 기록). 변경이면 상위 목록 전체를 돌려주고 호출하는 쪽이 다시 읽는다.
        boolean changed = source.updateHash(SourceHasher.hash(items));
        sourceRepository.save(source);
        return changed
                ? SourceCheckResult.changed(source.getId(), source.getProvider(), source.getKeyword(), items, source.getCheckedAt())
                : SourceCheckResult.unchanged(source.getId(), source.getProvider(), source.getKeyword(), source.getCheckedAt());
    }
}
