package com.insightforge.rule.engine;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 本地锁桩（教学/沙箱零依赖）。生产换 Redisson 同名锁即可，语义一致。
 *
 * {@link ReentrantLock#tryLock()} 立即返回：锁空闲则获取，已被持有则 false（不阻塞）——
 * 完美实现「0ms Fail-Fast」纪律。并发场景下，只有一个线程能持有，其余立即放弃，
 * 因此绝不会因 AI 调拨而拖慢用户抢购。
 */
@Component
public class LocalLockService implements LockService {

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    @Override
    public boolean tryLock(String lockKey) {
        ReentrantLock lock = locks.computeIfAbsent(lockKey, k -> new ReentrantLock());
        return lock.tryLock();
    }

    @Override
    public void unlock(String lockKey) {
        ReentrantLock lock = locks.get(lockKey);
        if (lock != null && lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
