package com.cue.global.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class GeminiRateLimiterTest {
    /** 수동으로 시간을 옮기는 시계. 대기(sleep)는 시계를 그만큼 앞으로 보낸다. */
    private static class FakeClock extends Clock {
        long now = 1_000_000;
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now); }
        @Override public long millis() { return now; }
    }

    @Test
    void 간격이_0이면_제한이_없다() {
        FakeClock clock = new FakeClock();
        GeminiRateLimiter limiter = new GeminiRateLimiter(0, clock, ms -> fail());
        assertTrue(limiter.acquire(0));
        assertTrue(limiter.acquire(0));
    }

    @Test
    void 첫_호출은_바로_통과하고_다음은_간격만큼_기다린다() {
        FakeClock clock = new FakeClock();
        List<Long> sleeps = new ArrayList<>();
        GeminiRateLimiter limiter = new GeminiRateLimiter(2000, clock, ms -> { sleeps.add(ms); clock.now += ms; });
        assertTrue(limiter.acquire(10_000));
        assertTrue(sleeps.isEmpty());
        assertTrue(limiter.acquire(10_000));
        assertEquals(List.of(2000L), sleeps);
    }

    @Test
    void 대기가_한도를_넘으면_슬롯을_잡지_않고_포기한다() {
        FakeClock clock = new FakeClock();
        List<Long> sleeps = new ArrayList<>();
        GeminiRateLimiter limiter = new GeminiRateLimiter(2000, clock, ms -> { sleeps.add(ms); clock.now += ms; });
        assertTrue(limiter.acquire(1000));
        assertFalse(limiter.acquire(1000)); // 2000ms 기다려야 하는데 한도는 1000ms
        assertTrue(sleeps.isEmpty());
        clock.now += 2000; // 포기한 호출이 슬롯을 잡지 않았으니 시간이 지나면 바로 통과한다
        assertTrue(limiter.acquire(0));
        assertTrue(sleeps.isEmpty());
    }

    @Test
    void 대기_중_인터럽트되면_false() {
        FakeClock clock = new FakeClock();
        GeminiRateLimiter limiter = new GeminiRateLimiter(2000, clock, ms -> { throw new InterruptedException(); });
        assertTrue(limiter.acquire(5000));
        assertFalse(limiter.acquire(5000));
        assertTrue(Thread.interrupted()); // 플래그를 복원했는지 확인하고 지운다
    }
}
