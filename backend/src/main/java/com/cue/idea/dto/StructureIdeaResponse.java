package com.cue.idea.dto;

import com.cue.idea.entity.ItCategory;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "구조화 결과. 저장은 하지 않는다. 사용자가 확인·수정한 뒤 `POST /api/ideas`로 저장한다. "
        + "원문에 근거가 없는 항목은 AI가 채우지 않고 빈 문자열로 돌려주며, 하나라도 비면 `complete=false`다(저장하면 HOLD).")
public record StructureIdeaResponse(
        @Schema(description = "원문을 바탕으로 제안한 제목 (최대 60자, 만들 수 없으면 빈 문자열)",
                example = "아이디어 경쟁력 추적기")
        String title,
        @Schema(description = "타깃: 누구를 위한 아이디어인가 (근거가 없으면 빈 문자열)", example = "혼자 창업을 준비하는 개발자")
        String target,
        @Schema(description = "문제: 타깃이 겪는 문제 (근거가 없으면 빈 문자열)", example = "사이드 프로젝트 아이디어가 정리되지 않고 잊힌다.")
        String problem,
        @Schema(description = "해결: 문제를 푸는 방식 (근거가 없으면 빈 문자열)", example = "아이디어를 구조화해 저장하고 시장 변화로 매일 점수를 다시 매긴다.")
        String solution,
        @Schema(description = "기대효과: 해결 후 기대하는 변화 (근거가 없으면 빈 문자열)", example = "아이디어의 우선순위를 근거와 함께 정할 수 있다.")
        String expectation,
        @Schema(description = "4개 항목이 모두 채워졌는지. false면 이대로 저장했을 때 HOLD(평가 대상 아님)다", example = "true")
        boolean complete,
        @Schema(description = "비어 있는 항목 이름 목록 (TARGET, PROBLEM, SOLUTION, EXPECTATION). 프론트가 \"해결 방법이 빠졌어요\"처럼 안내할 때 쓴다", example = "[]")
        List<String> missingFields,
        @Schema(description = "AI가 판단한 IT 세부 종류 (참고용). `complete=true`일 때만 있고, 내용을 고치지 않고 저장하면 서버가 이 결과를 다시 쓴다", nullable = true)
        ItCategory itCategory,
        @Schema(description = "AI가 뽑은 영어 검색 키워드 후보 (참고용, 최대 2개). `complete=false`면 빈 배열. "
                + "이 값을 `keywords`로 그대로 보내 저장하면 출처가 USER가 되므로, 사용자가 직접 정할 때만 보내고 아니면 생략한다", example = "[\"idea validation\", \"startup idea scoring\"]")
        List<String> keywords) {}
