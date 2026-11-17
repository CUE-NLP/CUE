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
        if (users.existsByEmail(req.email())) {
            throw new CustomException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }
        try {
            User saved = users.saveAndFlush(new User(req.nickname(), req.email(), encoder.encode(req.password())));
            return UserResponse.from(saved);
        } catch (DataIntegrityViolationException e) {
            // existsByEmail 확인과 저장 사이에 같은 이메일이 먼저 가입한 경우(유니크 제약)도 U001로 응답한다.
            throw new CustomException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }
    }

    @Transactional(readOnly = true)
    public UserResponse get(Long userId) {
        return users.findById(userId)
                .map(UserResponse::from)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
    }
}
