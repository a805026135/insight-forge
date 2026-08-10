package com.insightforge.seckill.core.redis;

import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 订单幂等服务（Phase 4）。
 *
 * 核心就一个 Redis 原语：SETNX（SET if Not eXists，不存在才设值）。
 * 我们用它给「某次抢购」占一个坑：key = order:idemp:{orderId}，只有第一次抢的人能占到，
 * 之后同一 orderId 再来（用户重复点击 / 网络重试）就会「占到失败」——从而被判定为重复请求。
 *
 * 为什么需要它？
 *   秒杀扣减（无论 Redis Lua 还是 DB 条件更新）本身**不认用户**——同一个用户连点两次，
 *   会被当成两次独立的抢购，库存被扣两次、却只该有一张单。SETNX 这层「幂等闸门」就是堵这个漏洞。
 *
 * 为什么要 TTL？
 *   正常一次抢购秒级内就能在 DB 落单，10 分钟绰绰有余；给个 TTL 是为了不让「占位」永久占用
 *   Redis 内存（也留出「活动结束后释放」的语义）。即便极端情况下 TTL 比落单慢，
 *   消费者还有 DB 唯一约束（order_id UNIQUE）这道最终兜底，不会重复建单。
 *
 * 仅「非 local」profile 生效：local 零依赖冒烟下没有 Redis，本 Bean 不创建，
 * SeckillService 检测到它不存在就自动降级（local 路径改用 DB 唯一约束做幂等）。
 */
@Service
@Profile("!local")
public class RedisIdempotentService {

    private final StringRedisTemplate redis;

    public RedisIdempotentService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 抢占幂等槽。
     *
     * @param payload 订单载荷 JSON（供补偿巡检在订单丢失时重建，传 null 退化为占位 "1"）
     * @return true 表示「你是这次抢购的第一个」，可以放行扣减；
     *         false 表示「这个 orderId 已经有人占过坑了」，应当直接当作重复请求处理。
     *
     * {@code setIfAbsent} 就是 Redis 的 SETNX，原子操作，并发安全。
     */
    public boolean tryAcquire(String orderId, String payload) {
        String key = RedisKeys.orderIdempotent(orderId);
        // 第三个参数是 TTL：占位最多保留 10 分钟，防止内存泄漏
        Boolean ok = redis.opsForValue().setIfAbsent(key, payload == null ? "1" : payload, Duration.ofMinutes(10));
        return Boolean.TRUE.equals(ok);
    }

    /** 兼容旧调用：不存载荷（无补偿能力的简单占位）。 */
    public boolean tryAcquire(String orderId) {
        return tryAcquire(orderId, null);
    }

    /** 主动释放幂等槽（仅当这次抢购「没真正扣成」时才调用，避免误释放他人占位）。 */
    public void release(String orderId) {
        redis.delete(RedisKeys.orderIdempotent(orderId));
    }
}
