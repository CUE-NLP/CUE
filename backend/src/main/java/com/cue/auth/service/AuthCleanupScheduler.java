package com.cue.auth.service;

import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 만료된 refresh_tokens 행을 매일 삭제한다.
 * 주기는 {@code cue.auth.cleanup.cron}(기본 매일 04:00). 값을 {@code -} 로 두면 비활성화된다 (테스트).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthCleanupScheduler {
    private final AuthService authService;

    @Scheduled(cron = "${cue.auth.cleanup.cron:0 0 4 * * *}")
    public void run() {
        int deleted = authService.cleanupExpired(LocalDateTime.now());
        log.info("[AUTH] 만료된 토큰 행 {}건 삭제", deleted);
    }
}
