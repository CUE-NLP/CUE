package com.cue.user.dto;

import com.cue.user.entity.User;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "사용자 정보")
public record UserResponse(
        @Schema(description = "사용자 ID", example = "1") Long id,
        @Schema(description = "닉네임", example = "큐") String nickname,
        @Schema(description = "이메일", example = "cue@example.com") String email) {
    public static UserResponse from(User u) {
        return new UserResponse(u.getId(), u.getNickname(), u.getEmail());
    }
}
