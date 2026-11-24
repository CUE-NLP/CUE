package com.cue.source.dto;

import com.cue.source.entity.ApiProvider;
import java.time.LocalDateTime;
import java.util.List;

/**
 * source 하나를 조회한 결과.
 * - CHANGED: 이전 해시와 달라졌거나 <b>처음 조회</b>(해시 기록). {@code items}에 이번 조회 결과가 담긴다. 처음 조회의 항목이 첫 평가의 근거가 된다.
 * - UNCHANGED: 해시가 같아 변화 없음. {@code items}는 비어 있다.
 * - FAILED: 외부 API 오류. 해시·조회 시각은 갱신하지 않는다.
 */
public record SourceCheckResult(
        Long sourceId,
        ApiProvider provider,
        String keyword,
        Status status,
        List<SourceItem> items,
        LocalDateTime checkedAt,
        String errorMessage) {

    public enum Status { CHANGED, UNCHANGED, FAILED }

    public boolean changed() {
        return status == Status.CHANGED;
    }

    public static SourceCheckResult changed(Long id, ApiProvider p, String k, List<SourceItem> items, LocalDateTime at) {
        return new SourceCheckResult(id, p, k, Status.CHANGED, items, at, null);
    }

    public static SourceCheckResult unchanged(Long id, ApiProvider p, String k, LocalDateTime at) {
        return new SourceCheckResult(id, p, k, Status.UNCHANGED, List.of(), at, null);
    }

    public static SourceCheckResult failed(Long id, ApiProvider p, String k, String message) {
        return new SourceCheckResult(id, p, k, Status.FAILED, List.of(), null, message);
    }
}
