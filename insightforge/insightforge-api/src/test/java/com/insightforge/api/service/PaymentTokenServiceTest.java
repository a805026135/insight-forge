package com.insightforge.api.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaymentTokenServiceTest {

    @Test
    void tokenCanOnlyBeConsumedOnce() {
        PaymentTokenService service = new PaymentTokenService();
        String token = service.issue("ORDER-1", 1L).token();

        assertEquals("ORDER-1", service.consume(token).orderId());
        assertThrows(IllegalArgumentException.class, () -> service.consume(token));
    }

    @Test
    void concurrentConsumersHaveExactlyOneWinner() throws Exception {
        PaymentTokenService service = new PaymentTokenService();
        String token = service.issue("ORDER-2", 2L).token();
        int consumers = 16;
        CountDownLatch ready = new CountDownLatch(consumers);
        CountDownLatch start = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(consumers);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < consumers; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        service.consume(token);
                        return true;
                    } catch (IllegalArgumentException ignored) {
                        return false;
                    }
                }));
            }
            ready.await();
            start.countDown();

            long winners = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) {
                    winners++;
                }
            }
            assertEquals(1, winners);
        } finally {
            pool.shutdownNow();
        }
    }
}
