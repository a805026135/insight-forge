package com.insightforge.rule.engine;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 分布式锁（Redisson 实现，@Profile("integration")）。
 *
 * 与秒杀热路径用「同一把锁」：key 形如 lock:a:{aid}:s:{skid}（见 {@link RuleEngineRedisKeys#lock}），
 * 逐字符等于 seckill-core {@code RedisKeys.lock}——所以「用户扣减」和「AI 调拨」在 Redis 里争的是同一把锁。
 *
 * 0ms Fail-Fast 纪律：{@code tryLock(0, MILLISECONDS)} 立即返回——用户正在抢购就放弃这一轮，
 * 绝不阻塞用户热路径。这是「用户流量永远优先」的硬保障：AI 调拨抢不到锁就下一拍（5s 后）再试。
 */
@Component
@Profile("integration")
public class RedissonLockService implements LockService {

    private final RedissonClient redisson;

    public RedissonLockService(RedissonClient redisson) {
        this.redisson = redisson;
    }

    @Override
    public boolean tryLock(String lockKey) {
        RLock lock = redisson.getLock(lockKey);
        try {
            // 0ms 等待：拿不到立刻返回 false（Fail-Fast，用户优先）。绝不使用带等待时间的 tryLock。
            return lock.tryLock(0, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public void unlock(String lockKey) {
        RLock lock = redisson.getLock(lockKey);
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
