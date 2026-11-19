package com.cue.trend.service;

import com.cue.global.ai.GeminiClient;
import com.cue.global.ai.PromptLoader;
import com.cue.source.client.SearchSettings;
import com.cue.source.client.SourceClient;
import com.cue.source.config.SourceApiProperties;
import com.cue.source.dto.SourceCheckResult;
import com.cue.source.dto.SourceItem;
import com.cue.source.entity.ApiProvider;
import com.cue.source.service.ItemBatchSummarizer;
import com.cue.source.service.SourceHasher;
import com.cue.source.service.SourceTextFormat;
import com.cue.trend.config.TrendProperties;
import com.cue.trend.dto.TrendSnapshot;
import com.cue.trend.entity.Trend;
import com.cue.trend.entity.TrendItem;
import com.cue.trend.entity.TrendSource;
import com.cue.trend.repository.TrendItemRepository;
import com.cue.trend.repository.TrendRepository;
import com.cue.trend.repository.TrendSourceRepository;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * 글로벌 트렌드(아이디어·키워드와 무관, 5번째 평가 기준 TREND의 근거)를 하루 1회 모은다.
 *
 * <ol>
 *   <li>고정 provider {@link ApiProvider#TREND_PROVIDERS}(HN·GitHub·Hugging Face)를 키워드 없이 "급상승" 기준으로 조회 (상위 {@code cue.trend.top-n})</li>
 *   <li>항목들의 해시({@link SourceHasher})가 가장 최근 트렌드와 같으면 재요약하지 않고 그 행을 그대로 쓴다</li>
 *   <li>다르면 항목 원문 읽기 → provider당 항목 일괄 요약 1회({@code trend-item-summary-batch}) → provider별 요약 → 최종 요약({@code global-trend-summary}) 후 새 행으로 저장.
 *       같은 URL의 항목 요약은 {@code cue.source.summary-reuse-days} 안이면 이전 행에서 재사용한다</li>
 * </ol>
 * 결과는 그날 첫 호출에서 만들어 메모리에 두고(재시작하면 DB의 오늘 행을 쓴다) 이후엔 그대로 돌려준다.
 * 실패하면 null을 돌려주고 {@link #RETRY_AFTER} 동안은 다시 시도하지 않는다 (아이디어마다 같은 실패를 반복하지 않으려고).
 * 외부 호출 동안 DB 트랜잭션을 잡지 않으려고 저장만 짧은 트랜잭션으로 한다.
 */
@Slf4j
@Service
public class GlobalTrendService {
    /** 수집·요약이 실패했을 때 다시 시도하기까지의 최소 간격. */
    static final Duration RETRY_AFTER = Duration.ofMinutes(30);

    private final Map<ApiProvider, SourceClient> clients = new EnumMap<>(ApiProvider.class);
    private final GeminiClient gemini;
    private final PromptLoader prompts;
    private final ItemBatchSummarizer batch;
    private final TrendRepository trends;
    private final TrendItemRepository trendItems;
    private final TrendSourceRepository trendSources;
    private final TransactionTemplate tx;
    private final SourceApiProperties sourceProps;
    private final SearchSettings settings;

    private TrendSnapshot cached;
    private LocalDate cachedOn;
    private LocalDateTime failedAt;

    public GlobalTrendService(List<SourceClient> clientList, GeminiClient gemini, PromptLoader prompts, ItemBatchSummarizer batch,
                              TrendRepository trends, TrendItemRepository trendItems, TrendSourceRepository trendSources,
                              TransactionTemplate tx, SourceApiProperties sourceProps, TrendProperties props) {
        clientList.forEach(c -> clients.put(c.provider(), c));
        this.gemini = gemini;
        this.prompts = prompts;
        this.batch = batch;
        this.trends = trends;
        this.trendItems = trendItems;
        this.trendSources = trendSources;
        this.tx = tx;
        this.sourceProps = sourceProps;
        this.settings = new SearchSettings(props.topN(), props.days());
    }

    /**
     * 오늘의 글로벌 트렌드. 오늘 이미 만들었으면 그것을, 아니면 수집해서 만든다. 하루에 한 번만 외부 호출을 한다.
     *
     * @return 트렌드, 만들지 못했으면 null (그날 TREND 평가는 건너뛰고 다음 배치에서 다시 시도한다)
     */
    public synchronized TrendSnapshot today() {
        LocalDate today = LocalDate.now();
        if (cached != null && today.equals(cachedOn)) {
            return cached;
        }
        if (failedAt != null && failedAt.plus(RETRY_AFTER).isAfter(LocalDateTime.now())) {
            return null;
        }
        try {
            TrendSnapshot snapshot = collect(today, false).snapshot();
            cached = snapshot;
            cachedOn = today;
            failedAt = null;
            return snapshot;
        } catch (RuntimeException e) {
            failedAt = LocalDateTime.now();
            log.warn("글로벌 트렌드를 만들지 못했습니다. TREND 평가는 건너뜁니다: {}", e.toString());
            return null;
        }
    }

    /** 오늘의 트렌드가 이미 있는지 (메모리 또는 DB의 오늘 행). 외부 호출은 하지 않는다. */
    public synchronized boolean isCurrent() {
        LocalDate today = LocalDate.now();
        if (cached != null && today.equals(cachedOn)) {
            return true;
        }
        return tx.execute(s -> trends.findFirstByOrderByIdDesc()
                .map(t -> t.getCreatedAt().toLocalDate().equals(today)).orElse(false));
    }

    /**
     * 트렌드를 지금 다시 수집·요약한다 (시연용 수동 실행). {@code force=false}면 오늘 이미 있을 때 아무것도 하지 않는다(하루 1회·해시 규칙 그대로).
     * {@code force=true}면 오늘 캐시와 "해시가 같으면 재사용" 규칙을 무시하고 새 행으로 다시 만든다(같은 URL 항목 요약 재사용은 유지). 실패하면 로그만 남긴다.
     *
     * @return 새 트렌드 행을 만들었으면 true
     */
    public synchronized boolean refresh(boolean force) {
        LocalDate today = LocalDate.now();
        if (!force && cached != null && today.equals(cachedOn)) {
            return false;
        }
        try {
            Collected c = collect(today, force);
            cached = c.snapshot();
            cachedOn = today;
            failedAt = null;
            return c.created();
        } catch (RuntimeException e) {
            failedAt = LocalDateTime.now();
            log.warn("글로벌 트렌드 수동 갱신에 실패했습니다: {}", e.toString());
            return false;
        }
    }

    private record Collected(TrendSnapshot snapshot, boolean created) {}

    private Collected collect(LocalDate today, boolean force) {
        Optional<Trend> latest = tx.execute(s -> trends.findFirstByOrderByIdDesc());
        if (!force && latest.isPresent() && latest.get().getCreatedAt().toLocalDate().equals(today)) {
            return new Collected(load(latest.get().getId()), false); // 오늘 이미 만들어 둠 (앱 재시작 등)
        }

        Map<ApiProvider, List<SourceItem>> found = new LinkedHashMap<>();
        for (ApiProvider provider : ApiProvider.TREND_PROVIDERS) {
            SourceClient client = clients.get(provider);
            try {
                List<SourceItem> items = client == null ? List.of() : client.trending(settings);
                if (!items.isEmpty()) {
                    found.put(provider, items);
                }
            } catch (RuntimeException e) {
                log.warn("트렌드 조회 실패 provider={}: {}", provider, e.toString());
            }
        }
        if (found.isEmpty()) {
            throw new IllegalStateException("트렌드 항목을 하나도 조회하지 못했습니다");
        }

        String hash = SourceHasher.hash(found.values().stream().flatMap(List::stream).toList());
        if (!force && latest.isPresent() && latest.get().getContentHash().equals(hash)) {
            log.info("글로벌 트렌드 상위 항목이 어제와 같아 기존 트렌드를 그대로 씁니다 trendId={}", latest.get().getId());
            return new Collected(load(latest.get().getId()), false);
        }
        return new Collected(summarizeAndSave(found, hash, latest.map(Trend::getId).orElse(null)), true);
    }

    private TrendSnapshot summarizeAndSave(Map<ApiProvider, List<SourceItem>> found, String hash, Long previousId) {
        Map<String, TrendItem> previous = new HashMap<>();
        if (previousId != null) {
            tx.execute(s -> trendItems.findByTrendIdOrderById(previousId)).forEach(i -> previous.put(i.getUrl(), i));
        }
        LocalDateTime reuseAfter = LocalDateTime.now().minusDays(sourceProps.summaryReuseDays());

        Map<ApiProvider, List<SourceItem>> summarized = new LinkedHashMap<>();
        Map<ApiProvider, List<LocalDateTime>> times = new LinkedHashMap<>();
        Map<ApiProvider, String> providerSummaries = new LinkedHashMap<>();
        for (Map.Entry<ApiProvider, List<SourceItem>> e : found.entrySet()) {
            // 같은 URL의 최근 요약은 재사용하고, 나머지는 원문을 읽어 provider당 한 번의 일괄 호출로 요약한다
            Map<String, TrendItem> reusedItems = new HashMap<>();
            List<ItemBatchSummarizer.Entry> toSummarize = new ArrayList<>();
            for (SourceItem item : e.getValue()) {
                TrendItem old = previous.get(item.url());
                if (old != null && sourceProps.summaryReuseDays() > 0 && old.getSummarizedAt().isAfter(reuseAfter)) {
                    reusedItems.put(item.url(), old);
                } else {
                    readContent(clients.get(e.getKey()), item).ifPresent(c -> toSummarize.add(new ItemBatchSummarizer.Entry(item, c)));
                }
            }
            Map<String, String> fresh = Map.of();
            if (!toSummarize.isEmpty()) {
                try {
                    fresh = batch.summarize("trend-item-summary-batch", Map.of(), toSummarize);
                } catch (RuntimeException ex) { // 일괄 호출 실패: 이 provider는 재사용 항목만으로 진행한다
                    log.warn("트렌드 항목 일괄 요약 실패 provider={}: {}", e.getKey(), ex.toString());
                }
            }
            List<SourceItem> items = new ArrayList<>();
            List<LocalDateTime> at = new ArrayList<>();
            for (SourceItem item : e.getValue()) {
                TrendItem old = reusedItems.get(item.url());
                if (old != null) {
                    items.add(withSummary(item, old.getSummary()));
                    at.add(old.getSummarizedAt());
                } else if (fresh.containsKey(item.url())) {
                    items.add(withSummary(item, fresh.get(item.url())));
                    at.add(LocalDateTime.now());
                }
            }
            if (items.isEmpty()) {
                continue; // 읽을 수 있었던 항목이 없는 provider는 트렌드에서 뺀다
            }
            summarized.put(e.getKey(), items);
            times.put(e.getKey(), at);
            try {
                String text = summarizeLines(SourceTextFormat.formatSources(List.of(asResult(e.getKey(), items))));
                if (text != null) {
                    providerSummaries.put(e.getKey(), text);
                }
            } catch (RuntimeException ex) { // provider 요약이 없어도 최종 요약은 그 provider의 항목 목록으로 만든다
                log.warn("트렌드 provider 요약 실패 provider={}: {}", e.getKey(), ex.toString());
            }
        }
        if (summarized.isEmpty()) {
            throw new IllegalStateException("요약할 수 있는 트렌드 항목이 없습니다");
        }

        // 최종 요약: provider별 요약(없으면 그 provider의 항목 목록)을 합쳐서
        StringBuilder lines = new StringBuilder();
        for (ApiProvider p : summarized.keySet()) {
            String text = providerSummaries.get(p);
            lines.append(text != null ? "[" + p + "] " + text.replaceAll("\\s+", " ")
                    : SourceTextFormat.formatSources(List.of(asResult(p, summarized.get(p))))).append("\n");
        }
        String finalSummary = summarizeLines(lines.toString().strip());
        if (finalSummary == null) {
            throw new IllegalStateException("최종 트렌드 요약이 비어 있습니다");
        }

        Long id = tx.execute(s -> {
            Trend trend = trends.save(new Trend(finalSummary, List.copyOf(summarized.keySet()), hash));
            providerSummaries.forEach((p, text) -> trendSources.save(new TrendSource(trend, p, text)));
            for (Map.Entry<ApiProvider, List<SourceItem>> e : summarized.entrySet()) {
                List<LocalDateTime> at = times.get(e.getKey());
                for (int i = 0; i < e.getValue().size(); i++) {
                    SourceItem it = e.getValue().get(i);
                    trendItems.save(new TrendItem(trend, e.getKey(), it.url(), it.title(), it.publishedAt(), it.metrics(),
                            it.summary(), at.get(i)));
                }
            }
            return trend.getId();
        });
        return load(id);
    }

    /** 항목 원문을 읽는다. 읽기에 실패하거나 비어 있으면 빈 값 (그 항목만 건너뛴다). */
    private java.util.Optional<String> readContent(SourceClient client, SourceItem item) {
        try {
            String content = client == null ? "" : client.fetchContent(item);
            content = content == null ? "" : content.strip();
            if (content.length() > sourceProps.maxContentChars()) {
                content = content.substring(0, sourceProps.maxContentChars());
            }
            if (content.isEmpty()) {
                log.warn("트렌드 항목 원문이 비어 건너뜁니다 url={}", item.url());
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(content);
        } catch (RuntimeException e) {
            log.warn("트렌드 항목 원문 읽기 실패, 건너뜁니다 url={}: {}", item.url(), e.toString());
            return java.util.Optional.empty();
        }
    }

    /** {@code global-trend-summary} 호출. 빈 요약이면 null, Gemini 실패는 예외를 던진다 (호출한 쪽이 처리). */
    private String summarizeLines(String items) {
        JsonNode result = gemini.generateJson(prompts.render("global-trend-summary", Map.of("items", items)),
                SourceTextFormat.SUMMARY_SCHEMA);
        String text = result.path("summary").asString("").strip();
        return text.isEmpty() ? null : text;
    }

    /** DB의 트렌드 한 건을 값 객체로 읽는다. */
    private TrendSnapshot load(Long trendId) {
        return tx.execute(s -> {
            Trend trend = trends.findById(trendId).orElseThrow();
            Map<ApiProvider, List<SourceItem>> byProvider = new LinkedHashMap<>();
            for (TrendItem i : trendItems.findByTrendIdOrderById(trendId)) {
                byProvider.computeIfAbsent(i.getProvider(), k -> new ArrayList<>())
                        .add(new SourceItem(i.getTitle(), i.getUrl(), i.getSummary(),
                                i.getPublishedAt() == null ? "" : i.getPublishedAt(),
                                i.getMetrics() == null ? "" : i.getMetrics()));
            }
            List<SourceCheckResult> sources = byProvider.entrySet().stream()
                    .map(e -> asResult(e.getKey(), e.getValue())).toList();
            return new TrendSnapshot(trend.getId(), trend.getSummary(), trend.getCreatedAt(), sources);
        });
    }

    private static SourceCheckResult asResult(ApiProvider provider, List<SourceItem> items) {
        return SourceCheckResult.changed(null, provider, "", items, LocalDateTime.now());
    }

    private static SourceItem withSummary(SourceItem item, String summary) {
        return new SourceItem(item.title(), item.url(), summary, item.publishedAt(), item.metrics());
    }
}
