package com.cue.global.ai;

import com.cue.global.error.CustomException;
import com.cue.global.error.ErrorCode;
import com.cue.global.error.ErrorDetail;
import com.cue.global.error.ErrorReason;
import com.cue.global.error.ExternalApi;
import com.cue.idea.service.GeminiProperties;
import com.cue.source.fetch.SourceApiLog;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 구조화 JSON 호출과 레인 관리. 기본은 Gemini이며, Gateway 설정에서 Claude 또는 GPT를 선택할 수 있다.
 *
 * <p><b>두 레인</b>({@link AiLane}): 사용자가 기다리는 호출은 {@link AiLane#INTERACTIVE}(가벼운 모델·짧은 간격·오래 안 기다림),
 * 배치 호출은 {@link AiLane#BATCH}(기본 모델·긴 간격)로 보낸다. 레인마다 속도 제한기를 따로 둬서 배치가 사용자 호출을 막지 않는다.
 *
 * <p><b>429/503 대응</b>: 레인별 횟수만큼 지수 백오프(응답의 {@code Retry-After}가 있으면 그 값)로 재시도한다.
 * 그래도 실패하면 인터랙티브는 {@code E006}(AI 바쁨, 사용자에게 직접 입력을 안내), 배치는 {@code G002}다.
 */
@Slf4j
@Component
@EnableConfigurationProperties({GeminiProperties.class, GeminiLaneProperties.class,
        ClaudeGatewayProperties.class, OpenAiGatewayProperties.class})
public class GeminiClient {
    private static final long BATCH_MAX_BACKOFF_MS = 60_000;
    private static final long BASE_BACKOFF_MS = 1_000;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final GeminiProperties props;
    private final ClaudeGatewayClient claudeGateway;
    private final OpenAiGatewayClient openAiGateway;
    private final GeminiLaneProperties lane;
    private final GeminiRateLimiter batchLimiter;
    private final GeminiRateLimiter interactiveLimiter;
    private final int batchMaxRetries;
    private final GeminiRateLimiter.Sleeper sleeper;

    @Autowired
    public GeminiClient(RestClient.Builder builder, ObjectMapper objectMapper, GeminiProperties props,
                        GeminiLaneProperties lane, ClaudeGatewayProperties claude, OpenAiGatewayProperties openAi,
                        SourceApiLog sourceApiLog,
                        @Value("${cue.gemini.min-interval-ms:0}") long batchMinIntervalMs,
                        @Value("${cue.gemini.max-retries:0}") int batchMaxRetries) {
        this(restClient(builder, props.baseUrl(), props.timeoutSeconds()).build(), objectMapper, props, lane,
                batchMinIntervalMs, batchMaxRetries, Clock.systemUTC(), Thread::sleep,
                claude.enabled() && !openAi.enabled()
                        ? new ClaudeGatewayClient(gatewayRestClient(builder, claude.baseUrl(), claude.timeoutSeconds(),
                                sourceApiLog), claude, objectMapper)
                        : null,
                openAi.enabled()
                        ? new OpenAiGatewayClient(gatewayRestClient(builder, openAi.baseUrl(), openAi.timeoutSeconds(),
                                sourceApiLog), openAi, objectMapper)
                        : null);
    }

    private static RestClient.Builder restClient(RestClient.Builder builder, String baseUrl, int timeoutSeconds) {
        Duration timeout = Duration.ofSeconds(timeoutSeconds);
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(timeout).build());
        factory.setReadTimeout(timeout);
        return builder.clone().requestFactory(factory).baseUrl(baseUrl);
    }

    private static RestClient gatewayRestClient(RestClient.Builder builder, String baseUrl, int timeoutSeconds,
                                               SourceApiLog sourceApiLog) {
        return restClient(builder, baseUrl, timeoutSeconds)
                .bufferContent((uri, method) -> sourceApiLog.isRecording())
                .requestInterceptor(sourceApiLog::intercept)
                .build();
    }

    /** 테스트용: 이미 구성된 RestClient(MockRestServiceServer 연결)를 쓴다. 간격·재시도 없음. */
    GeminiClient(RestClient restClient, ObjectMapper objectMapper, GeminiProperties props) {
        this(restClient, objectMapper, props, GeminiLaneProperties.none(), 0, 0, Clock.systemUTC(), millis -> { });
    }

    /** 테스트용: 레인 설정·재시도·시계·대기를 직접 정한다. */
    GeminiClient(RestClient restClient, ObjectMapper objectMapper, GeminiProperties props, GeminiLaneProperties lane,
                 long batchMinIntervalMs, int batchMaxRetries, Clock clock, GeminiRateLimiter.Sleeper sleeper) {
        this(restClient, objectMapper, props, lane, batchMinIntervalMs, batchMaxRetries, clock, sleeper, null, null);
    }

    private GeminiClient(RestClient restClient, ObjectMapper objectMapper, GeminiProperties props, GeminiLaneProperties lane,
                         long batchMinIntervalMs, int batchMaxRetries, Clock clock, GeminiRateLimiter.Sleeper sleeper,
                         ClaudeGatewayClient claudeGateway, OpenAiGatewayClient openAiGateway) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.props = props;
        this.claudeGateway = claudeGateway;
        this.openAiGateway = openAiGateway;
        this.lane = lane;
        this.sleeper = sleeper;
        this.batchLimiter = new GeminiRateLimiter(batchMinIntervalMs, clock, sleeper);
        this.interactiveLimiter = new GeminiRateLimiter(lane.minIntervalMs(), clock, sleeper);
        this.batchMaxRetries = Math.max(0, batchMaxRetries);
    }

    /** 지금 선택된 AI 제공업체. 실패 원인에 어느 API인지 알릴 때 쓴다. */
    public ExternalApi api() {
        if (openAiGateway != null) {
            return ExternalApi.OPENAI_GATEWAY;
        }
        return claudeGateway != null ? ExternalApi.CLAUDE_GATEWAY : ExternalApi.GEMINI;
    }

    /** 선택한 제공업체를 쓸 수 있는지 (활성 + API 키 있음). */
    public boolean available() {
        if (openAiGateway != null) {
            return openAiGateway.available();
        }
        if (claudeGateway != null) {
            return claudeGateway.available();
        }
        return props.enabled() && props.apiKey() != null && !props.apiKey().isBlank();
    }

    /**
     * 배치 레인으로 프롬프트를 보내고 JSON으로 파싱한 응답 본문을 돌려준다.
     *
     * @throws CustomException 키 없음 E001, 통신 실패 G002, 응답 형식 오류 E002
     */
    public JsonNode generateJson(String prompt, Map<String, Object> responseSchema) {
        return generateJson(AiLane.BATCH, null, prompt, responseSchema);
    }

    /**
     * 레인을 정해 호출한다. {@code systemPrompt}가 있으면 systemInstruction으로 보내고 {@code userText}는 사용자 메시지다.
     *
     * @throws CustomException 키 없음 E001, 통신 실패 G002, 응답 형식 오류 E002, 인터랙티브가 한도·바쁨으로 못 받음 E006
     */
    public JsonNode generateJson(AiLane aiLane, String systemPrompt, String userText, Map<String, Object> responseSchema) {
        if (!available()) {
            throw new CustomException(ErrorCode.AI_NOT_CONFIGURED);
        }
        if (openAiGateway != null) {
            String response = post(aiLane, openAiGateway.model(), () -> openAiGateway.send(systemPrompt, userText, responseSchema));
            return parse(response, openAiGateway::responseText);
        }
        if (claudeGateway != null) {
            String response = post(aiLane, claudeGateway.model(), () -> claudeGateway.send(systemPrompt, userText, responseSchema));
            return parse(response, claudeGateway::responseText);
        }
        Map<String, Object> body = new HashMap<>();
        if (systemPrompt != null) {
            body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", systemPrompt))));
        }
        body.put("contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", userText)))));
        body.put("generationConfig", Map.of(
                "responseMimeType", "application/json",
                "responseSchema", responseSchema,
                "temperature", 0.2));
        String model = modelFor(aiLane);
        return parse(post(aiLane, model, () -> restClient.post()
                .uri("/v1beta/models/{model}:generateContent", model)
                .header("x-goog-api-key", props.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class)));
    }

    /** 레인에 쓸 모델. 인터랙티브 모델이 비어 있으면 기본 모델. */
    String modelFor(AiLane aiLane) {
        return aiLane == AiLane.INTERACTIVE && !lane.model().isBlank() ? lane.model() : props.model();
    }

    private String post(AiLane aiLane, String model, Supplier<String> request) {
        String provider = api().label();
        boolean logDetails = openAiGateway == null && claudeGateway == null;
        boolean interactive = aiLane == AiLane.INTERACTIVE;
        GeminiRateLimiter limiter = interactive ? interactiveLimiter : batchLimiter;
        long maxWaitMs = interactive ? lane.maxWaitMs() : Long.MAX_VALUE;
        int maxRetries = interactive ? lane.maxRetries() : batchMaxRetries;
        for (int attempt = 0; ; attempt++) {
            if (!limiter.acquire(maxWaitMs)) {
                log.warn("{} 호출 한도 대기가 길어 포기합니다 lane={}", provider, aiLane);
                throw new CustomException(interactive ? ErrorCode.AI_BUSY : ErrorCode.EXTERNAL_SERVER_ERROR,
                        failure(ErrorReason.RATE_LIMIT_WAIT_EXCEEDED));
            }
            try {
                return request.get();
            } catch (RestClientResponseException e) {
                int status = e.getStatusCode().value();
                boolean busy = status == 429 || status == 503;
                long backoff = busy ? backoffMs(e, attempt, interactive) : -1;
                if (busy && attempt < maxRetries && (!interactive || backoff <= lane.maxWaitMs())) {
                    log.warn("{} {} 응답, {}ms 뒤 재시도합니다 lane={} attempt={}", provider, status, backoff, aiLane, attempt + 1);
                    sleep(backoff);
                    continue;
                }
                // 원인을 알 수 있게 상태 코드와 응답 본문 앞부분을 남긴다 (요청 헤더의 키는 로그에 넣지 않는다)
                ErrorDetail detail = ErrorDetail.ofStatus(api(), status);
                // Gateway 응답 본문은 콘솔에 남기지 않는다 (필요하면 SourceApiLog 파일에서 확인)
                String body = logDetails ? abbreviate(e.getResponseBodyAsString()) : "(생략)";
                log.warn("{} 호출 실패 status={} lane={} model={} 원인={} body={}", provider, status, aiLane, model, detail.reason(), body);
                throw new CustomException(busy && interactive ? ErrorCode.AI_BUSY : ErrorCode.EXTERNAL_SERVER_ERROR, detail, e);
            } catch (RuntimeException e) {
                ErrorDetail detail = ErrorDetail.of(api(), e);
                String message = logDetails ? e.getMessage() : "(생략)";
                log.warn("{} 호출 중 통신 오류 lane={} model={} 원인={} cause={}: {}", provider, aiLane, model, detail.reason(),
                        e.getClass().getSimpleName(), message);
                throw new CustomException(ErrorCode.EXTERNAL_SERVER_ERROR, detail, e);
            }
        }
    }

    /** Retry-After(초)가 있으면 그 값, 없으면 1초·2초·4초… 지수 백오프. 배치는 최대 60초로 제한한다. */
    private long backoffMs(RestClientResponseException e, int attempt, boolean interactive) {
        String retryAfter = e.getResponseHeaders() == null ? null : e.getResponseHeaders().getFirst("Retry-After");
        long ms = BASE_BACKOFF_MS << Math.min(attempt, 6);
        if (retryAfter != null) {
            try {
                ms = Long.parseLong(retryAfter.trim()) * 1000;
            } catch (NumberFormatException ignored) {
                // 날짜 형식 등은 무시하고 지수 백오프를 쓴다
            }
        }
        return interactive ? ms : Math.min(ms, BATCH_MAX_BACKOFF_MS);
    }

    /** 로그에 남길 응답 본문. 길면 앞 500자만. */
    private static String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        String oneLine = body.replaceAll("\\s+", " ").strip();
        return oneLine.length() > 500 ? oneLine.substring(0, 500) + "..." : oneLine;
    }

    private void sleep(long ms) {
        try {
            sleeper.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("{} 재시도 대기 중 중단됐습니다", api().label());
            throw new CustomException(ErrorCode.EXTERNAL_SERVER_ERROR, failure(ErrorReason.RETRY_INTERRUPTED), ie);
        }
    }

    JsonNode parse(String responseBody) {
        return parse(responseBody, response -> response.path("candidates").path(0).path("content").path("parts").path(0).path("text"));
    }

    private JsonNode parse(String responseBody, Function<JsonNode, JsonNode> responseText) {
        JsonNode root = null;
        try {
            root = objectMapper.readTree(responseBody);
            JsonNode text = responseText.apply(root);
            if (!text.isString()) {
                throw new CustomException(ErrorCode.AI_RESPONSE_INVALID,
                        failure(ErrorReason.NO_RESPONSE_CONTENT, stopReason(root)));
            }
            return objectMapper.readTree(text.asString());
        } catch (CustomException e) {
            if (e.getDetail() != null) {
                log.warn("{} 응답을 해석하지 못했습니다 원인={}", api().label(), e.getDetail().reason());
            }
            throw e;
        } catch (RuntimeException e) {
            throw invalidResponse(failure(ErrorReason.UNREADABLE_RESPONSE, stopReason(root)), e);
        }
    }

    private CustomException invalidResponse(ErrorDetail detail, Throwable cause) {
        // 응답 본문에는 사용자의 아이디어 내용이 섞일 수 있어 로그에 남기지 않는다
        log.warn("{} 응답을 해석하지 못했습니다 원인={}", api().label(), detail.reason());
        return new CustomException(ErrorCode.AI_RESPONSE_INVALID, detail, cause);
    }

    /** 응답이 끊기거나 막힌 이유가 있으면 {@code (finishReason=MAX_TOKENS)}처럼 덧붙인다. */
    private static String stopReason(JsonNode root) {
        if (root == null) {
            return "";
        }
        String finish = root.path("candidates").path(0).path("finishReason").asString("");
        if (!finish.isEmpty() && !finish.equals("STOP")) {
            return "(finishReason=" + finish + ")";
        }
        String blocked = root.path("promptFeedback").path("blockReason").asString("");
        return blocked.isEmpty() ? "" : "(blockReason=" + blocked + ")";
    }

    private ErrorDetail failure(ErrorReason reason, Object... args) {
        return ErrorDetail.of(api(), reason, args);
    }
}
