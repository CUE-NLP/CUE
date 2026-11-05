package com.cue.user.dto;

import com.cue.user.entity.User;

public record UserResponse(Long id, String nickname, String email) {
    public static UserResponse from(User u) {
        return new UserResponse(u.getId(), u.getNickname(), u.getEmail());
    }
}
