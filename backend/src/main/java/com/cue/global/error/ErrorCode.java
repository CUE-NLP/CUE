package com.cue.global.error;

import org.springframework.http.HttpStatus;

/**
 * 서비스 전체 에러 코드. 새 에러는 도메인 구분 주석 아래에 추가한다.
 * 코드 형식: {도메인 약어}-{3자리 번호} (예: USER-001)
 */
public enum ErrorCode {

    // ===== COMMON =====
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "COMMON-001", "요청 값이 올바르지 않습니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "COMMON-002", "인증이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "COMMON-003", "접근 권한이 없습니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "COMMON-004", "요청한 리소스를 찾을 수 없습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "COMMON-005", "지원하지 않는 요청 방식입니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON-999", "서버 내부 오류가 발생했습니다."),

    // ===== AUTH =====
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "AUTH-001", "이메일 또는 비밀번호가 올바르지 않습니다."),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH-002", "유효하지 않거나 만료된 토큰입니다."),

    // ===== USER =====
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "USER-001", "사용자를 찾을 수 없습니다."),
    EMAIL_DUPLICATED(HttpStatus.CONFLICT, "USER-002", "이미 사용 중인 이메일입니다."),

    // ===== IDEA =====
    IDEA_NOT_FOUND(HttpStatus.NOT_FOUND, "IDEA-001", "아이디어를 찾을 수 없습니다."),
    IDEA_ACCESS_DENIED(HttpStatus.FORBIDDEN, "IDEA-002", "본인의 아이디어만 조회할 수 있습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;

    ErrorCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
    public String getMessage() { return message; }
}
