package com.cue.source.dto;

/**
 * 변경이 감지된 source 하나를 읽고 요약한 결과.
 *
 * @param result   항목 요약으로 {@code summary}를 바꾼 조회 결과 (요약이 하나도 없으면 원래 항목 그대로). 재평가의 changedItems가 된다
 * @param summary  소스 단위 최종 요약 (만들지 못했으면 null)
 * @param degraded 항목 일괄 요약 호출이 실패해 원래 조회한 항목으로 대체했는지. true면 평가가 끝난 뒤 source 해시를 되돌려 다음 실행에서 다시 시도한다
 */
public record SourceSummary(SourceCheckResult result, String summary, boolean degraded) {
    public SourceSummary(SourceCheckResult result, String summary) {
        this(result, summary, false);
    }
}
