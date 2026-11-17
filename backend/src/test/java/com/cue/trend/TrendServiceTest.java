package com.cue.trend;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.cue.global.ai.GeminiClient;
import com.cue.global.ai.PromptLoader;
import com.cue.global.error.CustomException;
import com.cue.global.error.ErrorCode;
import com.cue.source.dto.SourceCheckResult;
import com.cue.source.dto.SourceItem;
import com.cue.source.dto.SourceSummary;
import com.cue.source.entity.ApiProvider;
import com.cue.trend.entity.Trend;
import com.cue.trend.repository.TrendRepository;
import com.cue.trend.service.TrendService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class TrendServiceTest {
    private final GeminiClient gemini = mock(GeminiClient.class);
    private final TrendRepository repo = mock(TrendRepository.class);
    private final TrendService service = new TrendService(gemini, new PromptLoader(), repo);
    private final JsonMapper mapper = JsonMapper.builder().build();

    private static SourceCheckResult changed(ApiProvider p, String kw) {
        return SourceCheckResult.changed(1L, p, kw, List.of(new SourceItem("t", "https://u/" + p, "s")), LocalDateTime.now());
    }

    private static SourceSummary summary(ApiProvider p, String kw, String text) {
        return new SourceSummary(changed(p, kw), text);
    }

    @Test
    void 소스_요약들을_최종_트렌드_요약으로_저장하고_소스_요약이_프롬프트에_들어간다() {
        when(gemini.generateJson(anyString(), anyMap())).thenReturn(mapper.readTree("{\"summary\":\"요즘 이런 흐름\"}"));
        when(repo.save(any(Trend.class))).thenAnswer(i -> i.getArgument(0));

        Trend trend = service.summarize(List.of(summary(ApiProvider.GITHUB, "a", "깃허브 요약문"),
                summary(ApiProvider.HACKER_NEWS, "a", null)));

        assertEquals("요즘 이런 흐름", trend.getSummary());
        assertEquals("GITHUB,HACKER_NEWS", trend.getProviders());
        var prompt = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(gemini).generateJson(prompt.capture(), anyMap());
        assertTrue(prompt.getValue().contains("깃허브 요약문"));
        assertTrue(prompt.getValue().contains("[HACKER_NEWS] t | https://u/HACKER_NEWS")); // 소스 요약이 없으면 항목 목록으로 대체
    }

    @Test
    void 자료_목록은_한_줄에_한_건_형식이고_항목당_길이와_전체_개수에_상한이_있다() {
        var item = new SourceItem("제목|파이프", "https://u/1", "요".repeat(400), "2026-01-02", "stars=5");
        String text = TrendService.formatSources(List.of(SourceCheckResult.changed(1L, ApiProvider.GITHUB, "k",
                List.of(item, new SourceItem("x", "https://u/2", "")), LocalDateTime.now())));
        String[] lines = text.split("\n");
        assertEquals("[GITHUB] 제목/파이프 | https://u/1 | 2026-01-02 | stars=5 | " + "요".repeat(300) + "…", lines[0]);
        assertEquals("[GITHUB] x | https://u/2 | - | - | -", lines[1]);

        List<SourceItem> many = java.util.stream.IntStream.range(0, 40)
                .mapToObj(i -> new SourceItem("t" + i, "https://u/" + i, "s")).toList();
        String capped = TrendService.formatSources(List.of(SourceCheckResult.changed(1L, ApiProvider.GITHUB, "k", many, LocalDateTime.now())));
        assertEquals(TrendService.MAX_ITEMS, capped.split("\n").length);
    }

    @Test
    void 변경이_없으면_호출하지_않고_실패하거나_빈_요약이면_null() {
        assertNull(service.summarize(List.of()));
        verifyNoInteractions(gemini);

        doThrow(new CustomException(ErrorCode.EXTERNAL_SERVER_ERROR)).when(gemini).generateJson(anyString(), anyMap());
        assertNull(service.summarize(List.of(summary(ApiProvider.GITHUB, "a", "s"))));

        doReturn(mapper.readTree("{\"summary\":\" \"}")).when(gemini).generateJson(anyString(), anyMap());
        assertNull(service.summarize(List.of(summary(ApiProvider.GITHUB, "a", "s"))));
        verify(repo, never()).save(any());
    }
}
