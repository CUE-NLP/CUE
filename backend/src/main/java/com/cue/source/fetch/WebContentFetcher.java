package com.cue.source.fetch;

import com.cue.global.error.CustomException;
import com.cue.global.error.ErrorCode;
import com.cue.global.error.ErrorDetail;
import com.cue.global.error.ErrorReason;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 외부 웹 페이지 원문 가져오기. 안전장치: {@link UrlGuard}로 매 요청(리다이렉트 포함) 검사, 리다이렉트 횟수 제한,
 * 타임아웃·응답 크기 상한(전송 계층), text/* 계열만 허용, HTML은 본문 텍스트만 추출, 글자 수 상한.
 * 실패는 {@code CustomException}으로 던지며 호출하는 쪽이 해당 항목을 건너뛴다.
 * 가져온 본문은 신뢰하지 않는 데이터다(프롬프트에서 지시문을 따르지 않도록 방어 문구를 둔다).
 */
public class WebContentFetcher {
    private static final Pattern CHARSET = Pattern.compile("charset=([\\w-]+)", Pattern.CASE_INSENSITIVE);

    /** HTTP 한 번 요청 (리다이렉트를 따라가지 않는다). 구현체는 응답 크기를 상한까지만 읽는다. */
    public interface Transport {
        Response get(URI uri) throws IOException, InterruptedException;
    }

    public record Response(int status, String location, String contentType, byte[] body) {}

    private final Transport transport;
    private final int maxRedirects;
    private final int maxChars;

    public WebContentFetcher(Transport transport, int maxRedirects, int maxChars) {
        this.transport = transport;
        this.maxRedirects = maxRedirects;
        this.maxChars = maxChars;
    }

    public String fetch(String url) {
        URI uri;
        try {
            uri = URI.create(url == null ? "" : url.strip());
        } catch (IllegalArgumentException e) {
            throw new CustomException(ErrorCode.SOURCE_URL_BLOCKED);
        }
        try {
            for (int hop = 0; hop <= maxRedirects; hop++) {
                UrlGuard.assertSafe(uri);
                Response r = transport.get(uri);
                if (r.status() >= 300 && r.status() < 400 && r.location() != null && !r.location().isBlank()) {
                    uri = uri.resolve(r.location());
                    continue;
                }
                if (r.status() != 200) {
                    throw failure(uri, ErrorDetail.ofStatus(null, r.status()), null);
                }
                if (r.body() == null) {
                    throw failure(uri, ErrorDetail.of(null, ErrorReason.EMPTY_RESPONSE), null);
                }
                return extract(uri, r);
            }
        } catch (IOException e) {
            throw failure(uri, ErrorDetail.of(null, e), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failure(uri, ErrorDetail.of(null, ErrorReason.INTERRUPTED), e);
        }
        throw failure(uri, ErrorDetail.of(null, ErrorReason.TOO_MANY_REDIRECTS), null);
    }

    private static CustomException failure(URI uri, ErrorDetail detail, Throwable cause) {
        return new CustomException(ErrorCode.EXTERNAL_SERVER_ERROR, detail.onWebPage(uri.getHost()), cause);
    }

    private String extract(URI uri, Response r) {
        String type = r.contentType() == null ? "" : r.contentType().toLowerCase(Locale.ROOT);
        boolean html = type.contains("html");
        if (!html && !type.startsWith("text/")) {
            throw failure(uri, ErrorDetail.of(null, ErrorReason.NOT_TEXT_RESPONSE), null); // 이진/JSON 등은 원문으로 쓰지 않는다
        }
        String text = new String(r.body(), charset(type));
        text = html ? HtmlText.toText(text) : text.replaceAll("\\s+", " ").strip();
        return text.length() > maxChars ? text.substring(0, maxChars) : text;
    }

    private static Charset charset(String contentType) {
        Matcher m = CHARSET.matcher(contentType);
        if (m.find()) {
            try {
                return Charset.forName(m.group(1));
            } catch (RuntimeException ignored) {
                // 알 수 없는 인코딩이면 UTF-8
            }
        }
        return StandardCharsets.UTF_8;
    }
}
