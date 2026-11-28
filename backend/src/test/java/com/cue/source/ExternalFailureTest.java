package com.cue.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.cue.global.error.CustomException;
import com.cue.global.error.ExternalFailure;
import com.cue.source.client.GithubSourceClient;
import com.cue.source.client.SearchSettings;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** 실제 HTTP 연결로 실패 원인 문구를 확인한다 (운영과 같은 JDK HttpClient 구성, 로컬 서버만 사용). */
class ExternalFailureTest {
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String start(int status, String body, long delayMs) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static String reasonOfSearch(String baseUrl) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newHttpClient());
        factory.setReadTimeout(Duration.ofMillis(300));
        var client = new GithubSourceClient(RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build(),
                SearchSettings.forKeywordSearch(5));
        return ExternalFailure.reason(assertThrows(CustomException.class, () -> client.search("k")));
    }

    @Test
    void HTTP_상태에_따라_원인을_알려준다() throws IOException {
        assertEquals("403 접근 거부(호출 한도 초과 또는 권한 확인)", reasonOfSearch(start(403, "{}", 0)));
        server.stop(0);
        assertEquals("500 외부 서버 오류", reasonOfSearch(start(500, "{}", 0)));
    }

    @Test
    void 늦은_응답과_잘못된_본문을_구분한다() throws IOException {
        assertEquals("응답 시간 초과", reasonOfSearch(start(200, "{}", 1_500)));
        server.stop(0);
        assertEquals("응답 해석 실패", reasonOfSearch(start(200, "<html>", 0)));
    }

    @Test
    void 연결하지_못하면_연결_실패다() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        assertEquals("연결 실패", reasonOfSearch("http://127.0.0.1:" + closedPort));
    }
}
