package com.cue.idea.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "즐겨찾기 변경 요청")
public record UpdateFavoriteRequest(
        @Schema(description = "true=즐겨찾기, false=해제", example = "true") @NotNull Boolean favorite) {}
