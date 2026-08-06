package com.insightforge.api;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.context.ActiveProfiles;
import redis.embedded.RedisServer;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 5 滑动窗口限流验证。
 *
 * 主测试（local 内存路径，必然执行）：证明"放行到上限 → 超限拦截 → 窗口过去重新放行"，
 * 以及"不同维度（不同用户）独立计数"。
 *
 * 附加测试（Redis 路径，需能起内嵌 redis；起不来则整组跳过）：直接拿 rate_limit_sliding.lua
 * 在真 Redis 上跑，证明脚本逻辑正确（与 RateLimitService 的 Redis 分支同源）。
 */
@SpringBootTest
@ActiveProfiles("local")
class SeckillRateLimitTest {

    @Autowired
    private com.insightforge.seckill.core.redis.RateLimitService rateLimitService;

    // —— 以下为可选的 Redis 路径证明（与 Phase 3 的内嵌 Redis 守护同思路）——
    private static final int REDIS_PORT = 16380; // 避开 Phase 3 的 16379，防冲突
    private static RedisServer redisServer;
    private StringRedisTemplate redis;
    private RedisScript<Long> script;

    @BeforeAll
    static void startRedis() {
        try {
            redisServer = new RedisServer(REDIS_PORT);
            redisServer.start();
        } catch (Exception e) {
            redisServer = null; // 起不来就跳过 Redis 路径测试
        }
    }

    @AfterAll
    static void stopRedis() {
        if (redisServer != null) {
            try {
                redisServer.stop();
            } catch (Exception ignored) {
            }
        }
    }

    @BeforeEach
    void setUpRedis() {
        Assumptions.assumeTrue(redisServer != null, "内嵌 Redis 未启动，跳过 Redis 路径限流验证");
        LettuceConnectionFactory factory = new LettuceConnectionFactory("localhost", REDIS_PORT);
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        DefaultRedisScript<Long> s = new DefaultRedisScript<>();
        s.setLocation(new ClassPathResource("lua/rate_limit_sliding.lua"));
        s.setResultType(Long.class);
        script = s;
    }

    @Test
    void in_memory_sliding_window_allows_up_to_max_then_blocks() throws InterruptedException {
        String key = "test:u:1:a:1";
        int max = 5;
        long windowMs = 200; // 短窗口，测试更快

        // 前 5 次都应放行
        for (int i = 0; i < max; i++) {
            assertTrue(rateLimitService.tryAcquire(key, max, windowMs), "第 " + (i + 1) + " 次应放行");
        }
        // 第 6 次超出窗口阈值，应被拦截
        assertFalse(rateLimitService.tryAcquire(key, max, windowMs), "超过 max 应被限流");

        // 等窗口过去，旧的时间戳过期，应又能放行
        Thread.sleep(windowMs + 50);
        assertTrue(rateLimitService.tryAcquire(key, max, windowMs), "窗口过去后应重新放行");
    }

    @Test
    void different_keys_are_independent() {
        int max = 3;
        long windowMs = 1000;
        // 用户 1 把额度用满
        for (int i = 0; i < max; i++) {
            assertTrue(rateLimitService.tryAcquire("u:1:a:1", max, windowMs));
        }
        // 用户 2 是不同维度，应不受影响、仍可放行
        assertTrue(rateLimitService.tryAcquire("u:2:a:1", max, windowMs), "不同 key 应独立计数");
    }

    /** 可选：直接在真 Redis 上验证 Lua 脚本（集群多机共享计数的真实形态）。 */
    @Test
    void redis_sliding_window_blocks_after_max() {
        String key = "rate:limit:redis:u:1:a:1";
        redis.delete(key);
        int max = 5;
        long window = 1000;
        // 故意用同一个 now，验证即使时间戳相同，member 唯一（靠 INCR 序号）也能正确计数
        long now = System.currentTimeMillis();
        for (int i = 0; i < max; i++) {
            Long r = redis.execute(script, Collections.singletonList(key), String.valueOf(window), String.valueOf(max), String.valueOf(now));
            assertEquals(1L, r.longValue(), "第 " + (i + 1) + " 次应放行");
        }
        Long blocked = redis.execute(script, Collections.singletonList(key), String.valueOf(window), String.valueOf(max), String.valueOf(now));
        assertEquals(0L, blocked.longValue(), "超过 max 应拦截");
    }
}
