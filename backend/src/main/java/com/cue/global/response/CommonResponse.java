package com.cue.global.response;

import com.cue.global.error.ErrorCode;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.validation.BindingResult;

/**
 * 모든 API 응답의 공통 래퍼.
 * 성공: {result: SUCCESS, code: "200", message, data, timestamp}
 * 실패: {result: FAIL, code: "A001" 등 ErrorCode, message, data(검증 오류 상세 등, 없으면 생략), timestamp}
 */
@Schema(description = "모든 API 응답의 공통 래퍼")
@JsonPropertyOrder({"result", "code", "message", "data", "timestamp"})
public record CommonResponse<T>(
        @Schema(description = "처리 결과", example = "SUCCESS") Result result,
        @Schema(description = "성공이면 \"200\", 실패면 ErrorCode 코드({영문1자}{3자리})", example = "200") String code,
        @Schema(description = "결과 메시지", example = "요청이 성공적으로 처리되었습니다.") String message,
        @Schema(description = "응답 데이터. 데이터가 없거나 실패면 생략(검증 실패 G004는 [{field, reason}])")
        @JsonInclude(JsonInclude.Include.NON_NULL) T data,
        @Schema(description = "응답 시각", example = "2026-11-05 15:00:00", type = "string")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime timestamp) {

    public enum Result { SUCCESS, FAIL }

    private static final String SUCCESS_CODE = "200";
    private static final String SUCCESS_MESSAGE = "요청이 성공적으로 처리되었습니다.";

    public static <T> CommonResponse<T> onSuccess(T data) {
        return new CommonResponse<>(Result.SUCCESS, SUCCESS_CODE, SUCCESS_MESSAGE, data, LocalDateTime.now());
    }

    /** 목록 응답: data가 {@code {items, count}} 형태가 된다. List를 data로 직접 반환하지 말고 이것을 쓴다. */
    public static <E> CommonResponse<ListData<E>> onSuccessList(List<E> items) {
        return onSuccess(ListData.of(items));
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

    @Schema(description = "입력 검증 실패 항목 (G004의 data)")
    public record ValidationErrorDetail(
            @Schema(description = "검증에 실패한 필드명", example = "email") String field,
            @Schema(description = "실패 사유", example = "must be a well-formed email address") String reason) {
        public static List<ValidationErrorDetail> of(BindingResult bindingResult) {
            return bindingResult.getFieldErrors().stream()
                    .map(e -> new ValidationErrorDetail(e.getField(), e.getDefaultMessage()))
                    .toList();
        }
    }
}
