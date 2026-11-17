package com.cue.user.service;

import com.cue.global.error.CustomException;
import com.cue.global.error.ErrorCode;
import com.cue.user.dto.SignUpRequest;
import com.cue.user.dto.UserResponse;
import com.cue.user.repository.UserRepository;
import com.cue.user.entity.User;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class UserService {
    private final UserRepository users;
    private final PasswordEncoder encoder;

    @Transactional
    public UserResponse signUp(SignUpRequest req) {
        String email = normalizeEmail(req.email());
        if (users.existsByEmail(email)) {
            throw new CustomException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }
        try {
            User saved = users.saveAndFlush(new User(req.nickname(), email, encoder.encode(req.password())));
            return UserResponse.from(saved);
        } catch (DataIntegrityViolationException e) {
            // existsByEmail 확인과 저장 사이에 같은 이메일이 먼저 가입한 경우(유니크 제약)도 U001로 응답한다.
            throw new CustomException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }
    }

    /** 이메일은 앞뒤 공백을 지우고 소문자로 정규화해 저장·조회한다 (대소문자만 다른 중복 가입 방지). */
    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(java.util.Locale.ROOT);
    }

    @Transactional(readOnly = true)
    public UserResponse get(Long userId) {
        return users.findById(userId)
                .map(UserResponse::from)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
    }
}
