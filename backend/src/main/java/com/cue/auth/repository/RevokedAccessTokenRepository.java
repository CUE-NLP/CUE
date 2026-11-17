package com.cue.auth.repository;

import com.cue.auth.entity.RevokedAccessToken;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RevokedAccessTokenRepository extends JpaRepository<RevokedAccessToken, String> {
    /** 이미 만료된 토큰은 어차피 거부되므로 폐기 기록도 지운다. */
    @Modifying
    @Query("delete from RevokedAccessToken t where t.expiresAt < :now")
    int deleteExpired(@Param("now") LocalDateTime now);
}
