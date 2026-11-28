package com.cue.global.error;

/**
 * 비즈니스 로직에서 던지는 예외. ErrorCode로 응답 상태·코드·메시지가 결정된다. 에러가 난 위치나 외부 API 실패 원인이 있으면
 * {@link ErrorDetail}을 함께 담는다 (응답 {@code data}와 예외 메시지·로그에 나온다).
 */
public class CustomException extends RuntimeException {
    private final ErrorCode errorCode;
    private final ErrorDetail detail;

    public CustomException(ErrorCode errorCode) {
        this(errorCode, null, null);
    }

    public CustomException(ErrorCode errorCode, ErrorDetail detail) {
        this(errorCode, detail, null);
    }

    public CustomException(ErrorCode errorCode, ErrorDetail detail, Throwable cause) {
        super(detail == null ? errorCode.getMessage() : errorCode.getMessage() + " [" + detail.summary() + "]", cause);
        this.errorCode = errorCode;
        this.detail = detail;
    }

    /** 같은 에러에 발생 위치를 붙인 새 예외 (원래 예외는 cause로 남는다). */
    public CustomException at(ErrorLocation location) {
        ErrorDetail located = (detail == null ? ErrorDetail.from(errorCode) : detail).at(location);
        return new CustomException(errorCode, located, this);
    }

    public ErrorCode getErrorCode() { return errorCode; }

    /** 없으면 null. */
    public ErrorDetail getDetail() { return detail; }
}
