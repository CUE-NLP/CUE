package com.cue.trend;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cue.global.ai.GeminiClient;
import com.cue.global.ai.PromptLoader;
import com.cue.global.error.CustomException;
import com.cue.global.error.ErrorCode;
import com.cue.source.client.SearchSettings;
import com.cue.source.client.SourceClient;
import com.cue.source.config.SourceApiProperties;
import com.cue.source.dto.SourceItem;
import com.cue.source.entity.ApiProvider;
import com.cue.source.service.ItemBatchSummarizer;
import com.cue.source.service.SourceHasher;
import com.cue.trend.config.TrendProperties;
import com.cue.trend.dto.TrendSnapshot;
import com.cue.trend.entity.Trend;
import com.cue.trend.entity.TrendItem;
import com.cue.trend.repository.TrendItemRepository;
import com.cue.trend.repository.TrendRepository;
import com.cue.trend.service.GlobalTrendService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

class GlobalTrendServiceTest {
    private final GeminiClient gemini = mock(GeminiClient.class);
    private final TrendRepository trends = mock(TrendRepository.class);
    private final TrendItemRepository itemRepo = mock(TrendItemRepository.class);
    private final JsonMapper mapper = JsonMapper.builder().build();

    private final List<Trend> savedTrends = new ArrayList<>();
    private final List<TrendItem> savedItems = new ArrayList<>();
    private final List<String> prompts = new ArrayList<>();
    private final List<ApiProvider> trendingCalls = new ArrayList<>();
    private final List<String> fetched = new ArrayList<>();
    private final Map<ApiProvider, Boolean> failing = new EnumMap<>(ApiProvider.class);
    private Trend latest;

    private SourceClient client(ApiProvider provider) {
        return new SourceClient() {
            public ApiProvider provider() { return provider; }
            public List<SourceItem> search(String k) { throw new UnsupportedOperationException(); }
            public List<SourceItem> trending(SearchSettings s) {
                trendingCalls.add(provider);
                if (failing.getOrDefault(provider, false)) throw new CustomException(ErrorCode.EXTERNAL_SERVER_ERROR);
                return items(provider);
            }
            public String fetchContent(SourceItem item) {
                fetched.add(item.url());
                return "원문 " + item.title();
            }
        };
    }

    private static List<SourceItem> items(ApiProvider p) {
        return List.of(new SourceItem(p + "-1", "https://" + p + "/1", "d", "2026-06-01", "stars=1"),
                new SourceItem(p + "-2", "https://" + p + "/2", "d", "2026-06-02", "stars=2"));
    }

    private static List<SourceItem> allItems() {
        return ApiProvider.TREND_PROVIDERS.stream().flatMap(p -> items(p).stream()).toList();
    }

    /** 일괄 프롬프트면 입력 URL마다 "항목요약", 아니면 "요약 N"(N = 지금까지의 호출 수). */
    private tools.jackson.databind.JsonNode reply(String prompt) {
        prompts.add(prompt);
        if (!prompt.contains("[항목 목록]")) return mapper.readTree("{\"summary\":\"요약 " + prompts.size() + "\"}");
        StringBuilder items = new StringBuilder();
        var m = java.util.regex.Pattern.compile("URL: (\\S+)").matcher(prompt);
        while (m.find()) {
            if (omitUrl != null && m.group(1).equals(omitUrl)) continue;
            if (items.length() > 0) items.append(",");
            items.append("{\"url\":\"").append(m.group(1)).append("\",\"summary\":\"항목요약\"}");
        }
        return mapper.readTree("{\"items\":[" + items + "]}");
    }

    private String omitUrl = null;

    private GlobalTrendService service(int reuseDays) {
        SourceApiProperties props = new SourceApiProperties(Duration.ofSeconds(3), Duration.ofSeconds(5), 5, 30,
                8000, 1048576, 3, reuseDays, 4000, 16000, 2, 30, "ua", "https://wikimedia.org", 800, null, null, null, null, null, null);
        return new GlobalTrendService(ApiProvider.TREND_PROVIDERS.stream().map(this::client).toList(), gemini,
                new PromptLoader(), new ItemBatchSummarizer(gemini, new PromptLoader(), props), trends, itemRepo, new TransactionTemplate(mock(PlatformTransactionManager.class)),
                props, new TrendProperties(7, 2, 3));
    }

