package com.cue.source;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cue.global.ai.GeminiClient;
import com.cue.global.ai.PromptLoader;
import com.cue.global.error.CustomException;
import com.cue.global.error.ErrorCode;
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
import com.cue.source.service.ItemBatchSummarizer;
import com.cue.source.service.SourceSummaryService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

class SourceSummaryServiceTest {
    private final GeminiClient gemini = mock(GeminiClient.class);
    private final SourceRepository sources = mock(SourceRepository.class);
    private final SourceItemSummaryRepository itemRepo = mock(SourceItemSummaryRepository.class);
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final List<String> fetched = new ArrayList<>();
    private boolean failSecond = false;
    private final Source source = new Source(null, ApiProvider.GITHUB, "ai agent");

    private final SourceClient client = new SourceClient() {
        public ApiProvider provider() { return ApiProvider.GITHUB; }
        public List<SourceItem> search(String k) { return List.of(); }
        public String fetchContent(SourceItem item) {
            fetched.add(item.url());
            if (failSecond && item.url().endsWith("/2")) throw new CustomException(ErrorCode.EXTERNAL_SERVER_ERROR);
            return "원문 of " + item.title() + " ".repeat(5) + "x".repeat(20000);
        }
    };

    private SourceSummaryService service(int topN, int reuseDays) {
        SourceApiProperties props = new SourceApiProperties(Duration.ofSeconds(3), Duration.ofSeconds(5), topN, 30,
                8000, 1048576, 3, reuseDays, 4000, 16000, null, null, null, null);
        return new SourceSummaryService(List.of(client), gemini, new PromptLoader(),
                new ItemBatchSummarizer(gemini, new PromptLoader(), props), sources, itemRepo,
                new TransactionTemplate(mock(PlatformTransactionManager.class)), props);
    }

    private SourceCheckResult changed(int n) {
        List<SourceItem> items = new ArrayList<>();
        for (int i = 1; i <= n; i++) items.add(new SourceItem("repo" + i, "https://github.com/o/" + i, "d", "2026-06-01", "stars=" + i));
        return SourceCheckResult.changed(9L, ApiProvider.GITHUB, "ai agent", items, LocalDateTime.now());
    }

    private final List<String> prompts = new ArrayList<>();
    /** 일괄 응답에서 빼먹을 URL 끝 번호. */
    private String omit = null;

    @BeforeEach
    void setUp() {
        when(sources.findById(9L)).thenReturn(Optional.of(source));
        when(itemRepo.findBySourceId(9L)).thenReturn(List.of());
        doAnswer(inv -> {
            String prompt = inv.getArgument(0);
            prompts.add(prompt);
            if (!prompt.contains("[항목 목록]")) return mapper.readTree("{\"summary\":\"소스 요약\"}");
            StringBuilder items = new StringBuilder();
            var m = java.util.regex.Pattern.compile("URL: (\\S+)").matcher(prompt);
            while (m.find()) {
                if (omit != null && m.group(1).endsWith("/" + omit)) continue;
                if (items.length() > 0) items.append(",");
                items.append("{\"url\":\"").append(m.group(1)).append("\",\"summary\":\"항목 요약\"}");
            }
            return mapper.readTree("{\"items\":[" + items + "]}");
        }).when(gemini).generateJson(anyString(), anyMap());
    }

    @Test
    void 항목은_한_번에_일괄_요약하고_소스_요약_1회로_source당_호출은_2회이다() {
        SourceSummary result = service(5, 7).summarize(changed(3));

        assertEquals(List.of("https://github.com/o/1", "https://github.com/o/2", "https://github.com/o/3"), fetched);
        assertEquals(2, prompts.size());
        String batchPrompt = prompts.get(0);
        for (int i = 1; i <= 3; i++) assertTrue(batchPrompt.contains("제목: repo" + i), batchPrompt);
        assertTrue(batchPrompt.contains("[키워드]\nai agent"));
        // 두 번째 호출: 소스 요약(trend-summary 프롬프트)에 항목 요약들이 줄 단위로 들어간다
        assertTrue(prompts.get(1).contains("[GITHUB] repo1 | https://github.com/o/1 | 2026-06-01 | stars=1 | 항목 요약"));
        assertEquals("소스 요약", result.summary());
        assertFalse(result.degraded());
        assertEquals("항목 요약", result.result().items().get(0).summary());
        assertEquals("소스 요약", source.getSummary());
    }

    @Test
    void 원문은_일괄_모드_상한으로_자른다() {
        service(1, 7).summarize(changed(1));
        // 원문 = "원문 of repo1" + 공백 5 + x 20000자 → 앞 4000자만 (접두 16자 + x 3984자)
        assertTrue(prompts.get(0).contains("x".repeat(3984)));
        assertFalse(prompts.get(0).contains("x".repeat(3985)));
    }

    @Test
    void top_n보다_많은_항목은_읽지_않는다() {
        service(2, 7).summarize(changed(5));
        assertEquals(2, fetched.size());
        verify(gemini, times(2)).generateJson(anyString(), anyMap());
    }

