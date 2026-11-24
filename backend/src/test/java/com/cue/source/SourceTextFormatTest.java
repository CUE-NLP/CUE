package com.cue.source;

import static org.junit.jupiter.api.Assertions.*;

import com.cue.source.dto.SourceCheckResult;
import com.cue.source.dto.SourceItem;
import com.cue.source.entity.ApiProvider;
import com.cue.source.service.SourceTextFormat;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class SourceTextFormatTest {
    @Test
    void 자료_목록은_한_줄에_한_건_형식이고_항목당_길이와_전체_개수에_상한이_있다() {
        var item = new SourceItem("제목|파이프", "https://u/1", "요".repeat(400), "2026-01-02", "stars=5");
        String text = SourceTextFormat.formatSources(List.of(SourceCheckResult.changed(1L, ApiProvider.GITHUB, "k",
                List.of(item, new SourceItem("x", "https://u/2", "")), LocalDateTime.now())));
        String[] lines = text.split("\n");
        assertEquals("[GITHUB] 제목/파이프 | https://u/1 | 2026-01-02 | stars=5 | " + "요".repeat(300) + "…", lines[0]);
        assertEquals("[GITHUB] x | https://u/2 | - | - | -", lines[1]);

        List<SourceItem> many = java.util.stream.IntStream.range(0, 40)
                .mapToObj(i -> new SourceItem("t" + i, "https://u/" + i, "s")).toList();
        String capped = SourceTextFormat.formatSources(
                List.of(SourceCheckResult.changed(1L, ApiProvider.GITHUB, "k", many, LocalDateTime.now())));
        assertEquals(SourceTextFormat.MAX_ITEMS, capped.split("\n").length);
    }

    @Test
    void 상한을_넓히면_근거_평가용으로_더_많은_항목과_긴_발췌를_담는다() {
        List<SourceItem> many = java.util.stream.IntStream.range(0, 40)
                .mapToObj(i -> new SourceItem("t" + i, "https://u/" + i, "발".repeat(900))).toList();
        String text = SourceTextFormat.formatSources(
                List.of(SourceCheckResult.changed(1L, ApiProvider.GITHUB, "k", many, LocalDateTime.now())),
                SourceTextFormat.EVIDENCE_MAX_ITEMS, SourceTextFormat.EVIDENCE_MAX_SUMMARY_CHARS);
        String[] lines = text.split("\n");
        assertEquals(SourceTextFormat.EVIDENCE_MAX_ITEMS, lines.length);
        assertTrue(lines[0].endsWith("| " + "발".repeat(900)));
    }
}
