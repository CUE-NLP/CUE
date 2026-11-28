package com.cue.global.error;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;

/**
 * 에러가 난 원인. 응답·DB·로그에 쓰는 문구는 여기에서만 정하고, 값이 필요한 문구는 {@code %s}로 받는다.
 * 예외 메시지·응답 본문·URL은 문구에 넣지 않는다 (키·토큰이나 사용자 내용이 섞일 수 있어서).
 */
public enum ErrorReason {
    // 외부 API의 HTTP 상태
    HTTP_BAD_REQUEST("400 잘못된 요청(요청 형식·API 키 확인)"),
    HTTP_UNAUTHORIZED("401 인증 실패(API 키·토큰 확인)"),
    HTTP_FORBIDDEN("403 접근 거부(호출 한도 초과 또는 권한 확인)"),
    HTTP_NOT_FOUND("404 대상 없음(주소·모델 이름 확인)"),
    HTTP_TOO_MANY_REQUESTS("429 호출 한도 초과"),
    HTTP_UNAVAILABLE("503 서비스 일시 중단"),
    HTTP_SERVER_ERROR("%s 외부 서버 오류"),
    HTTP_FAILED("%s 요청 실패"),
    // 통신
    UNKNOWN_HOST("주소를 찾을 수 없음"),
    CONNECT_TIMEOUT("연결 시간 초과"),
    RESPONSE_TIMEOUT("응답 시간 초과"),
    CONNECT_FAILED("연결 실패"),
    COMMUNICATION_ERROR("통신 오류(%s)"),
    TOO_MANY_REDIRECTS("리다이렉트 횟수 초과"),
    INTERRUPTED("요청이 중단됨"),
    RATE_LIMIT_WAIT_EXCEEDED("호출 간격 제한 대기 시간 초과"),
    RETRY_INTERRUPTED("재시도 대기 중 중단됨"),
    // 응답 내용
    EMPTY_RESPONSE("빈 응답"),
    NOT_TEXT_RESPONSE("텍스트가 아닌 응답"),
    UNREADABLE_RESPONSE("응답 해석 실패%s"),
    NO_RESPONSE_CONTENT("응답에 본문이 없음%s"),
    INCOMPLETE_RESPONSE("응답이 완료되지 않음(%s)"),
    MISSING_LIST("응답에 %s 목록이 없음"),
    INVALID_FIELD("응답의 %s 항목이 빠졌거나 형식이 올바르지 않음"),
    MISSING_KEYWORDS("응답에 키워드가 없음"),
    // 원문을 읽을 대상의 형식
    INVALID_REPOSITORY_NAME("저장소 이름 형식이 아님"),
    INVALID_MODEL_NAME("모델 이름 형식이 아님"),
    INVALID_QUESTION_URL("질문 주소 형식이 아님"),
    INVALID_STORY_ID("글 번호 형식이 아님"),
    // 서버 내부
    QUEUE_FULL("대기열이 가득 참"),
    INTERNAL_ERROR("서버 내부 오류");

    private final String template;

    ErrorReason(String template) {
        this.template = template;
    }

    public String text(Object... args) {
        return template.formatted(args);
    }

    /** HTTP 상태 코드에 해당하는 원인 문구. */
    public static String ofStatus(int status) {
        return switch (status) {
            case 400 -> HTTP_BAD_REQUEST.text();
            case 401 -> HTTP_UNAUTHORIZED.text();
            case 403 -> HTTP_FORBIDDEN.text();
            case 404 -> HTTP_NOT_FOUND.text();
            case 429 -> HTTP_TOO_MANY_REQUESTS.text();
            case 503 -> HTTP_UNAVAILABLE.text();
            default -> (status >= 500 ? HTTP_SERVER_ERROR : HTTP_FAILED).text(status);
        };
    }

    /** 외부 호출에서 난 예외의 원인 문구. 이미 원인을 담은 {@link CustomException}이면 그 문구를 그대로 쓴다. */
    public static String of(Throwable error) {
        if (error instanceof CustomException custom && custom.getDetail() != null) {
            return custom.getDetail().reason();
        }
        if (has(error, UnresolvedAddressException.class) || has(error, UnknownHostException.class)) {
            return UNKNOWN_HOST.text();
        }
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof RestClientResponseException response) return ofStatus(response.getStatusCode().value());
            if (t instanceof HttpConnectTimeoutException) return CONNECT_TIMEOUT.text();
            if (t instanceof HttpTimeoutException || t instanceof SocketTimeoutException) return RESPONSE_TIMEOUT.text();
            if (t instanceof ConnectException) return CONNECT_FAILED.text();
            if (t instanceof JacksonException) return UNREADABLE_RESPONSE.text("");
        }
        Throwable root = error;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return COMMUNICATION_ERROR.text(root.getClass().getSimpleName());
    }

    private static boolean has(Throwable error, Class<? extends Throwable> type) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (type.isInstance(t)) return true;
        }
        return false;
    }
}
