package com.xingyan.shortlink.common.id;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnowflakeIdGeneratorTest {

    @Test
    void nodeRangeValidated() {
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(1024));
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(-1));
    }

    @Test
    void monotonicWithinGenerator() {
        var gen = new SnowflakeIdGenerator(7);
        long prev = -1;
        for (int i = 0; i < 10_000; i++) {
            long id = gen.nextId();
            assertTrue(id > prev, "ids must strictly increase");
            prev = id;
        }
    }

    @Test
    void sequenceOverflowStillUnique() {
        var gen = new SnowflakeIdGenerator(1);
        Set<Long> ids = new java.util.HashSet<>();
        for (int i = 0; i < 100_000; i++) {
            assertTrue(ids.add(gen.nextId()), "duplicate id at index " + i);
        }
    }

    @Test
    void concurrentSameGeneratorNoDuplicates() throws Exception {
        var gen = new SnowflakeIdGenerator(42);
        int threads = 8, perThread = 50_000;
        Set<Long> pool = ConcurrentHashMap.newKeySet();
        ExecutorService es = Executors.newFixedThreadPool(threads);
        var start = new CountDownLatch(1);
        for (int t = 0; t < threads; t++) {
            es.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                for (int i = 0; i < perThread; i++) {
                    pool.add(gen.nextId());
                }
            });
        }
        start.countDown();
        es.shutdown();
        assertTrue(es.awaitTermination(60, TimeUnit.SECONDS));
        assertEquals((long) threads * perThread, pool.size());
    }
}
