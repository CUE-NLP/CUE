package com.cue.auth.service;

import com.cue.auth.dto.LoginRequest;
import com.cue.auth.dto.TokenResponse;
import com.cue.auth.entity.RefreshToken;
import com.cue.auth.repository.RefreshTokenRepository;
import com.cue.global.error.CustomException;
import com.cue.global.error.ErrorCode;
import com.cue.global.security.JwtProperties;
import com.cue.global.security.JwtTokenProvider;
import com.cue.global.security.UserPrincipal;
import com.cue.user.entity.User;
import com.cue.user.repository.UserRepository;
import com.cue.user.service.UserService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder encoder;
    private final JwtTokenProvider tokenProvider;
    private final JwtProperties jwtProperties;

    @Transactional
    public TokenResponse login(LoginRequest req) {
        User user = users.findByEmail(UserService.normalizeEmail(req.email()))
                .filter(u -> encoder.matches(req.password(), u.getPasswordHash()))
                .orElseThrow(() -> new CustomException(ErrorCode.INVALID_CREDENTIALS));
        return issueTokens(user);
    }

    /**
     * 리프레시 토큰 회전: 사용된 토큰은 폐기하고 새 액세스·리프레시 토큰을 발급한다.
     * 이미 폐기된 토큰이 다시 오면(탈취 의심) 그 사용자의 모든 토큰을 폐기한다.
     * 폐기를 커밋해야 하므로 CustomException에도 롤백하지 않는다.
     */
    @Transactional(noRollbackFor = CustomException.class)
    public TokenResponse refresh(String rawRefreshToken) {
        LocalDateTime now = LocalDateTime.now();
        RefreshToken stored = refreshTokens.findByTokenHash(hash(rawRefreshToken))
                .orElseThrow(() -> new CustomException(ErrorCode.REFRESH_TOKEN_INVALID));
        if (stored.isRevoked()) {
            refreshTokens.revokeAllByUserId(stored.getUserId(), now);
            throw new CustomException(ErrorCode.REFRESH_TOKEN_REUSED);
        }
        if (stored.isExpired(now)) {
            throw new CustomException(ErrorCode.REFRESH_TOKEN_EXPIRED);
        }
        User user = users.findById(stored.getUserId())
                .orElseThrow(() -> new CustomException(ErrorCode.REFRESH_TOKEN_INVALID));
        stored.revoke(now);
        return issueTokens(user);
    }

    /**
     * 로그아웃: 요청 본문의 리프레시 토큰(현재 기기)을 폐기한다. 액세스 토큰은 서버가 기억하지 않는 JWT라 폐기하지 않고
     * 만료(짧은 수명)까지 유효하다. 다른 기기의 토큰은 건드리지 않는다.
     * 리프레시 토큰이 없거나 다른 사용자 것이거나 이미 폐기됐어도 성공(멱등)이다.
     */
    @Transactional
    public void logout(Long userId, String rawRefreshToken) {
        refreshTokens.findByTokenHash(hash(rawRefreshToken))
                .filter(t -> t.getUserId().equals(userId))
                .ifPresent(t -> t.revoke(LocalDateTime.now()));
    }

    /** 만료된 리프레시 토큰 행을 삭제한다. 삭제한 행 수를 반환한다. */
    @Transactional
    public int cleanupExpired(LocalDateTime now) {
        return refreshTokens.deleteExpired(now);
    }

    private TokenResponse issueTokens(User user) {
        String access = tokenProvider.issue(new UserPrincipal(user.getId(), user.getEmail(), user.getNickname()));
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        refreshTokens.save(new RefreshToken(user.getId(), hash(refresh),
                LocalDateTime.now().plusDays(jwtProperties.refreshTokenDays())));
        return TokenResponse.bearer(access, refresh);
    }

    private static String hash(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
