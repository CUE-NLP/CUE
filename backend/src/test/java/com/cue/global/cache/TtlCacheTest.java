package com.cue.global.cache;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class TtlCacheTest {
    private static class FakeClock extends Clock {
        long now = 0;
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now); }
        @Override public long millis() { return now; }
    }

    @Test
    void 값을_넣으면_TTL_안에서_돌려주고_지나면_사라진다() {
        FakeClock clock = new FakeClock();
        TtlCache<String, String> cache = new TtlCache<>(Duration.ofMinutes(10), 10, clock);
        cache.put("k", "v");
        assertEquals("v", cache.get("k"));
        clock.now += Duration.ofMinutes(10).toMillis() - 1;
        assertEquals("v", cache.get("k"));
        clock.now += 1;
        assertNull(cache.get("k"));
        assertEquals(0, cache.size());
    }

    @Test
    void 최대_개수를_넘으면_오래_안_쓴_것부터_버린다() {
        TtlCache<String, Integer> cache = new TtlCache<>(Duration.ofMinutes(1), 2, new FakeClock());
        cache.put("a", 1);
        cache.put("b", 2);
        cache.get("a"); // a를 최근에 씀
        cache.put("c", 3);
        assertEquals(1, cache.get("a"));
        assertNull(cache.get("b"));
        assertEquals(3, cache.get("c"));
    }

    @Test
    void 없는_키는_null() {
        assertNull(new TtlCache<String, String>(Duration.ofMinutes(1), 2, new FakeClock()).get("x"));
    }
}
