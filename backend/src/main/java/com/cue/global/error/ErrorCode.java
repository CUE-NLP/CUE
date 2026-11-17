package com.cue.global.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 서비스 전체 에러 코드. 새 에러는 도메인 구분 주석 아래에 추가한다.
 * 코드 형식: {도메인 영문자 1자}{3자리 번호} (G: 공통, A: 인증, U: 사용자, I: 아이디어, S: 외부 자료 수집, E: 평가). 코드는 전체에서 유일해야 한다.
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // ===== Global (공통, G-xxx) =====
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "G001", "예상치 못한 서버 오류입니다. 관리자에게 문의해주세요."),
    EXTERNAL_SERVER_ERROR(HttpStatus.BAD_GATEWAY, "G002", "외부 서비스와의 통신 중 오류가 발생했습니다."),
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "G003", "잘못된 요청입니다."),
    INVALID_INPUT_VALUE(HttpStatus.BAD_REQUEST, "G004", "잘못된 입력입니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "G005", "허용되지 않은 HTTP 메소드입니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "G006", "요청하신 리소스를 찾을 수 없습니다."),

    // ===== Auth (인증/인가, A-xxx) =====
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "A001", "인증이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "A002", "접근 권한이 없습니다."),
    JWT_MALFORMED(HttpStatus.UNAUTHORIZED, "A003", "잘못된 JWT 토큰입니다."),
    JWT_EXPIRED(HttpStatus.UNAUTHORIZED, "A004", "만료된 JWT 토큰입니다."),
    JWT_INVALID(HttpStatus.UNAUTHORIZED, "A005", "유효하지 않은 JWT 토큰입니다."),
    JWT_MISSING(HttpStatus.UNAUTHORIZED, "A006", "JWT 토큰이 없습니다."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "A007", "이메일 또는 비밀번호가 올바르지 않습니다."),

    // ===== User (사용자, U-xxx) =====
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "U001", "이미 존재하는 이메일입니다."),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "U002", "일치하는 회원 정보가 존재하지 않습니다."),

    // ===== Idea (아이디어, I-xxx) =====
    IDEA_NOT_FOUND(HttpStatus.NOT_FOUND, "I001", "아이디어를 찾을 수 없습니다."),
    IDEA_ACCESS_DENIED(HttpStatus.FORBIDDEN, "I002", "본인의 아이디어만 조회할 수 있습니다."),
    INVALID_WEIGHT(HttpStatus.BAD_REQUEST, "I003", "가중치는 5개 항목을 모두 0~100 정수로 입력하고 합이 100이어야 합니다."),
    IDEA_NOT_ACTIVE(HttpStatus.CONFLICT, "I004", "구조화 항목이 모두 채워지지 않은 보류 아이디어는 평가할 수 없습니다."),
    IDEA_EVALUATING(HttpStatus.CONFLICT, "I005", "평가가 진행 중인 아이디어는 수정할 수 없습니다. 평가가 끝난 뒤 다시 시도해 주세요."),

    // ===== Source (외부 자료 수집, S-xxx) — 외부 API 통신 오류 자체는 G002 =====
    SOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "S001", "수집 설정(source)을 찾을 수 없습니다."),
    SOURCE_PROVIDER_NOT_SUPPORTED(HttpStatus.NOT_IMPLEMENTED, "S002", "아직 지원하지 않는 외부 API입니다."),
    SOURCE_URL_BLOCKED(HttpStatus.BAD_REQUEST, "S003", "허용되지 않는 URL입니다. (http/https 공개 주소만 가져올 수 있습니다)"),

    // ===== Evaluation (재평가, E-xxx) — AI 호출 자체의 통신 오류는 G002 =====
    AI_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "E001", "AI 평가를 사용할 수 없습니다. (Gemini 비활성 또는 API 키 없음)"),
    AI_RESPONSE_INVALID(HttpStatus.BAD_GATEWAY, "E002", "AI 평가 응답을 해석할 수 없습니다."),
    EVALUATION_QUEUE_FULL(HttpStatus.SERVICE_UNAVAILABLE, "E004", "평가 요청이 많아 대기열이 가득 찼습니다. 잠시 후 다시 시도해주세요."),
    EVALUATION_INTERRUPTED(HttpStatus.SERVICE_UNAVAILABLE, "E005", "서버가 재시작되어 평가가 중단되었습니다. 다시 시도해주세요.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
