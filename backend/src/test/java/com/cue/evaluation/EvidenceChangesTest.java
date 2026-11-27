package com.cue.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.cue.evaluation.dto.EvidenceChanges;
import com.cue.source.dto.EvaluatedItem;
import com.cue.source.dto.SourceCheckResult;
import com.cue.source.dto.SourceItem;
import com.cue.source.entity.ApiProvider;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EvidenceChangesTest {
    private static List<EvaluatedItem> recorded(String... names) {
        return Arrays.stream(names).map(n -> new EvaluatedItem("https://x.test/" + n, n)).toList();
    }

    private static List<SourceItem> items(String... names) {
        return Arrays.stream(names).map(n -> new SourceItem(n, "https://x.test/" + n, "설명")).toList();
    }

    private static SourceCheckResult changed(long id, String... names) {
        return SourceCheckResult.changed(id, ApiProvider.GITHUB, "k", items(names), LocalDateTime.now());
    }

    private static SourceCheckResult unchanged(long id, String... names) {
        return SourceCheckResult.unchanged(id, ApiProvider.GITHUB, "k", items(names), LocalDateTime.now());
    }

    private static List<String> added(EvidenceChanges c) {
        return c.added().stream().flatMap(r -> r.items().stream()).map(SourceItem::title).toList();
    }

    private static List<String> removed(EvidenceChanges c) {
        return c.removed().stream().map(EvaluatedItem::title).toList();
    }

    @Test
    void 상위_5개_중_2개가_바뀌면_새_2개만_추가이고_빠진_2개는_삭제다() {
        var c = EvidenceChanges.between(Map.of(1L, recorded("a", "b", "c", "d", "e")),
                List.of(changed(1, "a", "b", "c", "f", "g")));

        assertEquals(List.of("f", "g"), added(c));
        assertEquals(List.of("d", "e"), removed(c));
        assertEquals(recorded("a", "b", "c", "f", "g"), c.current().get(1L));
    }

    @Test
    void 순위와_제목만_달라지면_추가도_삭제도_아니다() {
        var retitled = SourceCheckResult.changed(1L, ApiProvider.GITHUB, "k",
                List.of(new SourceItem("새 제목", "https://x.test/b", ""), new SourceItem("a", "https://x.test/a", "")),
                LocalDateTime.now());

        assertFalse(EvidenceChanges.between(Map.of(1L, recorded("a", "b")), List.of(retitled)).any());
    }

    @Test
    void 같은_URL이_다른_source에_남아_있으면_삭제가_아니고_이미_반영한_URL은_추가가_아니다() {
        var c = EvidenceChanges.between(Map.of(1L, recorded("a", "b"), 2L, recorded("c")),
                List.of(changed(1, "a", "c", "n"), changed(2, "b", "n")));

        assertEquals(List.of("n"), added(c)); // 두 source에 함께 나타난 새 항목은 한 번만
        assertEquals(List.of(), removed(c));
    }

    @Test
    void 조회에_실패한_source의_항목은_삭제로_보지_않고_기록도_덮어쓰지_않는다() {
        var c = EvidenceChanges.between(Map.of(1L, recorded("a"), 2L, recorded("b")),
                List.of(unchanged(1, "a"), SourceCheckResult.failed(2L, ApiProvider.HACKER_NEWS, "k", "실패")));

        assertFalse(c.any());
        assertFalse(c.current().containsKey(2L));
    }

    @Test
    void 기록이_없는_source는_해시가_같으면_반영한_것으로_보고_달라졌으면_전부_추가로_본다() {
        assertFalse(EvidenceChanges.between(Map.of(), List.of(unchanged(1, "a", "b"))).any());

        var c = EvidenceChanges.between(Map.of(), List.of(changed(1, "a", "b")));
        assertEquals(List.of("a", "b"), added(c));
        assertEquals(List.of(), removed(c));
    }
}
