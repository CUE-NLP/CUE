package com.cue.global.docs;

import com.cue.global.error.ErrorCode;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 이 API가 던질 수 있는 비즈니스 에러(ErrorCode)를 Swagger에 나열한다. {@link ApiErrorCustomizer}가 읽어서
 * HTTP 상태별 응답 설명(code/message)과 예시로 바꾼다.
 * 공통 에러(인증 A001·A003~A006, 서버 G001, 바디/검증 G003·G004·G007)는 자동으로 붙으므로 적지 않는다.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ApiErrors {
    ErrorCode[] value();
}
