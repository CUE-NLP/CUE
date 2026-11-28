package com.cue.global.error;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;

/** 외부 API 실패 원인을 짧은 문구로 바꾼다. 예외 메시지·응답 본문은 쓰지 않는다 (URL·키가 섞일 수 있어서). */
public final class ExternalFailure {
    private ExternalFailure() {}

    public static String reason(Throwable error) {
        if (error instanceof CustomException custom && custom.getDetail() != null) {
            return custom.getDetail().reason();
        }
        if (has(error, UnresolvedAddressException.class) || has(error, UnknownHostException.class)) {
            return "주소를 찾을 수 없음";
        }
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof RestClientResponseException response) return http(response.getStatusCode().value());
            if (t instanceof HttpConnectTimeoutException) return "연결 시간 초과";
            if (t instanceof HttpTimeoutException || t instanceof SocketTimeoutException) return "응답 시간 초과";
            if (t instanceof ConnectException) return "연결 실패";
            if (t instanceof JacksonException) return "응답 해석 실패";
        }
        Throwable root = error;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return "통신 오류(" + root.getClass().getSimpleName() + ")";
    }

    public static String http(int status) {
        String meaning = switch (status) {
            case 400 -> "잘못된 요청(요청 형식·API 키 확인)";
            case 401 -> "인증 실패(API 키·토큰 확인)";
            case 403 -> "접근 거부(호출 한도 초과 또는 권한 확인)";
            case 404 -> "대상 없음(주소·모델 이름 확인)";
            case 429 -> "호출 한도 초과";
            case 503 -> "서비스 일시 중단";
            default -> status >= 500 ? "외부 서버 오류" : "요청 실패";
        };
        return status + " " + meaning;
    }

    private static boolean has(Throwable error, Class<? extends Throwable> type) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (type.isInstance(t)) return true;
        }
        return false;
    }
}
