package com.insightforge.api;

import com.insightforge.seckill.core.redis.DeductResult;
import com.insightforge.seckill.core.redis.RedisKeys;
import com.insightforge.seckill.core.redis.ReservationResult;
import com.insightforge.seckill.core.redis.RedisStockService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import redis.embedded.RedisServer;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Phase 3 的"Redis + Lua 原子扣减"证明测试。
 *
 * 为什么需要它：Phase 2 已经在 DB 层证明了"不超卖"。Phase 3 把扣减搬到了 Redis，
 * 同理要证明——哪怕 300 个线程同时抢 100 件库存，Redis Lua 脚本也必须让"抢成功"的次数
 * 恰好等于 100，且 Redis 里的剩余库存精确归零、绝不为负。这就是"单线程 + Lua 原子"的铁证。
 *
 * 怎么跑（无需 Docker）：用 embedded-redis 在测试里起一个真 redis-server 进程（端口 16379，
 * 避开本机默认的 6379）。若沙箱/CI 环境无法启动这个进程（比如禁用了外部 exe），
 * 我们用 Assumptions 让整组测试"优雅跳过"——构建照样绿灯，读者在本机 `mvn test` 即可复现。
 */
class SeckillRedisLuaTest {

    /** 用 16379，避免和本机可能已在跑的 6379 冲突 */
    private static final int REDIS_PORT = 16379;

    private static RedisServer redisServer;
    private StringRedisTemplate redis;
    private RedisStockService stockService;

    /** 类开始前尝试启动内嵌 Redis；失败则把 redisServer 置 null 并跳过后续。 */
    @BeforeAll
    static void startRedis() {
        try {
            redisServer = new RedisServer(REDIS_PORT);
            redisServer.start();
        } catch (Exception e) {
            // 沙箱/无权限环境起不了 redis-server 进程：置 null，由 @BeforeEach 跳过
            redisServer = null;
        }
    }

    @AfterAll
    static void stopRedis() {
        if (redisServer != null) {
            try {
                redisServer.stop();
            } catch (Exception ignored) {
                // 关闭失败不影响构建（沙箱环境下多半根本没起来）
            }
        }
    }

    @BeforeEach
    void setUp() {
        // 若 Redis 没起来，整组测试跳过（不报错、不影响构建）
        Assumptions.assumeTrue(redisServer != null, "内嵌 Redis 未启动，跳过 Phase 3 Redis 验证（请在可运行 redis 的环境执行）");

        LettuceConnectionFactory factory = new LettuceConnectionFactory("localhost", REDIS_PORT);
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);

        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/seckill_deduct.lua"));
        script.setResultType(Long.class);

        DefaultRedisScript<Long> reserveScript = new DefaultRedisScript<>();
        reserveScript.setLocation(new ClassPathResource("lua/seckill_reserve.lua"));
        reserveScript.setResultType(Long.class);

        stockService = new RedisStockService(redis, script, reserveScript);
    }

    /** 同一 Lua 同时保证去重、扣减、恢复载荷和超时索引，重复请求不能再次扣库存。 */
    @Test
    void recoverable_reservation_is_atomic_and_idempotent() {
        long activityId = 11L;
        long skuId = 11L;
        String orderId = "OF-11-11-1001";
        redis.delete(RedisKeys.orderIdempotent(orderId));
        redis.opsForZSet().remove(RedisKeys.ORDER_RESERVATION_PENDING, orderId);
        stockService.preheat(activityId, skuId, 5);

        ReservationResult first = stockService.reserve(
                activityId, skuId, 1, orderId, "{\"orderId\":\"OF-11-11-1001\"}");
        ReservationResult duplicate = stockService.reserve(
                activityId, skuId, 1, orderId, "{\"orderId\":\"OF-11-11-1001\"}");

        assertEquals(ReservationResult.SUCCESS, first);
        assertEquals(ReservationResult.DUPLICATE, duplicate);
        assertEquals(4, stockService.getStock(activityId, skuId), "重复请求不能二次扣库存");
        assertEquals("{\"orderId\":\"OF-11-11-1001\"}",
                redis.opsForValue().get(RedisKeys.orderIdempotent(orderId)));
    }

    /** 单线程：预热 100 → 扣 1 成功 → 剩 99。 */
    @Test
    void single_deduct_success_and_stock_decreases() {
        stockService.preheat(1, 1, 100);
        assertEquals(DeductResult.SUCCESS, stockService.deduct(1, 1, 1));
        assertEquals(99, stockService.getStock(1, 1));
    }

    /** 单线程：库存 5、买 10 → 直接售罄，库存不变。 */
    @Test
    void single_deduct_sold_out_when_not_enough() {
        stockService.preheat(1, 1, 5);
        assertEquals(DeductResult.SOLD_OUT, stockService.deduct(1, 1, 10));
        assertEquals(5, stockService.getStock(1, 1)); // 没扣成，库存保持 5
    }

    /** 单线程：没预热就扣 → NOT_PREHEATED，提示该走 DB 兜底。 */
    @Test
    void single_deduct_not_preheated() {
        // 注意：这里不清空 key，直接对从未 set 过的 sku=99 扣减
        assertEquals(DeductResult.NOT_PREHEATED, stockService.deduct(1, 99, 1));
    }

    /**
     * 并发铁证：300 线程抢 100 库存，每个买 1。
     * 期望：恰好 100 次 SUCCESS，其余都 SOLD_OUT；最终 Redis 库存 = 0，绝不为负。
     */
    @Test
    void concurrent_no_oversell_300_threads_100_stock() throws InterruptedException {
        stockService.preheat(1, 1, 100);
        final int threads = 300;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threads);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    startGate.await(); // 所有线程同时发枪，制造最激烈的竞态
                    if (stockService.deduct(1, 1, 1) == DeductResult.SUCCESS) {
                        successCount.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown();
        doneGate.await();
        pool.shutdown();

        System.out.println("==============================================");
        System.out.println(">>> Phase3 Redis+Lua 并发验证：");
        System.out.println(">>>   扣减成功次数 success = " + successCount.get());
        System.out.println(">>>   Redis 剩余库存     = " + stockService.getStock(1, 1));
        System.out.println(">>>   是否超卖           = " + (stockService.getStock(1, 1) < 0 || successCount.get() > 100));
        System.out.println("==============================================");

        assertEquals(100, successCount.get(), "并发抢购成功次数必须恰好等于初始库存 100（绝不超额放行）");
        assertEquals(0, stockService.getStock(1, 1), "Redis 剩余库存必须精确归零，绝不为负");
    }
}