    @Test
    void 원문_읽기에_실패한_항목은_건너뛰고_나머지로_일괄_호출한다() {
        failSecond = true;
        SourceSummary result = service(5, 7).summarize(changed(3));
        assertEquals(List.of("https://github.com/o/1", "https://github.com/o/3"),
                result.result().items().stream().map(SourceItem::url).toList());
        assertFalse(prompts.get(0).contains("repo2"));
        assertEquals(2, prompts.size());
    }

    @Test
    void 일괄_응답이_빠뜨린_항목은_저장하지_않고_원래_짧은_요약을_쓴다() {
        omit = "2";
        SourceSummary result = service(5, 7).summarize(changed(3));
        assertEquals(List.of("항목 요약", "d", "항목 요약"), result.result().items().stream().map(SourceItem::summary).toList());
        var rows = ArgumentCaptor.forClass(Iterable.class);
        verify(itemRepo).saveAll(rows.capture());
        List<String> saved = new ArrayList<>();
        rows.getValue().forEach(r -> saved.add(((SourceItemSummary) r).getUrl()));
        assertEquals(List.of("https://github.com/o/1", "https://github.com/o/3"), saved);
    }

    @Test
    void 일괄_호출이_실패하면_원래_항목으로_대체하고_degraded로_표시하며_저장하지_않는다() {
        doThrow(new CustomException(ErrorCode.EXTERNAL_SERVER_ERROR)).when(gemini).generateJson(anyString(), anyMap());
        SourceCheckResult original = changed(2);
        SourceSummary result = service(5, 7).summarize(original);
        assertSame(original, result.result());
        assertNull(result.summary());
        assertTrue(result.degraded());
        verify(itemRepo, never()).saveAll(any());
        verify(gemini, times(1)).generateJson(anyString(), anyMap()); // 소스 요약까지 가지 않는다
    }

    @Test
    void 읽을_수_있는_항목이_하나도_없으면_호출_없이_원래_항목을_돌려준다() {
        failSecond = false;
        SourceCheckResult original = changed(0);
        SourceSummary result = service(5, 7).summarize(original);
        assertSame(original, result.result());
        assertFalse(result.degraded());
        verifyNoInteractions(gemini);
    }

    @Test
    void 변경되면_기존_항목_요약을_지우고_새로_덮어쓴다() {
        service(5, 7).summarize(changed(2));
        InOrder order = inOrder(itemRepo);
        order.verify(itemRepo).deleteBySourceId(9L);
        var rows = ArgumentCaptor.forClass(Iterable.class);
        order.verify(itemRepo).saveAll(rows.capture());
        List<SourceItemSummary> saved = new ArrayList<>();
        rows.getValue().forEach(r -> saved.add((SourceItemSummary) r));
        assertEquals(2, saved.size());
        assertEquals("항목 요약", saved.get(0).getSummary());
        assertEquals("https://github.com/o/1", saved.get(0).getUrl());
    }

    @Test
    void 최근_요약이_있는_URL은_일괄_호출에서_빼고_재사용하며_전부_재사용이면_소스_요약만_호출한다() {
        SourceItemSummary one = new SourceItemSummary(source, "https://github.com/o/1", "repo1", "2026-06-01", "",
                "저장된 요약", LocalDateTime.now().minusDays(1));
        when(itemRepo.findBySourceId(9L)).thenReturn(List.of(one));

        SourceSummary result = service(5, 7).summarize(changed(2));
        assertEquals(List.of("https://github.com/o/2"), fetched); // 1번은 재사용
        assertEquals("저장된 요약", result.result().items().get(0).summary());
        assertFalse(prompts.get(0).contains("repo1"));
        assertEquals(2, prompts.size());                          // 일괄 1 + 소스 요약 1

        // 전부 재사용: 항목 요약 호출 0회, 소스 요약만
        prompts.clear();
        fetched.clear();
        SourceItemSummary two = new SourceItemSummary(source, "https://github.com/o/2", "repo2", "", "", "저장2", LocalDateTime.now());
        when(itemRepo.findBySourceId(9L)).thenReturn(List.of(one, two));
        service(5, 7).summarize(changed(2));
        assertTrue(fetched.isEmpty());
        assertEquals(1, prompts.size());
        assertFalse(prompts.get(0).contains("[항목 목록]"));

        // 재사용 기간 0이면 항상 다시 요약, 오래된 요약도 다시 읽는다
        fetched.clear();
        service(5, 0).summarize(changed(2));
        assertEquals(2, fetched.size());
        fetched.clear();
        when(itemRepo.findBySourceId(9L)).thenReturn(List.of(new SourceItemSummary(source, "https://github.com/o/1", "repo1",
                "", "", "오래된 요약", LocalDateTime.now().minusDays(30))));
        service(5, 7).summarize(changed(2));
        assertEquals(2, fetched.size());
    }
}
