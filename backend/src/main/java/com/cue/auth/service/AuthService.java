package com.cue.auth.service;

import com.cue.auth.dto.LoginRequest;
import com.cue.auth.dto.TokenResponse;
import com.cue.global.error.CustomException;
import com.cue.global.error.ErrorCode;
import com.cue.global.security.JwtTokenProvider;
import com.cue.global.security.UserPrincipal;
import com.cue.user.entity.User;
import com.cue.user.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtTokenProvider tokenProvider;

    @Transactional(readOnly = true)
    public TokenResponse login(LoginRequest req) {
        User user = users.findByEmail(req.email())
                .filter(u -> encoder.matches(req.password(), u.getPasswordHash()))
                .orElseThrow(() -> new CustomException(ErrorCode.INVALID_CREDENTIALS));
        String token = tokenProvider.issue(new UserPrincipal(user.getId(), user.getEmail(), user.getNickname()));
        return TokenResponse.bearer(token);
    }
}
