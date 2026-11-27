package com.cue.source.fetch;

import com.cue.source.config.SourceApiProperties;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 외부 자료와 Gateway의 HTTP 응답을 평가 작업별로 모아 로컬 진단 파일에 기록한다. */
@Slf4j
@Component
public class SourceApiLog {
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final Pattern QUERY_SECRET = Pattern.compile(
            "(?i)(^|&)(key|api[_-]?key|access[_-]?token|token|auth|authorization|password|secret|signature|sig)=[^&]*");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\n\\t]]");

    private final Path file;
    private final List<String> tokens;
    private final ThreadLocal<StringBuilder> active = new ThreadLocal<>();

    public SourceApiLog(@Value("${cue.source.api-log-file:}") String path, SourceApiProperties properties,
                        @Value("${cue.claude-gateway.api-key:}") String claudeKey,
                        @Value("${cue.openai-gateway.api-key:}") String openAiKey) {
        file = path.isBlank() ? null : Path.of(path).toAbsolutePath();
        tokens = file == null ? List.of() : Stream.concat(Stream.of(claudeKey, openAiKey),
                        Stream.of(properties.github(), properties.huggingFace(), properties.stackExchange(),
                                properties.semanticScholar(), properties.hackerNews(), properties.wikipedia())
                                .map(SourceApiProperties.Api::token))
                .filter(StringUtils::hasText).distinct().toList();
    }

    public <T> T recordOperation(String name, Supplier<T> operation) {
        if (file == null || isRecording()) {
            return operation.get();
        }
        StringBuilder entry = new StringBuilder().append(OffsetDateTime.now()).append(" 시작 ").append(name).append('\n');
        active.set(entry);
        try {
            return operation.get();
        } finally {
            active.remove();
            entry.append(OffsetDateTime.now()).append(" 종료 ").append(name).append("\n======\n\n");
            appendToFile(entry);
        }
    }

    public boolean isRecording() {
        return active.get() != null;
    }

    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        if (!isRecording()) {
            return execution.execute(request, body);
        }

        URI uri = request.getURI();
        HttpMethod method = request.getMethod();
        long start = System.nanoTime();
        ClientHttpResponse response;
        try {
            response = execution.execute(request, body);
        } catch (IOException | RuntimeException error) {
            failure(method, uri, start, null, error, true);
            throw error;
        }

        Integer status = null;
        try {
            status = response.getStatusCode().value();
            response(method, uri, start, status, response.getHeaders().getFirst("Content-Type"), response.getBody(), true);
        } catch (IOException | RuntimeException error) {
            // RestClient의 버퍼 응답을 사용하므로 로깅 이후에도 호출자가 같은 본문을 읽을 수 있다.
            failure(method, uri, start, status, error, true);
        }
        return response;
    }

    void response(HttpMethod method, URI uri, long start, int status, String contentType, InputStream body,
                  boolean includeQuery) throws IOException {
        StringBuilder entry = active.get();
        if (entry == null) {
            return;
        }
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        String text = type.isBlank() || type.startsWith("text/") || type.contains("json") || type.contains("xml")
                || type.contains("markdown") ? readBody(body) : "(텍스트가 아닌 응답)";
        startRequest(entry, method, uri, start, includeQuery);
        entry.append("  HTTP ").append(status).append("  ").append(clean(contentType)).append('\n')
                .append("  Body\n    ").append(text.isEmpty() ? "(비어 있음)" : clean(text).replace("\n", "\n    "))
                .append("\n\n");
    }

    void failure(HttpMethod method, URI uri, long start, Integer status, Exception error, boolean includeQuery) {
        StringBuilder entry = active.get();
        if (entry == null) {
            return;
        }
        startRequest(entry, method, uri, start, includeQuery);
        if (status != null) {
            entry.append("  HTTP ").append(status).append('\n');
        }
        entry.append("  Failure ").append(error.getClass().getSimpleName());
        if (error.getCause() != null) {
            entry.append(" / ").append(error.getCause().getClass().getSimpleName());
        }
        entry.append("\n\n");
    }

    private void startRequest(StringBuilder entry, HttpMethod method, URI uri, long start, boolean includeQuery) {
        String query = uri.getRawQuery();
        if (query != null) {
            query = includeQuery ? "?" + QUERY_SECRET.matcher(query).replaceAll("$1$2=[redacted]") : "?[omitted]";
        }
        String port = uri.getPort() < 0 ? "" : ":" + uri.getPort();
        String url = uri.getScheme() + "://" + uri.getHost() + port + uri.getRawPath() + (query == null ? "" : query);
        long millis = (System.nanoTime() - start) / 1_000_000;
        entry.append(OffsetDateTime.now()).append(' ').append(method).append(' ').append(clean(url))
                .append(" (").append(millis).append(" ms)\n");
    }

    private static String readBody(InputStream body) throws IOException {
        byte[] prefix = body.readNBytes(2);
        boolean compressed = prefix.length == 2 && prefix[0] == (byte) 0x1f && prefix[1] == (byte) 0x8b;
        try (InputStream raw = new SequenceInputStream(new ByteArrayInputStream(prefix), body);
             InputStream stream = compressed ? new GZIPInputStream(raw) : raw) {
            byte[] bytes = stream.readNBytes(MAX_BODY_BYTES + 1);
            String text = new String(bytes, 0, Math.min(bytes.length, MAX_BODY_BYTES), StandardCharsets.UTF_8);
            return bytes.length > MAX_BODY_BYTES ? text + "\n[본문 앞 64 KiB만 기록]" : text;
        }
    }

    private String clean(String text) {
        String result = text == null ? "" : CONTROL.matcher(text).replaceAll("");
        for (String token : tokens) {
            result = result.replace(token, "[redacted]");
        }
        return result;
    }

    private synchronized void appendToFile(StringBuilder entry) {
        try {
            Files.createDirectories(file.getParent());
            if (Files.getFileStore(file.getParent()).supportsFileAttributeView("posix")) {
                try {
                    Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                } catch (FileAlreadyExistsException ignored) {
                    // 이미 만들어 둔 파일 끝에 이어서 쓴다.
                }
            }
            Files.writeString(file, entry, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | SecurityException error) {
            log.warn("외부 API 로그 저장 실패 path={} cause={}", file, error.getClass().getSimpleName());
        }
    }
}
