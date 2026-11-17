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

    @Test
    void 변경된_자료를_요약해_provider_목록과_함께_저장한다() {
        when(gemini.generateJson(anyString(), anyMap())).thenReturn(mapper.readTree("{\"summary\":\"요즘 이런 흐름\"}"));
        when(repo.save(any(Trend.class))).thenAnswer(i -> i.getArgument(0));

        Trend trend = service.summarize(List.of(changed(ApiProvider.GITHUB, "a"), changed(ApiProvider.HACKER_NEWS, "a")));

        assertEquals("요즘 이런 흐름", trend.getSummary());
        assertEquals("GITHUB,HACKER_NEWS", trend.getProviders());
    }

    @Test
    void 변경이_없으면_호출하지_않고_실패하거나_빈_요약이면_null() {
        assertNull(service.summarize(List.of()));
        verifyNoInteractions(gemini);

        doThrow(new CustomException(ErrorCode.EXTERNAL_SERVER_ERROR)).when(gemini).generateJson(anyString(), anyMap());
        assertNull(service.summarize(List.of(changed(ApiProvider.GITHUB, "a"))));

        doReturn(mapper.readTree("{\"summary\":\" \"}")).when(gemini).generateJson(anyString(), anyMap());
        assertNull(service.summarize(List.of(changed(ApiProvider.GITHUB, "a"))));
        verify(repo, never()).save(any());
    }
}
