package com.cue.global.response;

import com.cue.global.error.ErrorCode;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.validation.BindingResult;

/**
 * 모든 API 응답의 공통 래퍼.
 * 성공: {result: SUCCESS, code: "200", message, data, timestamp}
 * 실패: {result: FAIL, code: "A001" 등 ErrorCode, message, data(검증 오류 상세 등, 없으면 생략), timestamp}
 */
@JsonPropertyOrder({"result", "code", "message", "data", "timestamp"})
public record CommonResponse<T>(
        Result result,
        String code,
        String message,
        @JsonInclude(JsonInclude.Include.NON_NULL) T data,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime timestamp) {

    public enum Result { SUCCESS, FAIL }

    private static final String SUCCESS_CODE = "200";
    private static final String SUCCESS_MESSAGE = "요청이 성공적으로 처리되었습니다.";

    public static <T> CommonResponse<T> onSuccess(T data) {
        return new CommonResponse<>(Result.SUCCESS, SUCCESS_CODE, SUCCESS_MESSAGE, data, LocalDateTime.now());
    }

    public static CommonResponse<Void> onSuccess() {
        return new CommonResponse<>(Result.SUCCESS, SUCCESS_CODE, SUCCESS_MESSAGE, null, LocalDateTime.now());
    }

    public static CommonResponse<Void> onFailure(ErrorCode errorCode) {
        return new CommonResponse<>(Result.FAIL, errorCode.getCode(), errorCode.getMessage(), null, LocalDateTime.now());
    }

    public static <T> CommonResponse<T> onFailure(ErrorCode errorCode, T data) {
        return new CommonResponse<>(Result.FAIL, errorCode.getCode(), errorCode.getMessage(), data, LocalDateTime.now());
    }

    public static CommonResponse<List<ValidationErrorDetail>> onFailure(ErrorCode errorCode, BindingResult bindingResult) {
        return onFailure(errorCode, ValidationErrorDetail.of(bindingResult));
    }

    public record ValidationErrorDetail(String field, String reason) {
        public static List<ValidationErrorDetail> of(BindingResult bindingResult) {
            return bindingResult.getFieldErrors().stream()
                    .map(e -> new ValidationErrorDetail(e.getField(), e.getDefaultMessage()))
                    .toList();
        }
    }
}
