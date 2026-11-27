package com.cue.source.fetch;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.http.HttpMethod;

/** JDK HttpClient 전송 계층: 자동 리다이렉트 끔(직접 검사하며 따라감), 타임아웃, 응답 크기 상한까지만 읽는다. */
public class JdkTransport implements WebContentFetcher.Transport {
    private final HttpClient client;
    private final Duration timeout;
    private final int maxBytes;
    private final SourceApiLog apiLog;

    public JdkTransport(Duration connectTimeout, Duration timeout, int maxBytes, SourceApiLog apiLog) {
        this.client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(connectTimeout).build();
        this.timeout = timeout;
        this.maxBytes = maxBytes;
        this.apiLog = apiLog;
    }

    @Override
    public WebContentFetcher.Response get(URI uri) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout).GET()
                .header("User-Agent", "CUE-research-bot")
                .header("Accept", "text/html,text/plain;q=0.9").build();
        long start = System.nanoTime();
        Integer status = null;
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            status = response.statusCode();
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            try (InputStream in = response.body()) {
                byte[] body = in.readNBytes(maxBytes);
                recordResponse(uri, start, status, contentType, body);
                return new WebContentFetcher.Response(status, response.headers().firstValue("Location").orElse(null),
                        contentType, body);
            }
        } catch (IOException | InterruptedException | RuntimeException error) {
            apiLog.failure(HttpMethod.GET, uri, start, status, error, false);
            throw error;
        }
    }

    private void recordResponse(URI uri, long start, int status, String contentType, byte[] body) {
        if (apiLog.isRecording()) {
            try {
                apiLog.response(HttpMethod.GET, uri, start, status, contentType, new ByteArrayInputStream(body), false);
            } catch (IOException error) {
                apiLog.failure(HttpMethod.GET, uri, start, status, error, false);
            }
        }
    }
}
