package com.cue.source.fetch;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** JDK HttpClient 전송 계층: 자동 리다이렉트 끔(직접 검사하며 따라감), 타임아웃, 응답 크기 상한까지만 읽는다. */
public class JdkTransport implements WebContentFetcher.Transport {
    private final HttpClient client;
    private final Duration timeout;
    private final int maxBytes;

    public JdkTransport(Duration connectTimeout, Duration timeout, int maxBytes) {
        this.client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(connectTimeout).build();
        this.timeout = timeout;
        this.maxBytes = maxBytes;
    }

    @Override
    public WebContentFetcher.Response get(URI uri) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout).GET()
                .header("User-Agent", "CUE-research-bot")
                .header("Accept", "text/html,text/plain;q=0.9").build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = response.body()) {
            byte[] body = in.readNBytes(maxBytes);
            return new WebContentFetcher.Response(response.statusCode(),
                    response.headers().firstValue("Location").orElse(null),
                    response.headers().firstValue("Content-Type").orElse(""), body);
        }
    }
}
