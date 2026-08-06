package com.insightforge.rule.engine;

/**
 * 分布式锁抽象（大白话：用户抢购和 AI 调拨都要改库存，得用同一把锁排队，不能一起改）。
 *
 * 生产实现 = Redisson 同名锁（如 {@code lock:a:{aid}:s:{sid}}，与秒杀热路径同一把锁）；
 * 本地用 {@link LocalLockService}（ReentrantLock 桩）。无论哪种，语义一致：
 *
 * 关键纪律：{@link #tryLock(String)} 0ms 超时（Fail-Fast）——抢不到立刻返回 false，
 * 绝不阻塞用户热路径。这正是「用户流量永远优先」的硬保障：
 * AI 调拨抢不到锁就放弃这一轮，下一拍（5s 后）再试，绝不拖慢用户下单。
 */
public interface LockService {

    /**
     * 尝试获取锁，0ms 超时（Fail-Fast）。
     *
     * @return true=拿到锁；false=锁被占用，立即放弃（不阻塞）
     */
    boolean tryLock(String lockKey);

    /**
     * 释放锁（仅释放当前线程持有的锁）。
     */
    void unlock(String lockKey);
}
