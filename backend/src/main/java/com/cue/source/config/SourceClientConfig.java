package com.cue.source.config;

import com.cue.source.client.GithubSourceClient;
import com.cue.source.client.HackerNewsSourceClient;
import com.cue.source.client.HuggingFaceSourceClient;
import com.cue.source.client.SearchSettings;
import com.cue.source.client.SemanticScholarSourceClient;
import com.cue.source.client.WikipediaPageviewsSourceClient;
import com.cue.source.client.StackExchangeSourceClient;
import com.cue.source.fetch.JdkTransport;
import com.cue.source.fetch.SourceApiLog;
import com.cue.source.fetch.WebContentFetcher;
import java.net.http.HttpClient;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties({SourceApiProperties.class, com.cue.trend.config.TrendProperties.class})
public class SourceClientConfig {
    private static final String GITHUB_URL = "https://api.github.com";
    private static final String HUGGING_FACE_URL = "https://huggingface.co";
    private static final String STACK_EXCHANGE_URL = "https://api.stackexchange.com";
    private static final String HACKER_NEWS_URL = "https://hn.algolia.com";
    private static final String SEMANTIC_SCHOLAR_URL = "https://api.semanticscholar.org";
    private static final String WIKIPEDIA_URL = "https://en.wikipedia.org";
    private final SourceApiLog apiLog;

    @Bean
    SearchSettings searchSettings(SourceApiProperties props) {
        return SearchSettings.forKeywordSearch(props.topN());
    }

    /** 기사 등 임의의 웹 페이지를 읽는 안전한 가져오기 (SSRF 차단·리다이렉트/크기/시간 제한). */
    @Bean
    WebContentFetcher webContentFetcher(SourceApiProperties props) {
        return new WebContentFetcher(new JdkTransport(props.connectTimeout(), props.readTimeout(), props.fetchMaxBytes(), apiLog),
                props.maxRedirects(), props.maxContentChars());
    }

    @Bean
    GithubSourceClient githubSourceClient(RestClient.Builder builder, SourceApiProperties props, SearchSettings settings) {
        RestClient.Builder b = base(builder, props, url(props.github(), GITHUB_URL))
                .defaultHeader("Accept", "application/vnd.github+json");
        bearer(b, props.github().token());
        return new GithubSourceClient(b.build(), settings);
    }

    @Bean
    HuggingFaceSourceClient huggingFaceSourceClient(RestClient.Builder builder, SourceApiProperties props, SearchSettings settings) {
        String url = url(props.huggingFace(), HUGGING_FACE_URL);
        RestClient.Builder b = base(builder, props, url);
        bearer(b, props.huggingFace().token());
        return new HuggingFaceSourceClient(b.build(), url, settings);
    }

    @Bean
    StackExchangeSourceClient stackExchangeSourceClient(RestClient.Builder builder, SourceApiProperties props, SearchSettings settings) {
        RestClient.Builder b = base(builder, props, url(props.stackExchange(), STACK_EXCHANGE_URL));
        return new StackExchangeSourceClient(b.build(), props.stackExchange().token(), settings);
    }

    /** 키가 필요 없는 Algolia 검색 API. */
    @Bean
    HackerNewsSourceClient hackerNewsSourceClient(RestClient.Builder builder, SourceApiProperties props,
                                                  SearchSettings settings, WebContentFetcher fetcher) {
        return new HackerNewsSourceClient(base(builder, props, url(props.hackerNews(), HACKER_NEWS_URL)).build(), settings, fetcher);
    }

    /** 논문 검색. API 키(선택)는 x-api-key 헤더로 보낸다. 키가 없으면 익명 한도라 429가 잦을 수 있다. */
    @Bean
    SemanticScholarSourceClient semanticScholarSourceClient(RestClient.Builder builder, SourceApiProperties props, SearchSettings settings) {
        return new SemanticScholarSourceClient(base(builder, props, url(props.semanticScholar(), SEMANTIC_SCHOLAR_URL)).build(),
                props.semanticScholar().token(), settings);
    }

    /** 위키백과 검색 + Wikimedia 조회수. Wikimedia 정책상 User-Agent가 필수다. */
    @Bean
    WikipediaPageviewsSourceClient wikipediaPageviewsSourceClient(RestClient.Builder builder, SourceApiProperties props, SearchSettings settings) {
        return new WikipediaPageviewsSourceClient(base(builder, props, url(props.wikipedia(), WIKIPEDIA_URL)).build(),
                props.wikimediaUserAgent(), props.pageviewsBaseUrl(), settings, props.pageviewsDays());
    }

    private static String url(SourceApiProperties.Api api, String defaultUrl) {
        return api.baseUrl().isBlank() ? defaultUrl : api.baseUrl();
    }

    private static void bearer(RestClient.Builder b, String token) {
        if (!token.isBlank()) {
            b.defaultHeader("Authorization", "Bearer " + token);
        }
    }

    private RestClient.Builder base(RestClient.Builder builder, SourceApiProperties props, String baseUrl) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(props.connectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(props.readTimeout());
        return builder.clone().baseUrl(baseUrl).requestFactory(factory)
                .bufferContent((uri, method) -> apiLog.isRecording())
                .requestInterceptor(apiLog::intercept);
    }
}
