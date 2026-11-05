package com.cue.global.error;

import com.cue.global.response.CommonResponse;
import com.cue.global.response.CommonResponse.ValidationErrorDetail;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(CustomException.class)
    ResponseEntity<CommonResponse<Void>> handleCustom(CustomException e, HttpServletRequest req) {
        ErrorCode code = e.getErrorCode();
        log.warn("[BUS] {} {} | {}: {}", req.getMethod(), req.getRequestURI(), code.getCode(), e.getMessage());
        return fail(code);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<CommonResponse<List<ValidationErrorDetail>>> handleValidation(
            MethodArgumentNotValidException e, HttpServletRequest req) {
        log.warn("[VAL] {} {} | {}", req.getMethod(), req.getRequestURI(), e.getMessage());
        return ResponseEntity.status(ErrorCode.INVALID_INPUT_VALUE.getStatus())
                .body(CommonResponse.onFailure(ErrorCode.INVALID_INPUT_VALUE, e.getBindingResult()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<CommonResponse<List<ValidationErrorDetail>>> handleConstraint(
            ConstraintViolationException e, HttpServletRequest req) {
        log.warn("[VAL] {} {} | {}", req.getMethod(), req.getRequestURI(), e.getMessage());
        List<ValidationErrorDetail> details = e.getConstraintViolations().stream()
                .map(v -> new ValidationErrorDetail(v.getPropertyPath().toString(), v.getMessage()))
                .toList();
        return ResponseEntity.status(ErrorCode.INVALID_INPUT_VALUE.getStatus())
                .body(CommonResponse.onFailure(ErrorCode.INVALID_INPUT_VALUE, details));
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<CommonResponse<Void>> handleInvalidParam(Exception e, HttpServletRequest req) {
        log.warn("[VAL] {} {} | {}", req.getMethod(), req.getRequestURI(), e.getMessage());
        return fail(ErrorCode.INVALID_INPUT_VALUE);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<CommonResponse<Void>> handleUnreadable(HttpMessageNotReadableException e, HttpServletRequest req) {
        log.warn("[BAD] {} {} | {}", req.getMethod(), req.getRequestURI(), e.getMessage());
        return fail(ErrorCode.BAD_REQUEST);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<CommonResponse<Void>> handleMethod(HttpRequestMethodNotSupportedException e) {
        return fail(ErrorCode.METHOD_NOT_ALLOWED);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<CommonResponse<Void>> handleNoResource(NoResourceFoundException e) {
        return fail(ErrorCode.NOT_FOUND);
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<CommonResponse<Void>> handleAuthentication(AuthenticationException e) {
        return fail(ErrorCode.UNAUTHORIZED);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<CommonResponse<Void>> handleAccessDenied(AccessDeniedException e) {
        return fail(ErrorCode.FORBIDDEN);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<CommonResponse<Void>> handleUnexpected(Exception e, HttpServletRequest req) {
        log.error("[ERR] {} {}", req.getMethod(), req.getRequestURI(), e);
        return fail(ErrorCode.INTERNAL_SERVER_ERROR);
    }

    private static ResponseEntity<CommonResponse<Void>> fail(ErrorCode code) {
        return ResponseEntity.status(code.getStatus()).body(CommonResponse.onFailure(code));
    }
}
