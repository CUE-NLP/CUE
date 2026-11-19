package com.cue.auth.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 로그아웃으로 폐기된 액세스 토큰(jti). 토큰의 원래 만료 시각까지만 보관한다. */
@Entity
@Table(name = "revoked_access_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RevokedAccessToken {
    @Id
    @Column(length = 36)
    private String jti;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at", nullable = false, updatable = false)
    private LocalDateTime revokedAt = LocalDateTime.now();

    public RevokedAccessToken(String jti, Long userId, LocalDateTime expiresAt) {
        this.jti = jti;
        this.userId = userId;
        this.expiresAt = expiresAt;
    }
}
