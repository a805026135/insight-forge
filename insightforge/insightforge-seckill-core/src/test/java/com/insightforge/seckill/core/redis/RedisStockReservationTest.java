package com.insightforge.seckill.core.redis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisStockReservationTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private RedisScript<Long> deductScript;
    @Mock
    private RedisScript<Long> reserveScript;
    @Mock
    private ZSetOperations<String, String> zset;

    private RedisStockService service;

    @BeforeEach
    void setUp() {
        service = new RedisStockService(redis, deductScript, reserveScript);
    }

    @Test
    void mapsAtomicScriptOutcomes() {
        when(redis.execute(eq(reserveScript), anyList(), any(Object[].class)))
                .thenReturn(1L, 2L, 0L, -1L, -2L);

        assertEquals(ReservationResult.SUCCESS, reserve());
        assertEquals(ReservationResult.DUPLICATE, reserve());
        assertEquals(ReservationResult.SOLD_OUT, reserve());
        assertEquals(ReservationResult.NOT_PREHEATED, reserve());
        assertEquals(ReservationResult.ERROR, reserve());
    }

    @Test
    void readsDueReservationsInBoundedBatchAndAcknowledges() {
        when(redis.opsForZSet()).thenReturn(zset);
        when(zset.rangeByScore(RedisKeys.ORDER_RESERVATION_PENDING, 0, 1000, 0, 100))
                .thenReturn(Set.of("OF-1"));

        assertEquals(List.of("OF-1"), service.dueReservations(1000, 100));
        service.acknowledgeReservation("OF-1");

        verify(zset).remove(RedisKeys.ORDER_RESERVATION_PENDING, "OF-1");
    }

    @Test
    void redisFailureDoesNotPretendReservationSucceeded() {
        when(redis.execute(eq(reserveScript), anyList(), any(Object[].class)))
                .thenThrow(new IllegalStateException("redis down"));
        assertEquals(ReservationResult.ERROR, reserve());
    }

    private ReservationResult reserve() {
        return service.reserve(1, 2, 1, "OF-1", "{\"orderId\":\"OF-1\"}");
    }
}
