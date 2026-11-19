package com.cue.global.docs;

import com.cue.global.error.ErrorCode;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springdoc.core.customizers.OperationCustomizer;

/**
 * 모든 API 문서에 에러 응답을 자동으로 채운다: 공통 에러 + {@link ApiErrors}로 선언한 에러를
 * HTTP 상태별 응답(설명에 code/message 목록, 예시에 CommonResponse 실패 본문)으로 만든다.
 * {@link PublicApi}가 붙은 API는 보안 요구를 비우고 인증 에러를 붙이지 않는다.
 */
@Component
public class ApiErrorCustomizer implements OperationCustomizer {

    /** 인증이 필요한 API에 붙는 에러. 필터 단계(SecurityErrorWriter)에서 응답한다. */
    private static final List<ErrorCode> AUTH_ERRORS = List.of(
            ErrorCode.UNAUTHORIZED, ErrorCode.JWT_MISSING, ErrorCode.JWT_MALFORMED,
            ErrorCode.JWT_EXPIRED, ErrorCode.JWT_INVALID);

    @Override
    public Operation customize(Operation operation, HandlerMethod method) {
        boolean isPublic = AnnotatedElementUtils.hasAnnotation(method.getMethod(), PublicApi.class);
        Set<ErrorCode> codes = new LinkedHashSet<>();
        if (isPublic) {
            operation.setSecurity(List.of()); // 전역 bearerAuth 요구 제거
        } else {
            codes.addAll(AUTH_ERRORS);
        }
        boolean hasBody = false;
        boolean hasParam = false;
        for (MethodParameter p : method.getMethodParameters()) {
            hasBody |= p.hasParameterAnnotation(RequestBody.class);
            hasParam |= p.hasParameterAnnotation(PathVariable.class) || p.hasParameterAnnotation(RequestParam.class);
        }
        if (hasBody) {
            codes.add(ErrorCode.BAD_REQUEST);
            codes.add(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
        }
        if (hasBody || hasParam) {
            codes.add(ErrorCode.INVALID_INPUT_VALUE);
        }
        ApiErrors declared = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), ApiErrors.class);
        if (declared != null) {
            codes.addAll(List.of(declared.value()));
        }
        codes.add(ErrorCode.INTERNAL_SERVER_ERROR);

        Map<HttpStatus, List<ErrorCode>> byStatus = codes.stream()
                .collect(Collectors.groupingBy(ErrorCode::getStatus, LinkedHashMap::new, Collectors.toList()));
        ApiResponses responses = operation.getResponses() != null ? operation.getResponses() : new ApiResponses();
        operation.setResponses(responses);
        byStatus.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> responses.addApiResponse(String.valueOf(e.getKey().value()), toResponse(e.getValue())));
        return operation;
    }

    private static ApiResponse toResponse(List<ErrorCode> codes) {
        String description = codes.stream()
                .map(c -> "- `" + c.getCode() + "` " + c.getMessage())
                .collect(Collectors.joining("\n"));
        Map<String, Example> examples = new LinkedHashMap<>();
        for (ErrorCode c : codes) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("result", "FAIL");
            value.put("code", c.getCode());
            value.put("message", c.getMessage());
            value.put("timestamp", "2026-11-05 15:00:00");
            examples.put(c.getCode() + " " + c.name(), new Example().summary(c.getMessage()).value(value));
        }
        MediaType mediaType = new MediaType().schema(failureSchema());
        mediaType.setExamples(examples);
        return new ApiResponse().description(description).content(new Content().addMediaType("application/json", mediaType));
    }

    /** 실패 응답 스키마: CommonResponse에서 result=FAIL. data는 검증 실패(G004)일 때만 [{field, reason}]. */
    private static Schema<?> failureSchema() {
        Schema<?> schema = new ObjectSchema().description("CommonResponse 실패 응답");
        List<String> results = new ArrayList<>(List.of("SUCCESS", "FAIL"));
        schema.addProperty("result", new StringSchema()._enum(results).example("FAIL"));
        schema.addProperty("code", new StringSchema().description("ErrorCode 코드 ({영문1자}{3자리})").example("A006"));
        schema.addProperty("message", new StringSchema().description("에러 메시지"));
        schema.addProperty("data", new Schema<>().description("G004일 때만 [{field, reason}] 목록, 그 외 생략"));
        schema.addProperty("timestamp", new StringSchema().description("응답 시각 (yyyy-MM-dd HH:mm:ss)").example("2026-11-05 15:00:00"));
        return schema;
    }
}
