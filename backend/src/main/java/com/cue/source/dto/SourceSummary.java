package com.cue.source.dto;

/**
 * 변경이 감지된 source 하나를 읽고 요약한 결과.
 *
 * @param result  항목 요약으로 {@code summary}를 바꾼 조회 결과 (요약이 하나도 없으면 원래 항목 그대로). 재평가의 changedItems가 된다
 * @param summary 소스 단위 최종 요약 (만들지 못했으면 null)
 */
public record SourceSummary(SourceCheckResult result, String summary) {}
