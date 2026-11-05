package com.cue.global.error;

/** 비즈니스 로직에서 던지는 예외. ErrorCode 하나로 응답 상태·코드·메시지가 결정된다. */
public class CustomException extends RuntimeException {
    private final ErrorCode errorCode;

    public CustomException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() { return errorCode; }
}
