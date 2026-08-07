package com.insightforge.rule.engine;

import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

/**
 * 验证 Redisson 锁实现：与秒杀热路径「同一把锁」（key 形如 lock:a:{aid}:s:{skid}）+ 0ms Fail-Fast。
 */
class RedissonLockServiceTest {

    @Test
    void tryLockReturnsFalseWhenRedissonCannotAcquire() throws InterruptedException {
        RedissonClient client = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(client.getLock("lock:a:1:s:1")).thenReturn(lock);
        when(lock.tryLock(0, TimeUnit.MILLISECONDS)).thenReturn(false);

        LockService svc = new RedissonLockService(client);
        assertFalse(svc.tryLock("lock:a:1:s:1"));
        // 拿不到锁绝不应该调用 unlock（Fail-Fast，不阻塞用户热路径）
        verify(lock, never()).unlock();
    }

    @Test
    void tryLockReturnsTrueWhenAcquired() throws InterruptedException {
        RedissonClient client = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(client.getLock("lock:a:1:s:1")).thenReturn(lock);
        when(lock.tryLock(0, TimeUnit.MILLISECONDS)).thenReturn(true);

        LockService svc = new RedissonLockService(client);
        assertTrue(svc.tryLock("lock:a:1:s:1"));
    }

    @Test
    void unlockOnlyWhenHeldByCurrentThread() {
        RedissonClient client = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(client.getLock("lock:a:1:s:1")).thenReturn(lock);
        when(lock.isHeldByCurrentThread()).thenReturn(false);

        LockService svc = new RedissonLockService(client);
        svc.unlock("lock:a:1:s:1");
        verify(lock, never()).unlock();
    }
}
