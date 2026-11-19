package com.cue.trend.dto;

import com.cue.source.dto.SourceCheckResult;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 그날의 글로벌 트렌드를 트랜잭션 밖에서 쓰려고 값만 복사한 것.
 *
 * @param sources provider별 상위 항목 (항목의 {@code summary}는 항목 요약, sourceId·keyword는 없다). TREND 평가의 근거 URL 검증에도 쓴다
 */
public record TrendSnapshot(Long id, String summary, LocalDateTime createdAt, List<SourceCheckResult> sources) {}
