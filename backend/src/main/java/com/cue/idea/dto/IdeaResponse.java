package com.cue.idea.dto;

import com.cue.idea.entity.IdeaStatus;
import com.cue.idea.entity.ItCategory;
import com.cue.idea.entity.KeywordOrigin;
import com.cue.idea.entity.IdeaType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * type: 대분류(현재 IT 고정), itCategory: IT 세부 종류(검색 API 조합 결정).
 * keywords: 검색 키워드(없으면 빈 배열), keywordOrigin: 키워드 출처 USER/AI(키워드가 없으면 null).
 * lastDifference: 가장 최근 평가의 총점 변화량(없으면 null). hasNewChange: 아직 확인하지 않은 새 평가가 있는지.
 */
public record IdeaResponse(
        Long id, IdeaType type, ItCategory itCategory, IdeaStatus status, String problem, String target, String solution, String expectation,
        BigDecimal totalScore, BigDecimal lastDifference, LocalDateTime lastEvaluatedAt, boolean hasNewChange,
        LocalDateTime createdAt, List<ScoreResponse> scores, List<String> keywords, KeywordOrigin keywordOrigin) {}
