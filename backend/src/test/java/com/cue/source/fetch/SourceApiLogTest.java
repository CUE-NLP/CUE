package com.cue.source.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cue.source.config.SourceApiProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

class SourceApiLogTest {
    @TempDir Path directory;

    @Test
    void 요청과_응답을_본문_소비_없이_작업별로_기록한다() throws IOException {
        Path file = directory.resolve("source-api.txt");
        SourceApiProperties props = mock(SourceApiProperties.class, RETURNS_DEEP_STUBS);
        when(props.stackExchange().token()).thenReturn("private-token-fixture");
        SourceApiLog log = new SourceApiLog(file.toString(), props, "", "");
        var compressed = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(compressed)) {
            gzip.write("{\"title\":\"압축된 자료\"}".getBytes(StandardCharsets.UTF_8));
        }
        byte[] gzippedBody = compressed.toByteArray();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> {
            byte[] body = "{\"title\":\"응답 예시\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.createContext("/article", exchange -> {
            byte[] body = "요청 제한 응답".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(429, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.createContext("/gzip", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, gzippedBody.length);
            try (var output = exchange.getResponseBody()) {
                output.write(gzippedBody);
            }
        });
        server.start();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        RestClient client = RestClient.builder().baseUrl(baseUrl)
                .bufferContent((uri, method) -> log.isRecording()).requestInterceptor(log::intercept).build();
        try {
            String reply = log.recordOperation("아이디어 평가 ideaId=7",
                    () -> client.get().uri("/search?q=agent&key=private-token-fixture").retrieve().body(String.class));
            assertThat(reply).isEqualTo("{\"title\":\"응답 예시\"}");
            JdkTransport web = new JdkTransport(Duration.ofSeconds(1), Duration.ofSeconds(1), 8192, log);
            log.recordOperation("웹 원문 및 자료 API", () -> {
                byte[] original = client.get().uri("/gzip").retrieve().body(byte[].class);
                assertThat(original).isEqualTo(gzippedBody);
                try {
                    var response = web.get(URI.create(baseUrl + "/article?private=web-secret"));
                    assertThat(response.status()).isEqualTo(429);
                    assertThat(response.body()).asString(StandardCharsets.UTF_8).isEqualTo("요청 제한 응답");
                } catch (IOException | InterruptedException e) {
                    throw new AssertionError(e);
                }
                assertThatThrownBy(() -> client.get().uri("/article").retrieve().body(String.class))
                        .isInstanceOf(RestClientResponseException.class);
                return null;
            });
        } finally {
            server.stop(0);
        }

        assertThatThrownBy(() -> log.recordOperation("통신 실패 작업",
                () -> client.get().uri("/search").retrieve().body(String.class))).isInstanceOf(ResourceAccessException.class);
        String written = Files.readString(file);
        assertThat(written)
                .containsSubsequence("시작 아이디어 평가 ideaId=7", "/search?q=agent&key=[redacted]", "HTTP 200", "응답 예시",
                        "종료 아이디어 평가 ideaId=7", "======", "시작 웹 원문 및 자료 API",
                        "/gzip", "HTTP 200", "압축된 자료",
                        "/article?[omitted]", "HTTP 429", "요청 제한 응답", "종료 웹 원문 및 자료 API", "======",
                        "시작 통신 실패 작업", "Failure", "종료 통신 실패 작업", "======")
                .doesNotContain("private-token-fixture", "web-secret");
        assertThat(written.lines().filter("======"::equals)).hasSize(3);
    }
}
