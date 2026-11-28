package com.cue.global.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.StringJoiner;

/**
 * 에러가 난 위치와, 외부 API를 쓰다 실패했으면 그 API와 원인. 실패 응답의 {@code data}, 아이디어의 {@code lastEvaluationError}, 로그에 같이 쓴다.
 * 키·토큰·요청·응답 본문은 담지 않는다 (HTTP 상태나 예외 종류로 만든 짧은 문구만).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "에러가 난 위치와 원인")
public record ErrorDetail(
        @Schema(description = "에러가 난 위치", example = "근거 4개 기준 평가", nullable = true) String location,
        @Schema(description = "실패한 외부 API. 외부 API와 무관하면 생략", example = "Gemini", nullable = true) String api,
        @Schema(description = "실패 원인", example = "429 호출 한도 초과", nullable = true) String reason) {

    /** 외부 API 실패. 위치는 호출한 쪽이 {@link CustomException#at}으로 채운다. */
    public static ErrorDetail external(String api, String reason) {
        return new ErrorDetail(null, api, reason);
    }

    public ErrorDetail at(String newLocation) {
        return new ErrorDetail(newLocation, api, reason);
    }

    /** 로그용 한 줄: {@code 위치=…, API=…, 원인=…} (없는 값은 생략). */
    public String summary() {
        StringJoiner joined = new StringJoiner(", ");
        if (location != null) joined.add("위치=" + location);
        if (api != null) joined.add("API=" + api);
        if (reason != null) joined.add("원인=" + reason);
        return joined.toString();
    }
}
