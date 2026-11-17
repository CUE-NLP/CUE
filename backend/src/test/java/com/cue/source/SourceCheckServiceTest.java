package com.cue.source;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.cue.global.error.CustomException;
import com.cue.global.error.ErrorCode;
import com.cue.source.client.SourceClient;
import com.cue.source.dto.SourceCheckResult;
import com.cue.source.dto.SourceItem;
import com.cue.source.entity.ApiProvider;
import com.cue.source.entity.Source;
import com.cue.source.repository.SourceRepository;
import com.cue.source.service.SourceCheckService;
import com.cue.source.service.SourceHasher;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SourceCheckServiceTest {
    private final SourceRepository repo = mock(SourceRepository.class);
    private List<SourceItem> githubItems = List.of(new SourceItem("t", "u", "s"));
    private boolean stackFails = false;

    private final SourceClient github = new SourceClient() {
        public ApiProvider provider() { return ApiProvider.GITHUB; }
        public List<SourceItem> search(String k) { return githubItems; }
    };
    private final SourceClient stack = new SourceClient() {
        public ApiProvider provider() { return ApiProvider.STACK_EXCHANGE; }
        public List<SourceItem> search(String k) {
            if (stackFails) throw new CustomException(ErrorCode.EXTERNAL_SERVER_ERROR);
            return List.of();
        }
    };
    private final SourceCheckService service = new SourceCheckService(repo, List.of(github, stack));

    @Test
    void 요약만_달라지면_같은_해시() {
        assertEquals(SourceHasher.hash(githubItems), SourceHasher.hash(List.of(new SourceItem("t", "u", "다른 요약"))));
        assertEquals(64, SourceHasher.hash(List.of()).length());
    }

    @Test
    void 순서만_다르면_같은_해시() {
        SourceItem a = new SourceItem("A", "https://a", "");
        SourceItem b = new SourceItem("B", "https://b", "");
        assertEquals(SourceHasher.hash(List.of(a, b)), SourceHasher.hash(List.of(b, a)));
    }

    @Test
    void 항목이_추가_교체되거나_제목_URL이_바뀌면_다른_해시() {
        SourceItem a = new SourceItem("A", "https://a", "");
        SourceItem b = new SourceItem("B", "https://b", "");
        String base = SourceHasher.hash(List.of(a, b));
        assertNotEquals(base, SourceHasher.hash(List.of(a)));
        assertNotEquals(base, SourceHasher.hash(List.of(a, b, new SourceItem("C", "https://c", ""))));
        assertNotEquals(base, SourceHasher.hash(List.of(a, new SourceItem("C", "https://c", ""))));
        assertNotEquals(base, SourceHasher.hash(List.of(a, new SourceItem("B2", "https://b", ""))));
        assertNotEquals(base, SourceHasher.hash(List.of(a, new SourceItem("B", "https://b2", ""))));
    }

    @Test
    void 첫_조회는_변경_없음으로_해시와_시각만_기록한다() {
        Source s = new Source(null, ApiProvider.GITHUB, "kw");
        when(repo.findById(1L)).thenReturn(Optional.of(s));
        SourceCheckResult r = service.check(1L);
        assertEquals(SourceCheckResult.Status.UNCHANGED, r.status());
        assertNotNull(s.getContentHash());
        assertNotNull(s.getCheckedAt());
        verify(repo).save(s);
    }

    @Test
    void 내용이_바뀌면_변경으로_제목_URL_요약을_반환하고_같으면_변경_없음() {
        Source s = new Source(null, ApiProvider.GITHUB, "kw");
        when(repo.findById(1L)).thenReturn(Optional.of(s));
        service.check(1L);
        assertFalse(service.check(1L).changed()); // 같은 내용

        githubItems = List.of(new SourceItem("t", "u", "요약만 변경"));
        assertFalse(service.check(1L).changed()); // 요약 변경은 변경이 아님

        githubItems = List.of(new SourceItem("new", "https://x", "sum"));
        SourceCheckResult r = service.check(1L);
        assertTrue(r.changed());
        assertEquals(githubItems, r.items());
        assertEquals(SourceHasher.hash(githubItems), s.getContentHash());
    }

    @Test
    void 한_source가_실패해도_나머지는_계속_조회하고_실패한_해시는_유지한다() {
        Source ok = new Source(null, ApiProvider.GITHUB, "a");
        Source bad = new Source(null, ApiProvider.STACK_EXCHANGE, "b");
        Source unsupported = new Source(null, ApiProvider.HACKER_NEWS, "c");
        when(repo.findByIdeaId(7L)).thenReturn(List.of(bad, unsupported, ok));
        stackFails = true;

        List<SourceCheckResult> results = service.checkAll(7L);
        assertEquals(List.of(SourceCheckResult.Status.FAILED, SourceCheckResult.Status.FAILED, SourceCheckResult.Status.UNCHANGED),
                results.stream().map(SourceCheckResult::status).toList());
        assertNull(bad.getContentHash());
        assertNull(bad.getCheckedAt());
        assertNotNull(ok.getContentHash());
        verify(repo, times(1)).save(any());
    }

    @Test
    void 없는_source는_S001_미지원_provider는_S002() {
        when(repo.findById(9L)).thenReturn(Optional.empty());
        assertEquals(ErrorCode.SOURCE_NOT_FOUND, assertThrows(CustomException.class, () -> service.check(9L)).getErrorCode());
        when(repo.findById(2L)).thenReturn(Optional.of(new Source(null, ApiProvider.HACKER_NEWS, "k")));
        assertEquals(ErrorCode.SOURCE_PROVIDER_NOT_SUPPORTED,
                assertThrows(CustomException.class, () -> service.check(2L)).getErrorCode());
    }
}
