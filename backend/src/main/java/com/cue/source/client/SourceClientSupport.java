package com.cue.source.client;

import com.cue.global.error.CustomException;
import com.cue.global.error.ErrorCode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 클라이언트 공통: 본문을 받아 JSON으로 파싱하고, 모든 실패를 G002로 바꾼다. */
final class SourceClientSupport {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private SourceClientSupport() {}

    static JsonNode getJson(RestClient.RequestHeadersSpec<?> request) {
        try {
            byte[] body = request.retrieve().body(byte[].class);
            if (body == null || body.length == 0) {
                throw new CustomException(ErrorCode.EXTERNAL_SERVER_ERROR);
            }
            return MAPPER.readTree(new String(gunzipIfNeeded(body), StandardCharsets.UTF_8));
        } catch (CustomException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            // 4xx/5xx, 타임아웃, 연결 실패, JSON 파싱 실패 모두 외부 서비스 오류로 취급한다.
            throw new CustomException(ErrorCode.EXTERNAL_SERVER_ERROR);
        }
    }

    /** Stack Exchange는 항상 gzip으로 응답하고 JDK HttpClient는 자동 해제하지 않아서 직접 푼다. */
    private static byte[] gunzipIfNeeded(byte[] body) throws IOException {
        if (body.length > 2 && (body[0] & 0xff) == 0x1f && (body[1] & 0xff) == 0x8b) {
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(body))) {
                return in.readAllBytes();
            }
        }
        return body;
    }

    static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? "" : v.asString();
    }

    /** 본문을 문자열 그대로 받는다. 모든 실패는 G002. */
    static String getText(RestClient.RequestHeadersSpec<?> request) {
        try {
            String body = request.retrieve().body(String.class);
            return body == null ? "" : body;
        } catch (RuntimeException e) {
            throw new CustomException(ErrorCode.EXTERNAL_SERVER_ERROR);
        }
    }

    /** ISO 일시 문자열("2026-01-02T03:04:05Z" 등)에서 날짜(yyyy-MM-dd)만 뽑는다. 없으면 빈 문자열. */
    static String date(JsonNode node, String field) {
        String v = text(node, field);
        return v.length() >= 10 ? v.substring(0, 10) : "";
    }

    /** epoch 초를 날짜(yyyy-MM-dd, UTC)로. 없으면 빈 문자열. */
    static String epochDate(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isNumber() ? java.time.Instant.ofEpochSecond(v.asLong()).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString() : "";
    }

    /** "이름=값" 쌍을 쉼표로 잇는다. 값이 없는 필드는 뺀다. */
    static String metrics(JsonNode node, String... pairs) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            JsonNode v = node.path(pairs[i + 1]);
            if (v.isNumber()) {
                parts.add(pairs[i] + "=" + v.asLong());
            }
        }
        return String.join(", ", parts);
    }
}
