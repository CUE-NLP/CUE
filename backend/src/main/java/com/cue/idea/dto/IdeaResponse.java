package com.cue.idea.dto;

import com.cue.idea.entity.IdeaStatus;
import com.cue.idea.entity.ItCategory;
import com.cue.idea.entity.KeywordOrigin;
import com.cue.idea.entity.IdeaType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * type: 대분류(현재 IT 고정), itCategory: IT 세부 종류(검색 API 조합 결정).
 * keywords: 검색 키워드(없으면 빈 배열), keywordOrigin: 키워드 출처 USER/AI(키워드가 없으면 null).
 * lastDifference: 가장 최근 평가의 총점 변화량(없으면 null). hasNewChange: 아직 확인하지 않은 새 평가가 있는지.
 */
@Schema(description = "아이디어 상세/리더보드 항목")
public record IdeaResponse(
        @Schema(description = "아이디어 ID", example = "1") Long id,
        @Schema(description = "대분류. 현재 항상 IT", example = "IT") IdeaType type,
        @Schema(description = "IT 세부 종류. 항상 AI가 판단하며 외부 자료 검색 API 조합을 정한다. ACTIVE 등록·수정 시 다시 판단하며, 판단 전(HOLD)에는 기본값 DEV_TOOLS_INFRA. "
                + "AI_ML=AI·머신러닝, DEV_TOOLS_INFRA=개발자 도구·인프라, WEB_APP_SERVICE=웹·앱 서비스, DATA_ANALYTICS=데이터·분석",
                example = "DEV_TOOLS_INFRA") ItCategory itCategory,
        @Schema(description = "상태. ACTIVE=평가 대상, HOLD=구조화 항목이 비어 보류", example = "ACTIVE") IdeaStatus status,
        @Schema(description = "문제 (비어 있으면 null)", example = "아이디어가 재평가되지 않고 잊힌다.") String problem,
        @Schema(description = "타깃 (비어 있으면 null)", example = "사이드 프로젝트를 구상하는 개발자") String target,
        @Schema(description = "해결 (비어 있으면 null)", example = "외부 자료 변화로 매일 점수를 재평가한다.") String solution,
        @Schema(description = "기대효과 (비어 있으면 null)", example = "경쟁력 변화를 보고 우선순위를 정한다.") String expectation,
        @Schema(description = "총점 = Σ(항목 점수 × 가중치), 소수 2자리. 첫 평가 전에는 0", example = "62.50") BigDecimal totalScore,
        @Schema(description = "가장 최근 평가의 총점 변화량. 평가 이력이 없으면 null", example = "1.25", nullable = true) BigDecimal lastDifference,
        @Schema(description = "가장 최근 평가 시각. 없으면 null", example = "2026-11-05T15:00:00", nullable = true) LocalDateTime lastEvaluatedAt,
        @Schema(description = "확인하지 않은 새 평가가 있는지. POST /api/ideas/{id}/check 로 해제", example = "true") boolean hasNewChange,
        @Schema(description = "등록 시각", example = "2026-11-01T10:30:00") LocalDateTime createdAt,
        @Schema(description = "평가 항목 4개의 점수·가중치") List<ScoreResponse> scores,
        @Schema(description = "외부 자료 검색 키워드. 없으면 빈 배열", example = "[\"idea scoring\", \"startup validation\"]") List<String> keywords,
        @Schema(description = "키워드 출처. USER=사용자가 직접 입력(AI 추출보다 우선), AI=AI가 추출. 키워드가 없으면 null",
                example = "AI", nullable = true) KeywordOrigin keywordOrigin) {}
