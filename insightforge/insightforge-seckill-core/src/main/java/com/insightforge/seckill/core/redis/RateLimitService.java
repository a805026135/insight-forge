package com.insightforge.seckill.core.redis;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Collections;
import java.time.Duration;

/**
 * 滑动窗口限流（Phase 5）。
 *
 * ====================================================================
 * 大白话：秒杀入口像地铁闸机，得拦住"一个人疯狂刷票"的羊毛党。
 * 我们按 (用户, 活动) 维度计数——比如"同一用户同一活动，1 秒内最多放 20 次"。
 *
 * 两套实现，自动切换：
 *   - 非 local（有 Redis）：用 rate_limit_sliding.lua 在 Redis 端原子完成"清旧窗口→计数→放行/拦截"，
 *     集群多机共享同一个计数，限流才准。
 *   - local（无 Redis）：退化成 JVM 内存版滑动窗口（教学/冒烟用，单机够用）。
 *
 * 返回 true=放行，false=拦截（调用方据此返回 HTTP 429）。
 * ====================================================================
 */
@Service
public class RateLimitService {

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final ObjectProvider<RedisScript<Long>> slidingScriptProvider;
    private final boolean localFastPath;

    /**
     * 单实例快速保护层。Caffeine 的容量和空闲过期避免传统 ConcurrentHashMap
     * 限流器在大量一次性用户 key 下永久增长；业务幂等仍由 Redis Reservation 保证。
     */
    private final Cache<String, ArrayDeque<Long>> memWindows = Caffeine.newBuilder()
            .maximumSize(200_000)
            .expireAfterAccess(Duration.ofSeconds(10))
            .build();

    public RateLimitService(ObjectProvider<StringRedisTemplate> redisProvider,
                            @Qualifier("rateLimitSlidingScript") ObjectProvider<RedisScript<Long>> slidingScriptProvider,
                            @Value("${insightforge.seckill.rate-limit-mode:distributed}") String mode) {
        this.redisProvider = redisProvider;
        this.slidingScriptProvider = slidingScriptProvider;
        this.localFastPath = "local-fast".equalsIgnoreCase(mode);
    }

    /**
     * 尝试获取一次放行资格。
     * @param key      限流维度，例如 "u:1001:a:1"
     * @param max      窗口内最大放行次数
     * @param windowMs 窗口大小（毫秒）
     * @return true=放行，false=被限流
     */
    public boolean tryAcquire(String key, int max, long windowMs) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        RedisScript<Long> script = slidingScriptProvider.getIfAvailable();
        if (!localFastPath && redis != null && script != null) {
            // —— Redis 路径：脚本在单线程里原子判定，集群共享计数 ——
            Long now = System.currentTimeMillis();
            Long res = redis.execute(script,
                    Collections.singletonList(RedisKeys.rateLimit(key)),
                    String.valueOf(windowMs), String.valueOf(max), String.valueOf(now));
            return res != null && res == 1L;
        }
        // —— 内存路径（local 零依赖）——
        return tryAcquireInMemory(key, max, windowMs);
    }

    /** 纯 JVM 滑动窗口：维护每个 key 的时间戳队列，超过窗口的甩掉，超阈值的拦截。 */
    private boolean tryAcquireInMemory(String key, int max, long windowMs) {
        long now = System.currentTimeMillis();
        ArrayDeque<Long> window = memWindows.get(key, k -> new ArrayDeque<>());
        synchronized (window) {
            // 甩掉窗口外的旧时间戳（滑动窗口的核心：只认"最近 windowMs 内"的请求）
            while (!window.isEmpty() && now - window.peekFirst() >= windowMs) {
                window.pollFirst();
            }
            if (window.size() < max) {
                window.addLast(now);
                return true; // 没超阈值，放行并记录本次时间戳
            }
            return false; // 超阈值，拦截
        }
    }
}
