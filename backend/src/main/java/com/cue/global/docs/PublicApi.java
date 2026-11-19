package com.cue.global.docs;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 액세스 토큰 없이 호출할 수 있는 API 표시. Swagger에서 보안 요구(bearerAuth)와 인증 에러(401)를 뺀다.
 * 실제 허용은 {@code SecurityConfig}의 permitAll이 결정하므로 둘을 반드시 같이 바꾼다 (OpenApiDocsTest가 일치 여부를 검사).
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PublicApi {
}
