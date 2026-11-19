package com.cue.auth.repository;

import com.cue.auth.entity.RefreshToken;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** 사용자의 아직 유효한 토큰을 모두 폐기한다 (재사용 탐지 시). */
    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :now where t.userId = :userId and t.revokedAt is null")
    int revokeAllByUserId(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    /**
     * 만료된 토큰 행을 지운다 (폐기 여부와 무관). 폐기됐지만 아직 만료 전인 행은 재사용 탐지(A010)에 필요하므로 남긴다.
     * 만료 후에는 재사용해도 어차피 거부되므로(A008/A009) 지워도 안전하다.
     */
    @Modifying
    @Query("delete from RefreshToken t where t.expiresAt < :now")
    int deleteExpired(@Param("now") LocalDateTime now);
}
