package com.cue.source;

import static org.mockito.Mockito.times;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cue.global.error.CustomException;
import com.cue.global.error.ErrorCode;
import com.cue.source.client.SourceClient;
import com.cue.source.config.SourceApiProperties;
import com.cue.source.dto.SourceCheckResult;
import com.cue.source.dto.SourceItem;
import com.cue.source.entity.ApiProvider;
import com.cue.source.service.SourceExcerptService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 근거 자료 발췌는 AI를 부르지 않는다: 이 서비스는 Gemini 의존성이 아예 없다. */
class SourceExcerptServiceTest {
    private final SourceClient client = mock(SourceClient.class);

    private SourceExcerptService service(int topN, int excerptChars) {
        when(client.provider()).thenReturn(ApiProvider.GITHUB);
        SourceApiProperties props = new SourceApiProperties(Duration.ofSeconds(3), Duration.ofSeconds(5), topN,
                8000, 1048576, 3, 7, 4000, 16000, 30, "ua", "https://wikimedia.org", excerptChars, null, null, null, null, null, null);
        return new SourceExcerptService(List.of(client), props);
    }

    private SourceCheckResult changed(SourceItem... items) {
        return SourceCheckResult.changed(9L, ApiProvider.GITHUB, "agent", List.of(items), LocalDateTime.now());
    }

    @Test
    void 상위_N개만_남기고_API_설명과_원문_발췌를_합쳐_summary_자리에_넣는다() {
        SourceItem one = new SourceItem("repo1", "https://github.com/o/1", "에이전트 도구", "2026-06-01", "stars=10");
        SourceItem two = new SourceItem("repo2", "https://github.com/o/2", "두번째", "", "");
        SourceItem three = new SourceItem("repo3", "https://github.com/o/3", "세번째", "", "");
        SourceExcerptService service = service(2, 800);
        when(client.fetchContent(one)).thenReturn("# repo1\n\n에이전트를 만드는 **라이브러리**입니다.");
        when(client.fetchContent(two)).thenReturn("");

        SourceCheckResult result = service.excerpt(changed(one, two, three));

        assertEquals(2, result.items().size());
        assertEquals(new SourceItem("repo1", "https://github.com/o/1", "에이전트 도구 — repo1 에이전트를 만드는 라이브러리입니다.", "2026-06-01", "stars=10"),
                result.items().get(0));
        assertEquals("두번째", result.items().get(1).summary()); // 원문이 비면 API 설명만
        assertEquals(9L, result.sourceId());
        assertTrue(result.changed());
        verify(client, never()).fetchContent(three);
    }

    @Test
    void 원문_읽기가_실패해도_항목은_버리지_않고_API_설명으로_남긴다() {
        SourceItem item = new SourceItem("repo", "https://github.com/o/1", "설명", "", "");
        SourceExcerptService service = service(5, 800);
        when(client.fetchContent(item)).thenThrow(new CustomException(ErrorCode.EXTERNAL_SERVER_ERROR));

        SourceCheckResult result = service.excerpt(changed(item));

        assertEquals(1, result.items().size());
        assertEquals("설명", result.items().get(0).summary());
    }

    @Test
    void API_설명이_이미_발췌_길이만큼_충분하면_원문을_다시_읽지_않고_길이에_맞춘다() {
        SourceItem item = new SourceItem("paper", "https://s2/1", "가".repeat(500), "", "");
        SourceExcerptService service = service(5, 400);

        SourceCheckResult result = service.excerpt(changed(item));

        verify(client, never()).fetchContent(any());
        assertEquals("가".repeat(400) + "…", result.items().get(0).summary());
    }

    @Test
    void 읽은_원문은_재사용하고_실패한_것은_다시_읽는다() {
        SourceItem ok = new SourceItem("repo", "https://github.com/o/1", "설명", "", "");
        SourceItem flaky = new SourceItem("flaky", "https://github.com/o/2", "설명2", "", "");
        SourceExcerptService service = service(5, 800);
        when(client.fetchContent(ok)).thenReturn("본문");
        when(client.fetchContent(flaky)).thenThrow(new CustomException(ErrorCode.EXTERNAL_SERVER_ERROR)).thenReturn("본문2");

        service.excerpt(changed(ok, flaky));
        SourceCheckResult second = service.excerpt(changed(ok, flaky));

        verify(client, times(1)).fetchContent(ok);
        verify(client, times(2)).fetchContent(flaky);
        assertEquals("설명 — 본문", second.items().get(0).summary());
        assertEquals("설명2 — 본문2", second.items().get(1).summary());
    }

    @Test
    void 발췌_길이_예산을_지킨다() {
        SourceItem item = new SourceItem("repo", "https://github.com/o/1", "설명", "", "");
        SourceExcerptService service = service(5, 200);
        when(client.fetchContent(item)).thenReturn("나".repeat(1000));

        String summary = service.excerpt(changed(item)).items().get(0).summary();

        assertTrue(summary.startsWith("설명 — "));
        assertTrue(summary.length() <= 200 + 1, "길이 " + summary.length()); // 예산 + 말줄임표
    }
}
