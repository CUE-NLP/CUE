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
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * 변경이 감지된 source의 상위 N개 항목을 실제로 읽고(원문) → 항목별 Gemini 요약({@code item-summary}) → 소스 단위 최종 요약
 * ({@code trend-summary} 프롬프트를 이 source의 항목 요약들에 적용)을 만들어 DB에 덮어쓴다.
 * 원문은 {@code max-content-chars}로 자르고 저장하지 않는다. 항목 하나가 실패(원문 읽기·Gemini)하면 그 항목만 건너뛰고 로그를 남긴다.
 * 같은 URL의 요약이 {@code summary-reuse-days} 안에 있으면 다시 읽지 않고 재사용한다.
 * 외부 호출 동안 트랜잭션을 잡지 않으려고 @Transactional을 쓰지 않는다 (저장만 짧은 트랜잭션).
 */
@Slf4j
@Service
public class SourceSummaryService {
    private final Map<ApiProvider, SourceClient> clients = new EnumMap<>(ApiProvider.class);
    private final GeminiClient gemini;
    private final PromptLoader prompts;
    private final SourceRepository sources;
    private final SourceItemSummaryRepository itemRepo;
    private final TransactionTemplate tx;
    private final SourceApiProperties props;

    public SourceSummaryService(List<SourceClient> clientList, GeminiClient gemini, PromptLoader prompts,
                                SourceRepository sources, SourceItemSummaryRepository itemRepo,
                                TransactionTemplate tx, SourceApiProperties props) {
        clientList.forEach(c -> clients.put(c.provider(), c));
        this.gemini = gemini;
        this.prompts = prompts;
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

        List<SourceItem> summarized = new ArrayList<>();
        List<LocalDateTime> times = new ArrayList<>();
        for (SourceItem item : changed.items().stream().limit(props.topN()).toList()) {
            SourceItemSummary old = stored.get(item.url());
            if (old != null && props.summaryReuseDays() > 0 && old.getSummarizedAt().isAfter(reuseAfter)) {
                summarized.add(withSummary(item, old.getSummary()));
                times.add(old.getSummarizedAt());
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
                JsonNode result = gemini.generateJson(prompts.render("item-summary", Map.of(
                        "keyword", changed.keyword(), "title", item.title(), "url", item.url(), "content", content)), SourceTextFormat.SUMMARY_SCHEMA);
                String summary = result.path("summary").asString("").strip();
                if (summary.isEmpty()) {
                    continue;
                }
                summarized.add(withSummary(item, summary));
                times.add(LocalDateTime.now());
            } catch (RuntimeException e) {
                log.warn("항목 요약 실패, 건너뜁니다 sourceId={} url={}: {}", changed.sourceId(), item.url(), e.toString());
            }
        }
        if (summarized.isEmpty()) {
            return new SourceSummary(changed, null); // 요약이 없으면 조회한 원래 항목(제목·URL·짧은 요약)을 그대로 쓴다
        }

        SourceCheckResult enriched = SourceCheckResult.changed(changed.sourceId(), changed.provider(), changed.keyword(),
                summarized, changed.checkedAt());
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
        save(changed.sourceId(), summarized, times, sourceSummary);
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
