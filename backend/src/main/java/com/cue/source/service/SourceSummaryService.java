package com.cue.source.service;

import com.cue.global.ai.GeminiClient;
import com.cue.global.ai.PromptLoader;
import com.cue.source.client.SourceClient;
import com.cue.source.config.SourceApiProperties;
import com.cue.source.dto.SourceCheckResult;
import com.cue.source.dto.SourceItem;
import com.cue.source.dto.SourceSummary;
import com.cue.source.entity.ApiProvider;
import com.cue.source.entity.Source;
import com.cue.source.entity.SourceItemSummary;
import com.cue.source.repository.SourceItemSummaryRepository;
import com.cue.source.repository.SourceRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * 변경이 감지된 source의 상위 N개 항목을 실제로 읽고(원문) → <b>항목 일괄 요약 1회</b>({@code item-summary-batch}, {@link ItemBatchSummarizer})
 * → 소스 단위 최종 요약 1회({@code trend-summary} 프롬프트를 항목 요약들에 적용)로 만들어 DB에 덮어쓴다. source당 Gemini 호출은 최대 2회다.
 *
 * <p>같은 URL의 요약이 {@code summary-reuse-days} 안에 있으면 그 항목은 일괄 호출에서 빼고 저장된 요약을 쓴다(전부 재사용이면 소스 요약 1회만).
 * 원문 읽기에 실패한 항목은 건너뛰고 나머지로 일괄 호출한다. 일괄 호출이 빠뜨린 항목은 저장하지 않고 원래 조회한 짧은 요약으로 대체한다.
 * 일괄 호출 자체가 실패하면 {@link SourceSummary#degraded()}로 표시한 채 원래 조회한 항목을 돌려준다(저장 안 함).
 * 외부 호출 동안 트랜잭션을 잡지 않으려고 @Transactional을 쓰지 않는다 (저장만 짧은 트랜잭션).
 *
 * <p>호출 수를 줄이려 한 호출에서 항목 요약과 소스 요약을 함께 받는 안도 있으나, 응답이 길어져 JSON이 잘리거나 품질이 떨어질 위험이 있고
 * 전부 재사용된 날에는 어차피 소스 요약 호출이 따로 필요해서 2회 방식을 택했다.
 */
@Slf4j
@Service
public class SourceSummaryService {
    private final Map<ApiProvider, SourceClient> clients = new EnumMap<>(ApiProvider.class);
    private final GeminiClient gemini;
    private final PromptLoader prompts;
    private final ItemBatchSummarizer batch;
    private final SourceRepository sources;
    private final SourceItemSummaryRepository itemRepo;
    private final TransactionTemplate tx;
    private final SourceApiProperties props;

    public SourceSummaryService(List<SourceClient> clientList, GeminiClient gemini, PromptLoader prompts,
                                ItemBatchSummarizer batch, SourceRepository sources, SourceItemSummaryRepository itemRepo,
                                TransactionTemplate tx, SourceApiProperties props) {
        clientList.forEach(c -> clients.put(c.provider(), c));
        this.gemini = gemini;
        this.prompts = prompts;
        this.batch = batch;
        this.sources = sources;
        this.itemRepo = itemRepo;
        this.tx = tx;
        this.props = props;
    }

    public SourceSummary summarize(SourceCheckResult changed) {
        SourceClient client = clients.get(changed.provider());
        Map<String, SourceItemSummary> stored = new HashMap<>();
        itemRepo.findBySourceId(changed.sourceId()).forEach(i -> stored.put(i.getUrl(), i));
        LocalDateTime reuseAfter = LocalDateTime.now().minusDays(props.summaryReuseDays());

        List<SourceItem> top = changed.items().stream().limit(props.topN()).toList();
        Map<String, SourceItemSummary> reused = new HashMap<>();
        List<ItemBatchSummarizer.Entry> toSummarize = new ArrayList<>();
        for (SourceItem item : top) {
            SourceItemSummary old = stored.get(item.url());
            if (old != null && props.summaryReuseDays() > 0 && old.getSummarizedAt().isAfter(reuseAfter)) {
                reused.put(item.url(), old);
                continue;
            }
            try {
                String content = client == null ? "" : client.fetchContent(item);
                content = content == null ? "" : content.strip();
                if (content.length() > props.maxContentChars()) {
                    content = content.substring(0, props.maxContentChars());
                }
                if (content.isEmpty()) {
                    log.warn("원문이 비어 항목을 건너뜁니다 sourceId={} url={}", changed.sourceId(), item.url());
                    continue;
                }
                toSummarize.add(new ItemBatchSummarizer.Entry(item, content));
            } catch (RuntimeException e) {
                log.warn("원문 읽기 실패, 항목을 건너뜁니다 sourceId={} url={}: {}", changed.sourceId(), item.url(), e.toString());
            }
        }

        Map<String, String> fresh = Map.of();
        if (!toSummarize.isEmpty()) {
            try {
                fresh = batch.summarize("item-summary-batch", Map.of("keyword", changed.keyword()), toSummarize);
            } catch (RuntimeException e) {
                log.warn("항목 일괄 요약 실패, 원래 조회한 항목으로 대체합니다 sourceId={}: {}", changed.sourceId(), e.toString());
                return new SourceSummary(changed, null, true);
            }
        }

        // 조회 순서대로: 재사용 / 새 요약 / (읽었지만 요약이 빠진 항목은 원래 짧은 요약, 저장은 안 함) / (읽지 못한 항목은 제외)
        Set<String> readable = new HashSet<>();
        toSummarize.forEach(e -> readable.add(e.item().url()));
        List<SourceItem> enrichedItems = new ArrayList<>();
        List<SourceItem> saveItems = new ArrayList<>();
        List<LocalDateTime> times = new ArrayList<>();
        for (SourceItem item : top) {
            SourceItemSummary old = reused.get(item.url());
            String summary = fresh.get(item.url());
            if (old != null) {
                SourceItem it = withSummary(item, old.getSummary());
                enrichedItems.add(it);
                saveItems.add(it);
                times.add(old.getSummarizedAt());
            } else if (summary != null) {
                SourceItem it = withSummary(item, summary);
                enrichedItems.add(it);
                saveItems.add(it);
                times.add(LocalDateTime.now());
            } else if (readable.contains(item.url())) {
                enrichedItems.add(item); // 일괄 응답이 빠뜨린 항목
            }
        }
        if (saveItems.isEmpty()) {
            return new SourceSummary(changed, null); // 요약이 하나도 없으면 조회한 원래 항목(제목·URL·짧은 요약)을 그대로 쓴다
        }

        SourceCheckResult enriched = SourceCheckResult.changed(changed.sourceId(), changed.provider(), changed.keyword(),
                enrichedItems, changed.checkedAt());
        String sourceSummary = null;
        try {
            JsonNode result = gemini.generateJson(prompts.render("trend-summary", Map.of(
                    "keywords", changed.keyword(), "items", SourceTextFormat.formatSources(List.of(enriched)))),
                    SourceTextFormat.SUMMARY_SCHEMA);
            String text = result.path("summary").asString("").strip();
            sourceSummary = text.isEmpty() ? null : text;
        } catch (RuntimeException e) {
            log.warn("소스 단위 요약 실패 sourceId={}: {}", changed.sourceId(), e.toString());
        }
        save(changed.sourceId(), saveItems, times, sourceSummary);
        return new SourceSummary(enriched, sourceSummary);
    }

    /** source의 항목 요약과 최종 요약을 덮어쓴다. */
    private void save(Long sourceId, List<SourceItem> items, List<LocalDateTime> times, String sourceSummary) {
        tx.executeWithoutResult(status -> {
            Source source = sources.findById(sourceId).orElse(null);
            if (source == null) {
                return;
            }
            itemRepo.deleteBySourceId(sourceId);
            List<SourceItemSummary> rows = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) {
                SourceItem it = items.get(i);
                rows.add(new SourceItemSummary(source, it.url(), it.title(), it.publishedAt(), it.metrics(),
                        it.summary(), times.get(i)));
            }
            itemRepo.saveAll(rows);
            source.updateSummary(sourceSummary);
        });
    }

    private static SourceItem withSummary(SourceItem item, String summary) {
        return new SourceItem(item.title(), item.url(), summary, item.publishedAt(), item.metrics());
    }
}
