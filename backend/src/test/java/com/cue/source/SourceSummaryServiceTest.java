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
        return new SourceSummaryService(List.of(client), gemini, new PromptLoader(), sources, itemRepo,
                new TransactionTemplate(mock(PlatformTransactionManager.class)), props);
    }

    private SourceCheckResult changed(int n) {
        List<SourceItem> items = new ArrayList<>();
        for (int i = 1; i <= n; i++) items.add(new SourceItem("repo" + i, "https://github.com/o/" + i, "d", "2026-06-01", "stars=" + i));
        return SourceCheckResult.changed(9L, ApiProvider.GITHUB, "ai agent", items, LocalDateTime.now());
    }

    @BeforeEach
    void setUp() {
        when(sources.findById(9L)).thenReturn(Optional.of(source));
        when(itemRepo.findBySourceId(9L)).thenReturn(List.of());
        when(gemini.generateJson(anyString(), anyMap())).thenAnswer(inv -> {
            String prompt = inv.getArgument(0);
            return mapper.readTree(prompt.contains("[원문]")
                    ? "{\"summary\":\"항목 요약\"}" : "{\"summary\":\"소스 요약\"}");
        });
    }

    @Test
    void 항목별_요약_후_소스_요약_순서이고_호출_횟수는_항목수_더하기_1이다() {
        SourceSummary result = service(5, 7).summarize(changed(3));

        assertEquals(List.of("https://github.com/o/1", "https://github.com/o/2", "https://github.com/o/3"), fetched);
        var prompts = ArgumentCaptor.forClass(String.class);
        verify(gemini, times(4)).generateJson(prompts.capture(), anyMap());
        List<String> sent = prompts.getAllValues();
        for (int i = 0; i < 3; i++) {
            assertTrue(sent.get(i).contains("[원문]") && sent.get(i).contains("repo" + (i + 1)));
            assertTrue(sent.get(i).contains("[키워드]\nai agent"));
        }
        // 마지막 호출: 소스 요약(trend-summary 프롬프트)에는 항목 요약들이 줄 단위로 들어간다
        assertTrue(sent.get(3).contains("[GITHUB] repo1 | https://github.com/o/1 | 2026-06-01 | stars=1 | 항목 요약"));
        assertEquals("소스 요약", result.summary());
        assertEquals("항목 요약", result.result().items().get(0).summary());
        assertEquals("소스 요약", source.getSummary());
    }

    @Test
    void 원문은_글자_수_상한으로_잘라_보낸다() {
        service(1, 7).summarize(changed(1));
        var prompts = ArgumentCaptor.forClass(String.class);
        verify(gemini, atLeastOnce()).generateJson(prompts.capture(), anyMap());
        String itemPrompt = prompts.getAllValues().get(0);
        String content = itemPrompt.substring(itemPrompt.indexOf("[원문]\n") + 5);
        assertEquals(8000, content.strip().length());
    }

    @Test
    void top_n보다_많은_항목은_읽지_않는다() {
        service(2, 7).summarize(changed(5));
        assertEquals(2, fetched.size());
        verify(gemini, times(3)).generateJson(anyString(), anyMap());
    }

    @Test
    void 원문_읽기나_요약에_실패한_항목은_건너뛰고_나머지로_계속한다() {
        failSecond = true;
        SourceSummary result = service(5, 7).summarize(changed(3));
        assertEquals(2, result.result().items().size());
        assertEquals(List.of("https://github.com/o/1", "https://github.com/o/3"),
                result.result().items().stream().map(SourceItem::url).toList());

        // Gemini가 한 항목에서 실패해도 같은 방식
        fetched.clear();
        failSecond = false;
        var calls = new int[1];
        when(gemini.generateJson(anyString(), anyMap())).thenAnswer(inv -> {
            if (++calls[0] == 1) throw new CustomException(ErrorCode.EXTERNAL_SERVER_ERROR);
            return mapper.readTree("{\"summary\":\"요약\"}");
        });
        assertEquals(2, service(5, 7).summarize(changed(3)).result().items().size());
    }

    @Test
    void 요약된_항목이_하나도_없으면_원래_항목을_그대로_돌려주고_저장하지_않는다() {
        when(gemini.generateJson(anyString(), anyMap())).thenThrow(new CustomException(ErrorCode.EXTERNAL_SERVER_ERROR));
        SourceCheckResult original = changed(2);
        SourceSummary result = service(5, 7).summarize(original);
        assertSame(original, result.result());
        assertNull(result.summary());
        verify(itemRepo, never()).saveAll(any());
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
    void 최근_요약이_있는_URL은_다시_읽지_않고_재사용하며_기간이_0이면_항상_다시_요약한다() {
        SourceItemSummary stored = new SourceItemSummary(source, "https://github.com/o/1", "repo1", "2026-06-01", "",
                "저장된 요약", LocalDateTime.now().minusDays(1));
        when(itemRepo.findBySourceId(9L)).thenReturn(List.of(stored));

        SourceSummary result = service(5, 7).summarize(changed(2));
        assertEquals(List.of("https://github.com/o/2"), fetched); // 1번은 재사용
        assertEquals("저장된 요약", result.result().items().get(0).summary());
        verify(gemini, times(2)).generateJson(anyString(), anyMap()); // 항목 1건 + 소스 요약

        fetched.clear();
        service(5, 0).summarize(changed(2));
        assertEquals(2, fetched.size());

        fetched.clear();
        when(itemRepo.findBySourceId(9L)).thenReturn(List.of(new SourceItemSummary(source, "https://github.com/o/1", "repo1",
                "", "", "오래된 요약", LocalDateTime.now().minusDays(30))));
        service(5, 7).summarize(changed(2));
        assertEquals(2, fetched.size()); // 오래된 요약은 다시 읽는다
    }
}
