package com.cue.global.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.StringJoiner;

/**
 * 에러가 난 위치와, 외부 API를 쓰다 실패했으면 그 API와 원인. 실패 응답의 {@code data}, 아이디어의 {@code lastEvaluationError}, 로그에 같이 쓴다.
 * 만들 때는 문자열을 직접 적지 않고 {@link ErrorLocation}·{@link ExternalApi}·{@link ErrorReason}에서 고른다
 * (생성자는 DB에 저장한 값을 되살릴 때만 쓴다).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "에러가 난 위치와 원인")
public record ErrorDetail(
        @Schema(description = "에러가 난 위치", example = "근거 4개 기준 평가", nullable = true) String location,
        @Schema(description = "실패한 외부 API. 외부 API와 무관하면 생략", example = "Gemini", nullable = true) String api,
        @Schema(description = "실패 원인", example = "429 호출 한도 초과", nullable = true) String reason) {

    /** 정해진 원인. 위치는 아는 곳에서 {@link CustomException#at}으로 붙인다. {@code api}는 모르거나 무관하면 null. */
    public static ErrorDetail of(ExternalApi api, ErrorReason reason, Object... args) {
        return new ErrorDetail(null, api == null ? null : api.label(), reason.text(args));
    }

    /** 외부 호출에서 난 예외로 원인을 정한다. */
    public static ErrorDetail of(ExternalApi api, Throwable cause) {
        return new ErrorDetail(null, api == null ? null : api.label(), ErrorReason.of(cause));
    }

    /** 외부 API가 돌려준 HTTP 상태로 원인을 정한다. */
    public static ErrorDetail ofStatus(ExternalApi api, int status) {
        return new ErrorDetail(null, api == null ? null : api.label(), ErrorReason.ofStatus(status));
    }

    /** 이미 정해진 원인 문구를 가진 detail에 웹 페이지 호스트를 API로 붙인다. */
    public ErrorDetail onWebPage(String host) {
        return new ErrorDetail(location, ExternalApi.WEB_PAGE.label(host), reason);
    }

    /** 원인이 따로 없는 에러는 에러 코드의 메시지를 원인으로 쓴다. */
    public static ErrorDetail from(ErrorCode code) {
        return new ErrorDetail(null, null, code.getMessage());
    }

    public ErrorDetail at(ErrorLocation newLocation) {
        return new ErrorDetail(newLocation.label(), api, reason);
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
