package com.cue.global.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger(OpenAPI) 전역 설정. 모든 API에 bearerAuth(JWT)를 기본 요구로 걸고,
 * 인증이 필요 없는 API만 {@code @PublicApi}로 뺀다 ({@code ApiErrorCustomizer}).
 */
@Configuration
public class OpenApiConfig {
    public static final String BEARER = "bearerAuth";

    @Bean
    OpenAPI cueOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("CUE API")
                        .version("v1")
                        .description("""
                                아이디어를 기록·구조화하고, 외부 자료의 변화를 근거로 경쟁력을 지속적으로 재평가하는 \
                                개인 아이디어 포트폴리오 서비스 CUE의 백엔드 API.

                                **응답 형식**: 모든 응답은 `{result, code, message, data, timestamp}` 래퍼(CommonResponse)입니다. \
                                성공은 `result=SUCCESS, code="200"`, 실패는 `result=FAIL`과 에러 코드(`{영문1자}{3자리}`)입니다. \
                                입력 검증 실패(`G004`)는 `data`에 `[{field, reason}]`이 담깁니다.

                                **인증**: 회원가입·로그인·토큰 재발급·로그아웃을 뺀 모든 API는 `Authorization: Bearer {accessToken}`이 필요합니다. \
                                우측 상단 **Authorize**에 로그인 응답의 `accessToken`을 넣으세요. 액세스 토큰이 만료(`A004`)되면 \
                                `POST /api/auth/refresh`로 재발급합니다."""))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("로그인(`POST /api/auth/login`) 응답의 `accessToken` 값만 입력합니다 (`Bearer ` 접두어는 자동으로 붙습니다).")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
