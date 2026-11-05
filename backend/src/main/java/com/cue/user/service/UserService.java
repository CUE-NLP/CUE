package com.cue.user.service;

import com.cue.global.error.CustomException;
import com.cue.global.error.ErrorCode;
import com.cue.user.dto.SignUpRequest;
import com.cue.user.dto.UserResponse;
import com.cue.user.repository.UserRepository;
import com.cue.user.entity.User;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {
    private final UserRepository users;
    private final PasswordEncoder encoder;

    public UserService(UserRepository users, PasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
    }

    @Transactional
    public UserResponse signUp(SignUpRequest req) {
        if (users.existsByEmail(req.email())) {
            throw new CustomException(ErrorCode.EMAIL_DUPLICATED);
        }
        User saved = users.save(new User(req.nickname(), req.email(), encoder.encode(req.password())));
        return UserResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public UserResponse get(Long userId) {
        return users.findById(userId)
                .map(UserResponse::from)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
    }
}
