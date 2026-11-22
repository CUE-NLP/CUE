package com.cue.idea.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "아이디어 구조화 요청. 사용자가 자유롭게 쓴(또는 음성 인식으로 받은) 원문을 보낸다. 서버는 원문을 저장하지 않는다.")
public record StructureIdeaRequest(
        @Schema(description = "아이디어 원문 (최대 5000자, 공백만 있으면 안 됨)",
                example = "개발자들이 사이드 프로젝트 아이디어를 잊어버리는 게 문제라서, 아이디어를 구조화해 저장하고 매일 시장 변화로 점수를 다시 매겨주는 서비스를 만들고 싶다. 혼자 창업을 준비하는 개발자가 우선순위를 정하는 데 도움이 될 것 같다.")
        @NotBlank @Size(max = 5000) String text) {}
