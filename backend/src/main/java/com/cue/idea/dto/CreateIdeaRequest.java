package com.cue.idea.dto;

import com.cue.idea.entity.IdeaType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * 프론트가 구조화한 결과를 저장하는 요청. 작성자는 인증 정보에서 가져온다.
 * 4개 항목은 비어 있어도 되며, 하나라도 비면 서버가 보류(HOLD)로 저장한다.
 */
@Schema(description = "아이디어 저장 요청. 프론트가 AI로 구조화하고 사용자가 확인한 결과를 저장만 한다. "
        + "4개 항목이 모두 채워지면 ACTIVE(평가 대상), 하나라도 비면 HOLD(보류)로 서버가 정한다. 사용자 ID·상태는 보내지 않는다.")
public record CreateIdeaRequest(
        @Schema(description = "아이디어 종류. 생략하면 IT (현재 IT만 지원, ACTIVE 등록 시 AI가 다시 판단)", example = "IT", defaultValue = "IT")
        IdeaType type,
        @Schema(description = "문제: 해결하려는 문제 (최대 5000자, 비우면 HOLD)", example = "개인이 떠올린 아이디어가 시장 변화에 맞게 재평가되지 않고 잊힌다.")
        @Size(max = 5000) String problem,
        @Schema(description = "타깃: 대상 사용자 (최대 5000자, 비우면 HOLD)", example = "사이드 프로젝트를 구상하는 개발자와 예비 창업자")
        @Size(max = 5000) String target,
        @Schema(description = "해결: 해결 방식 (최대 5000자, 비우면 HOLD)", example = "아이디어를 구조화해 저장하고 외부 자료 변화로 매일 점수를 재평가한다.")
        @Size(max = 5000) String solution,
        @Schema(description = "기대효과 (최대 5000자, 비우면 HOLD)", example = "아이디어의 경쟁력 변화를 한눈에 보고 우선순위를 정할 수 있다.")
        @Size(max = 5000) String expectation) {}
