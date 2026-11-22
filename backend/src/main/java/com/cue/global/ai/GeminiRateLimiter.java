package com.cue.global.ai;

import java.time.Clock;

/**
 * 호출 사이 최소 간격을 지키는 순번 예약식 속도 제한기 (Gemini 분당 한도 대응). 스레드가 여러 개여도 예약한 순서대로 슬롯을 받는다.
 * {@code maxWaitMs}보다 오래 기다려야 하면 슬롯을 잡지 않고 false를 돌려줘, 사용자가 기다리는 호출이 배치처럼 오래 막히지 않게 한다.
 */
public class GeminiRateLimiter {
    private final long minIntervalMs;
    private final Clock clock;
    private final Sleeper sleeper;
    private long nextFreeAt;

    public GeminiRateLimiter(long minIntervalMs, Clock clock, Sleeper sleeper) {
        this.minIntervalMs = minIntervalMs;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    /** 대기 구현 (테스트에서 실제로 자지 않게 바꾼다). */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /**
     * 슬롯을 예약하고 차례가 될 때까지 기다린다.
     *
     * @param maxWaitMs 허용하는 최대 대기(ms). 넘으면 예약하지 않고 false
     * @return 슬롯을 받았으면 true, 대기 한도를 넘어 포기했으면 false (인터럽트도 false)
     */
    public boolean acquire(long maxWaitMs) {
        if (minIntervalMs <= 0) {
            return true;
        }
        long wait;
        synchronized (this) {
            long now = clock.millis();
            long slot = Math.max(now, nextFreeAt);
            wait = slot - now;
            if (wait > maxWaitMs) {
                return false;
            }
            nextFreeAt = slot + minIntervalMs;
        }
        if (wait > 0) {
            try {
                sleeper.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }
}