    @BeforeEach
    void setUp() {
        doAnswer(inv -> reply(inv.getArgument(0))).when(gemini).generateJson(anyString(), anyMap());
        when(trends.findFirstByOrderByIdDesc()).thenAnswer(inv -> Optional.ofNullable(latest));
        when(trends.save(any(Trend.class))).thenAnswer(inv -> {
            Trend t = inv.getArgument(0);
            ReflectionTestUtils.setField(t, "id", 100L + savedTrends.size());
            savedTrends.add(t);
            return t;
        });
        when(trends.findById(anyLong())).thenAnswer(inv ->
                Optional.of(savedTrends.stream().filter(t -> t.getId().equals(inv.getArgument(0))).findFirst().orElse(latest)));
        when(itemRepo.save(any(TrendItem.class))).thenAnswer(inv -> { savedItems.add(inv.getArgument(0)); return inv.getArgument(0); });
        when(itemRepo.findByTrendIdOrderById(anyLong())).thenAnswer(inv -> {
            long id = inv.getArgument(0);
            return savedItems.stream().filter(i -> i.getTrend().getId() == id).toList();
        });
    }

    private Trend existing(long id, String hash, LocalDateTime createdAt) {
        Trend t = new Trend("어제 요약", List.of(ApiProvider.GITHUB), hash);
        ReflectionTestUtils.setField(t, "id", id);
        ReflectionTestUtils.setField(t, "createdAt", createdAt);
        return t;
    }

    @Test
    void 키워드_없이_3개_provider를_조회해_항목_일괄_요약_1회와_최종_요약_1회로_저장한다() {
        TrendSnapshot snapshot = service(7).today();

        assertEquals(ApiProvider.TREND_PROVIDERS, trendingCalls);
        assertEquals(6, fetched.size());
        // 모든 provider의 항목을 한 번에 요약(1회) + 항목 요약 줄들로 최종 요약(1회) = 2회
        assertEquals(2, prompts.size());
        String batchPrompt = prompts.get(0);
        assertTrue(batchPrompt.contains("원문 HACKER_NEWS-1") && batchPrompt.contains("원문 GITHUB-2") && batchPrompt.contains("원문 HUGGING_FACE-2")); // 한 호출에 항목 6개
        assertFalse(batchPrompt.contains("{{"));
        String last = prompts.get(1); // 최종 요약 입력: 항목 요약 줄들
        assertTrue(last.contains("[HACKER_NEWS] HACKER_NEWS-1 | https://HACKER_NEWS/1 | 2026-06-01 | stars=1 | 항목요약"));
        assertTrue(last.contains("[GITHUB] GITHUB-2") && last.contains("[HUGGING_FACE] HUGGING_FACE-2"));
        assertEquals("요약 2", snapshot.summary());

        Trend saved = savedTrends.get(0);
        assertEquals("HACKER_NEWS,GITHUB,HUGGING_FACE", saved.getProviders());
        assertEquals(SourceHasher.hash(allItems()), saved.getContentHash());
        assertEquals(6, savedItems.size());
        assertEquals(3, snapshot.sources().size());
        assertEquals("항목요약", snapshot.sources().get(0).items().get(0).summary());
    }

    @Test
    void 같은_날_다시_불러도_외부_호출을_하지_않는다() {
        GlobalTrendService s = service(7);
        s.today();
        int calls = prompts.size();
        trendingCalls.clear();
        assertNotNull(s.today());
        assertEquals(calls, prompts.size());
        assertTrue(trendingCalls.isEmpty());
        assertEquals(1, savedTrends.size());
    }

    @Test
    void 상위_항목_해시가_가장_최근_트렌드와_같으면_재요약하지_않고_기존_트렌드를_쓴다() {
        latest = existing(5L, SourceHasher.hash(allItems()), LocalDateTime.now().minusDays(1));
        TrendSnapshot snapshot = service(7).today();

        assertEquals(5L, snapshot.id());
        assertEquals("어제 요약", snapshot.summary());
        assertTrue(prompts.isEmpty());
        assertTrue(savedTrends.isEmpty());
        assertTrue(fetched.isEmpty());
    }

    @Test
    void 오늘_이미_만든_트렌드가_있으면_조회도_하지_않는다() {
        latest = existing(6L, "x", LocalDateTime.now());
        assertEquals(6L, service(7).today().id());
        assertTrue(trendingCalls.isEmpty());
        assertTrue(prompts.isEmpty());
    }

    @Test
    void 해시가_다르면_새_행을_만들고_같은_URL의_항목_요약은_재사용한다() {
        latest = existing(5L, "old-hash", LocalDateTime.now().minusDays(1));
        savedTrends.add(latest);
        savedItems.add(new TrendItem(latest, ApiProvider.GITHUB, "https://GITHUB/1", "GITHUB-1", "", "", "저장된 항목 요약",
                LocalDateTime.now().minusDays(1)));

        TrendSnapshot snapshot = service(7).today();

        assertFalse(fetched.contains("https://GITHUB/1")); // 재사용: 다시 읽지 않는다
        assertEquals(5, fetched.size());
        assertEquals(2, prompts.size());                    // 일괄 1 + 최종 1
        assertEquals(101L, snapshot.id());
        assertEquals(6, savedItems.stream().filter(i -> i.getTrend().getId() == 101L).count());
        assertTrue(savedItems.stream().anyMatch(i -> "저장된 항목 요약".equals(i.getSummary()) && i.getTrend().getId() == 101L));
    }

