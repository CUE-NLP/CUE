package com.cue.source.config;

import com.cue.source.client.GithubSourceClient;
import com.cue.source.client.HackerNewsSourceClient;
import com.cue.source.client.HuggingFaceSourceClient;
import com.cue.source.client.StackExchangeSourceClient;
import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(SourceApiProperties.class)
public class SourceClientConfig {
    private static final String GITHUB_URL = "https://api.github.com";
    private static final String HUGGING_FACE_URL = "https://huggingface.co";
    private static final String STACK_EXCHANGE_URL = "https://api.stackexchange.com";
    private static final String HACKER_NEWS_URL = "https://hn.algolia.com";

    @Bean
    GithubSourceClient githubSourceClient(RestClient.Builder builder, SourceApiProperties props) {
        RestClient.Builder b = base(builder, props, url(props.github(), GITHUB_URL))
                .defaultHeader("Accept", "application/vnd.github+json");
        bearer(b, props.github().token());
        return new GithubSourceClient(b.build());
    }

    @Bean
    HuggingFaceSourceClient huggingFaceSourceClient(RestClient.Builder builder, SourceApiProperties props) {
        String url = url(props.huggingFace(), HUGGING_FACE_URL);
        RestClient.Builder b = base(builder, props, url);
        bearer(b, props.huggingFace().token());
        return new HuggingFaceSourceClient(b.build(), url);
    }

    @Bean
    StackExchangeSourceClient stackExchangeSourceClient(RestClient.Builder builder, SourceApiProperties props) {
        RestClient.Builder b = base(builder, props, url(props.stackExchange(), STACK_EXCHANGE_URL));
        return new StackExchangeSourceClient(b.build(), props.stackExchange().token());
    }

    /** 키가 필요 없는 Algolia 검색 API. */
    @Bean
    HackerNewsSourceClient hackerNewsSourceClient(RestClient.Builder builder, SourceApiProperties props) {
        return new HackerNewsSourceClient(base(builder, props, url(props.hackerNews(), HACKER_NEWS_URL)).build());
    }

    private static String url(SourceApiProperties.Api api, String defaultUrl) {
        return api.baseUrl().isBlank() ? defaultUrl : api.baseUrl();
    }

    private static void bearer(RestClient.Builder b, String token) {
        if (!token.isBlank()) {
            b.defaultHeader("Authorization", "Bearer " + token);
        }
    }

    private static RestClient.Builder base(RestClient.Builder builder, SourceApiProperties props, String baseUrl) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(props.connectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(props.readTimeout());
        return builder.clone().baseUrl(baseUrl).requestFactory(factory);
    }
}
