package com.cue.source.service;

import com.cue.global.cache.TtlCache;
import com.cue.global.error.CustomException;
import com.cue.global.error.ErrorLocation;
import com.cue.global.error.ErrorReason;
import com.cue.source.client.SourceClient;
import com.cue.source.config.SourceApiProperties;
import com.cue.source.dto.SourceCheckResult;
import com.cue.source.dto.SourceItem;
import com.cue.source.entity.ApiProvider;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 평가 대상 source의 상위 N개 항목을 읽고, 평가 프롬프트에 그대로 넣을 <b>원문 발췌</b>를 만든다. <b>Gemini를 부르지 않는다.</b>
 *
 * <p>예전에는 항목 일괄 요약 1회 + 소스 요약 1회(source당 2회)를 쓰고 그 요약을 다시 평가에 넣었다. 지금은 API가 주는 짧은 설명과
 * 원문 앞부분({@link TextExcerpt})을 합쳐 {@link SourceItem#summary()} 자리에 넣고, 아이디어 하나를 한 번의 평가 호출로 처리한다.
 * 무료 티어의 병목이 토큰이 아니라 요청 수이기 때문이고, 이중 요약으로 정보가 깎이는 것도 막는다.
 *
 * <p>읽은 발췌는 {@link #CACHE_TTL} 동안 메모리에만 두고 재사용한다. 같은 아이디어를 다시 평가할 때 같은 원문을 또 읽지 않아
 * GitHub README(익명 시간당 60회)·Stack Exchange(키 없이 하루 300회) 한도를 아낀다. 읽기에 실패한 것은 두지 않는다.
 *
 * <p>발췌는 DB에 저장하지 않는다(외부 원문 저장 금지 방침). 원문 읽기에 실패한 항목도 버리지 않고 API가 준 짧은 설명만으로 남긴다.
 * API 설명이 이미 발췌 길이만큼 충분하면 원문을 다시 읽지 않는다(GitHub README 같은 호출의 익명 한도를 아낀다).
 * 외부 HTTP만 쓰므로 @Transactional을 쓰지 않는다.
 */
@Slf4j
@Service
public class SourceExcerptService {
    /** 항목 발췌에서 API 설명이 차지할 수 있는 최대 길이. 나머지는 원문 발췌에 쓴다. */
    private static final int MAX_DESCRIPTION_CHARS = 300;
    private static final int MIN_EXCERPT_CHARS = 100;
    static final Duration CACHE_TTL = Duration.ofHours(24);
    private static final int CACHE_MAX_ENTRIES = 1_000;

    private final Map<ApiProvider, SourceClient> clients = new EnumMap<>(ApiProvider.class);
    private final SourceApiProperties props;
    /** key: provider + URL + 발췌 길이. 값은 정리한 발췌(최대 수백~수천 자). */
    private final TtlCache<String, String> excerpts = new TtlCache<>(CACHE_TTL, CACHE_MAX_ENTRIES, Clock.systemUTC());

    public SourceExcerptService(List<SourceClient> clientList, SourceApiProperties props) {
        clientList.forEach(c -> clients.put(c.provider(), c));
        this.props = props;
    }

    /** 상위 N개 항목의 {@code summary}를 원문 발췌로 바꾼 조회 결과를 돌려준다. */
    public SourceCheckResult excerpt(SourceCheckResult result) {
        SourceClient client = clients.get(result.provider());
        List<SourceItem> items = new ArrayList<>();
        for (SourceItem item : result.items().stream().limit(props.topN()).toList()) {
            items.add(new SourceItem(item.title(), item.url(), summaryOf(client, result, item), item.publishedAt(), item.metrics()));
        }
        return new SourceCheckResult(result.sourceId(), result.provider(), result.keyword(), result.status(), items,
                result.checkedAt(), result.errorMessage());
    }

    private String summaryOf(SourceClient client, SourceCheckResult result, SourceItem item) {
        String description = clip(normalize(item.summary()), MAX_DESCRIPTION_CHARS);
        int budget = Math.max(props.excerptChars() - description.length() - 3, MIN_EXCERPT_CHARS);
        if (client == null || normalize(item.summary()).length() >= props.excerptChars()) {
            return clip(normalize(item.summary()), props.excerptChars());
        }
        String cacheKey = result.provider() + "|" + item.url() + "|" + budget;
        String excerpt = excerpts.get(cacheKey);
        if (excerpt != null) {
            return combine(description, excerpt);
        }
        excerpt = "";
        try {
            excerpt = TextExcerpt.of(client.fetchContent(item), budget);
            if (!excerpt.isEmpty()) {
                excerpts.put(cacheKey, excerpt);
            }
        } catch (RuntimeException e) {
            // 웹 페이지를 직접 읽다 실패하면 그 사이트가, 아니면 provider의 API가 실패한 것이다
            String api = e instanceof CustomException custom && custom.getDetail() != null && custom.getDetail().api() != null
                    ? custom.getDetail().api() : result.provider().api().label();
            log.warn("원문 읽기 실패, API 설명만 씁니다 sourceId={} url={} 위치={}, API={}, 원인={}", result.sourceId(), item.url(),
                    ErrorLocation.SOURCE_CONTENT.label(), api, e instanceof CustomException ? ErrorReason.of(e) : e.toString());
        }
        return combine(description, excerpt);
    }

    private static String combine(String description, String excerpt) {
        if (excerpt.isEmpty()) {
            return description;
        }
        if (description.isEmpty() || excerpt.startsWith(description)) {
            return excerpt;
        }
        return description + " — " + excerpt;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").strip();
    }

    private static String clip(String value, int max) {
        return value.length() > max ? value.substring(0, max) + "…" : value;
    }
}
