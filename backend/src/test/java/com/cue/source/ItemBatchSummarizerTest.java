package com.cue.source;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cue.global.ai.GeminiClient;
import com.cue.global.ai.PromptLoader;
import com.cue.global.error.CustomException;
import com.cue.global.error.ErrorCode;
import com.cue.source.config.SourceApiProperties;
import com.cue.source.dto.SourceItem;
import com.cue.source.service.ItemBatchSummarizer;
import com.cue.source.service.ItemBatchSummarizer.Entry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

class ItemBatchSummarizerTest {
    private final GeminiClient gemini = mock(GeminiClient.class);
    private final JsonMapper mapper = JsonMapper.builder().build();

    private ItemBatchSummarizer summarizer(int perItem, int total) {
        return new ItemBatchSummarizer(gemini, new PromptLoader(), new SourceApiProperties(Duration.ofSeconds(3),
                Duration.ofSeconds(5), 5, 30, 8000, 1048576, 3, 7, perItem, total, 2, 30, "ua", "https://wikimedia.org", 800, null, null, null, null, null, null));
    }

    private static Entry entry(int n, String content) {
        return new Entry(new SourceItem("repo" + n, "https://u/" + n, "d", "2026-06-01", "stars=" + n), content);
    }

    private void respond(String json) {
        doReturn(mapper.readTree(json)).when(gemini).generateJson(anyString(), anyMap());
    }

    @Test
    void 한_번의_호출로_항목_전부를_요약하고_URL_기준으로_돌려준다() {
        respond("{\"items\":[{\"url\":\"https://u/1\",\"summary\":\"요약1\"},{\"url\":\"https://u/2\",\"summary\":\"요약2\"}]}");
        Map<String, String> out = summarizer(4000, 16000).summarize("trend-item-summary-batch", Map.of(),
                List.of(entry(1, "원문1"), entry(2, "원문2")));
        assertEquals(Map.of("https://u/1", "요약1", "https://u/2", "요약2"), out);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(gemini, times(1)).generateJson(prompt.capture(), anyMap());
        String p = prompt.getValue();
        assertTrue(p.contains("[1]\n제목: repo1\nURL: https://u/1\n날짜·지표: 2026-06-01 / stars=1\n원문:\n원문1"));
        assertTrue(p.contains("[2]\n제목: repo2"));
        assertFalse(p.contains("{{"));
    }

    @Test
    void 입력에_없던_URL_중복_빈_요약은_버리고_빠진_항목은_결과에_없다() {
        respond("{\"items\":[{\"url\":\"https://u/1\",\"summary\":\"첫 요약\"},{\"url\":\"https://u/1\",\"summary\":\"중복\"},"
                + "{\"url\":\"https://made-up/9\",\"summary\":\"환각\"},{\"url\":\"https://u/2\",\"summary\":\"  \"}]}");
        Map<String, String> out = summarizer(4000, 16000).summarize("trend-item-summary-batch", Map.of(),
                List.of(entry(1, "a"), entry(2, "b"), entry(3, "c")));
        assertEquals(Map.of("https://u/1", "첫 요약"), out);
    }

    @Test
    void 항목_요약은_500자로_자르고_items가_없으면_빈_결과() {
        respond("{\"items\":[{\"url\":\"https://u/1\",\"summary\":\"" + "가".repeat(700) + "\"}]}");
        assertEquals(500, summarizer(4000, 16000).summarize("trend-item-summary-batch", Map.of(), List.of(entry(1, "a")))
                .get("https://u/1").length());
        respond("{}");
        assertTrue(summarizer(4000, 16000).summarize("trend-item-summary-batch", Map.of(), List.of(entry(1, "a"))).isEmpty());
    }

    @Test
    void 항목당_원문과_전체_원문_상한을_적용한다() {
        respond("{\"items\":[]}");
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        summarizer(300, 16000).summarize("trend-item-summary-batch", Map.of(), List.of(entry(1, "§".repeat(1000))));
        verify(gemini).generateJson(prompt.capture(), anyMap());
        assertEquals(300, prompt.getValue().chars().filter(c -> c == '§').count());

        // 전체 상한 1000자, 항목 4개 → 항목당 250자로 균등 축소
        summarizer(4000, 1000).summarize("trend-item-summary-batch", Map.of(),
                List.of(entry(1, "§".repeat(900)), entry(2, "§".repeat(900)), entry(3, "§".repeat(900)), entry(4, "§".repeat(900))));
        verify(gemini, times(2)).generateJson(prompt.capture(), anyMap());
        assertEquals(1000, prompt.getValue().chars().filter(c -> c == '§').count());
    }

    @Test
    void 항목이_없으면_호출하지_않고_Gemini_실패는_예외로_던진다() {
        assertTrue(summarizer(4000, 16000).summarize("trend-item-summary-batch", Map.of(), List.of()).isEmpty());
        verifyNoInteractions(gemini);
        doThrow(new CustomException(ErrorCode.EXTERNAL_SERVER_ERROR)).when(gemini).generateJson(anyString(), anyMap());
        assertThrows(CustomException.class, () -> summarizer(4000, 16000)
                .summarize("trend-item-summary-batch", Map.of(), List.of(entry(1, "a"))));
    }
}
