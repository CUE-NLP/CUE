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
    void 같은_내용은_같은_해시_다른_내용은_다른_해시() {
        assertEquals(SourceHasher.hash(githubItems), SourceHasher.hash(new ArrayList<>(githubItems)));
        assertNotEquals(SourceHasher.hash(githubItems), SourceHasher.hash(List.of(new SourceItem("t", "u", "s2"))));
        assertEquals(64, SourceHasher.hash(List.of()).length());
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

        githubItems = List.of(new SourceItem("new", "https://x", "sum"));
        SourceCheckResult r = service.check(1L);
        assertTrue(r.changed());
        assertEquals(githubItems, r.items());
        assertEquals(SourceHasher.hash(githubItems), s.getContentHash());
    }

    @Test
    void 변경이면_상위_목록_전체를_돌려주고_순서만_같고_내용이_같으면_변경_없음이며_복구하면_다시_감지한다() {
        SourceItem a = new SourceItem("a", "https://a", "s"), b = new SourceItem("b", "https://b", "s");
        githubItems = List.of(a, b);
        Source s = new Source(null, ApiProvider.GITHUB, "kw");
        when(repo.findById(1L)).thenReturn(Optional.of(s));
        service.check(1L);
        String before = s.getContentHash();
        java.time.LocalDateTime beforeAt = s.getCheckedAt();

        assertFalse(service.check(1L).changed());
        githubItems = List.of(a, new SourceItem("c", "https://c", "s"));
        SourceCheckResult r = service.check(1L);
        assertTrue(r.changed());
        assertEquals(githubItems, r.items()); // 새 항목만이 아니라 상위 목록 전체

        // 평가에 실패하면 이전 해시로 되돌려 다음 조회에서 같은 변화를 다시 감지한다
        service.restoreHash(1L, before, beforeAt);
        assertEquals(before, s.getContentHash());
        assertTrue(service.check(1L).changed());
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

    @Test
    void 시연용_강제_해시가_들어간_source는_다음_조회에서_변경으로_감지된다() {
        Source s = new Source(null, ApiProvider.GITHUB, "kw");
        when(repo.findById(1L)).thenReturn(Optional.of(s));
        service.check(1L); // 실제 해시 기록
        assertFalse(service.check(1L).changed());

        s.restore(com.cue.demo.DemoEvaluationService.FORCED_HASH, s.getCheckedAt()); // 시연 API가 해시를 바꾼 상태
        SourceCheckResult r = service.check(1L);
        assertTrue(r.changed());
        assertEquals(githubItems, r.items());
        assertEquals(SourceHasher.hash(githubItems), s.getContentHash());
    }
}
