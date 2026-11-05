package com.cue.global.security;

import com.cue.global.error.ErrorCode;
import com.cue.global.response.CommonResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 시큐리티 필터 단계(인증 실패·권한 부족)의 에러를 CommonResponse 형식으로 응답한다. */
@Component
@RequiredArgsConstructor
public class SecurityErrorWriter implements AuthenticationEntryPoint, AccessDeniedHandler {
    private final ObjectMapper mapper;

    @Override
    public void commence(HttpServletRequest req, HttpServletResponse res, AuthenticationException e) throws IOException {
        write(res, resolveAuthError(req, e));
    }

    @Override
    public void handle(HttpServletRequest req, HttpServletResponse res, AccessDeniedException e) throws IOException {
        write(res, ErrorCode.FORBIDDEN);
    }

    /** 토큰 상태에 따라 JWT 에러를 구분해 프론트가 "만료 → 재로그인" 등으로 분기할 수 있게 한다. */
    private ErrorCode resolveAuthError(HttpServletRequest req, AuthenticationException e) {
        if (req.getHeader("Authorization") == null) {
            return ErrorCode.JWT_MISSING;
        }
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        if (msg.contains("expired")) {
            return ErrorCode.JWT_EXPIRED;
        }
        if (msg.contains("serialization") || msg.contains("malformed") || msg.contains("compact")) {
            return ErrorCode.JWT_MALFORMED;
        }
        if (msg.contains("jwt") || msg.contains("token")) {
            return ErrorCode.JWT_INVALID;
        }
        return ErrorCode.UNAUTHORIZED;
    }

    private void write(HttpServletResponse res, ErrorCode code) throws IOException {
        res.setStatus(code.getStatus().value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        res.getWriter().write(mapper.writeValueAsString(CommonResponse.onFailure(code)));
    }
}
