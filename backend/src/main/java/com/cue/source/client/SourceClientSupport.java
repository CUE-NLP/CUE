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
}