    @Test
    void 재사용_기간이_0이면_항상_다시_요약한다() {
        latest = existing(5L, "old-hash", LocalDateTime.now().minusDays(1));
        savedTrends.add(latest);
        savedItems.add(new TrendItem(latest, ApiProvider.GITHUB, "https://GITHUB/1", "GITHUB-1", "", "", "저장된 항목 요약",
                LocalDateTime.now().minusDays(1)));
        service(0).today();
        assertEquals(6, fetched.size());
    }

    @Test
    void 한_provider_조회가_실패해도_나머지로_만들고_전부_실패하면_null이며_재시도_대기한다() {
        failing.put(ApiProvider.GITHUB, true);
        TrendSnapshot snapshot = service(7).today();
        assertEquals("HACKER_NEWS,HUGGING_FACE", savedTrends.get(0).getProviders());
        assertEquals(2, snapshot.sources().size());

        savedTrends.clear();
        savedItems.clear();
        trendingCalls.clear();
        failing.put(ApiProvider.HACKER_NEWS, true);
        failing.put(ApiProvider.HUGGING_FACE, true);
        GlobalTrendService s = service(7);
        assertNull(s.today());
        assertEquals(3, trendingCalls.size());
        assertNull(s.today());                      // 대기 시간 안에는 다시 시도하지 않는다
        assertEquals(3, trendingCalls.size());
        assertTrue(savedTrends.isEmpty());
    }

    @Test
    void 최종_요약이_실패하면_아무것도_저장하지_않고_null() {
        doAnswer(inv -> {
            String prompt = inv.getArgument(0);
            if (!prompt.contains("[항목 목록]")) throw new CustomException(ErrorCode.EXTERNAL_SERVER_ERROR); // 최종 요약 호출
            return reply(prompt);
        }).when(gemini).generateJson(anyString(), anyMap());
        assertNull(service(7).today());
        assertTrue(savedTrends.isEmpty());
        assertTrue(savedItems.isEmpty());
    }

    @Test
    void 이전_항목_요약이_전부_재사용되면_일괄_호출_없이_최종_요약만_한다() {
        latest = existing(5L, "old-hash", LocalDateTime.now().minusDays(1));
        savedTrends.add(latest);
        for (ApiProvider p : ApiProvider.TREND_PROVIDERS)
            for (SourceItem it : items(p))
                savedItems.add(new TrendItem(latest, p, it.url(), it.title(), "", "", "저장 " + it.title(), LocalDateTime.now().minusDays(1)));

        service(7).today();

        assertTrue(fetched.isEmpty());
        assertEquals(1, prompts.size()); // 최종 1
        assertTrue(prompts.stream().noneMatch(p -> p.contains("[항목 목록]")));
    }

    @Test
    void 일괄_호출이_실패하면_재사용_항목만으로_만들고_재사용할_게_없으면_만들지_못한다() {
        doAnswer(inv -> {
            String prompt = inv.getArgument(0);
            if (prompt.contains("[항목 목록]")) throw new CustomException(ErrorCode.EXTERNAL_SERVER_ERROR); // 일괄 실패
            return reply(prompt);
        }).when(gemini).generateJson(anyString(), anyMap());

        assertNull(service(7).today()); // 재사용할 이전 요약이 없다
        assertTrue(savedTrends.isEmpty());

        latest = existing(5L, "old-hash", LocalDateTime.now().minusDays(1));
        savedTrends.add(latest);
        savedItems.add(new TrendItem(latest, ApiProvider.GITHUB, "https://GITHUB/1", "GITHUB-1", "", "", "저장된 항목 요약", LocalDateTime.now().minusDays(1)));
        TrendSnapshot snapshot = service(7).today(); // GITHUB-1만 재사용 가능
        assertEquals("GITHUB", savedTrends.get(savedTrends.size() - 1).getProviders());
        assertEquals(1, snapshot.sources().size());
    }

    @Test
    void 일괄_응답이_빠뜨린_항목과_원문을_못_읽은_항목은_저장하지_않는다() {
        omitUrl = "https://GITHUB/2";
        TrendSnapshot snapshot = service(7).today();
        assertEquals(5, savedItems.size());
        assertTrue(savedItems.stream().noneMatch(i -> i.getUrl().equals("https://GITHUB/2")));
        assertNotNull(snapshot);
    }
}
