package com.cue.idea.dto;

import com.cue.idea.entity.Idea;
import com.cue.idea.entity.IdeaType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 프론트가 구조화한 결과를 저장하는 요청. 작성자는 인증 정보에서 가져온다.
 * 4개 항목은 비어 있어도 되며, 하나라도 비면 서버가 보류(HOLD)로 저장한다.
 * keywords(선택): 사용자가 직접 정한 검색 키워드(최대 2개, 각 1~50자). 보내면 AI 키워드 추출 대신 이 값을 쓴다.
 */
@Schema(description = "아이디어 저장 요청. 프론트가 AI로 구조화하고 사용자가 확인한 결과를 저장만 한다. "
        + "4개 항목이 모두 채워지면 ACTIVE(평가 대상), 하나라도 비면 HOLD(보류)로 서버가 정한다. 사용자 ID·상태는 보내지 않는다.")
public record CreateIdeaRequest(
        @Schema(description = "아이디어 대분류. 항상 IT이므로 생략해도 된다 (IT 세부 종류 itCategory는 AI가 판단해 응답에 담긴다)",
                example = "IT", defaultValue = "IT")
        IdeaType type,
        @Schema(description = "표시용 제목 (선택, 최대 60자)", example = "아이디어 경쟁력 추적기", nullable = true)
        @Size(max = Idea.TITLE_MAX_LENGTH) @Pattern(regexp = "(?U)[^\\r\\n]*\\S[^\\r\\n]*") String title,
        @Schema(description = "문제: 해결하려는 문제 (최대 5000자, 비우면 HOLD)", example = "개인이 떠올린 아이디어가 시장 변화에 맞게 재평가되지 않고 잊힌다.")
        @Size(max = 5000) String problem,
        @Schema(description = "타깃: 대상 사용자 (최대 5000자, 비우면 HOLD)", example = "사이드 프로젝트를 구상하는 개발자와 예비 창업자")
        @Size(max = 5000) String target,
        @Schema(description = "해결: 해결 방식 (최대 5000자, 비우면 HOLD)", example = "아이디어를 구조화해 저장하고 외부 자료 변화로 매일 점수를 재평가한다.")
        @Size(max = 5000) String solution,
        @Schema(description = "기대효과 (최대 5000자, 비우면 HOLD)", example = "아이디어의 경쟁력 변화를 한눈에 보고 우선순위를 정할 수 있다.")
        @Size(max = 5000) String expectation,
        @Schema(description = "사용자가 직접 정한 외부 자료 검색 키워드 (선택, 최대 2개, 각 1~50자, 빈 문자열 불가). "
                + "보내면 AI 키워드 추출 없이 이 값을 쓰고(keywordOrigin=USER), 생략하거나 비어 있을 때만 AI가 추출한다(keywordOrigin=AI)",
                example = "[\"idea scoring\", \"startup validation\"]", nullable = true)
        @Size(max = 2) List<@NotBlank @Size(max = 50) String> keywords) {}
