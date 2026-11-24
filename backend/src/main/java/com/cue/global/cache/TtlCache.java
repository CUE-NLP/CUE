package com.cue.global.cache;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 만료 시간(TTL)과 최대 개수(LRU)가 있는 작은 인메모리 캐시. Redis를 쓰지 않는 방침이라 단일 인스턴스 메모리에만 둔다.
 * 재시작하면 비워지고, 다중 인스턴스에서는 공유되지 않는다(AI 호출을 아끼는 용도라 그래도 괜찮다).
 */
public class TtlCache<K, V> {
    private record Entry<V>(V value, long expiresAtMs) {}

    private final long ttlMs;
    private final Clock clock;
    private final Map<K, Entry<V>> map;

    public TtlCache(Duration ttl, int maxEntries, Clock clock) {
        this.ttlMs = ttl.toMillis();
        this.clock = clock;
        this.map = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, Entry<V>> eldest) {
                return size() > maxEntries;
            }
        };
    }

    /** 만료되지 않은 값을 돌려준다. 없거나 만료됐으면 null. */
    public synchronized V get(K key) {
        Entry<V> e = map.get(key);
        if (e == null) {
            return null;
        }
        if (e.expiresAtMs() <= clock.millis()) {
            map.remove(key);
            return null;
        }
        return e.value();
    }

    public synchronized void put(K key, V value) {
        map.put(key, new Entry<>(value, clock.millis() + ttlMs));
    }

    public synchronized int size() {
        return map.size();
    }
}
