package com.insightforge.rule.engine;

import com.insightforge.agent.core.planning.Action;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证指令执行器：调拨建段 + 锁 Fail-Fast（用户流量优先）+ ALERT 不写共享状态。
 */
class ControlInstructionExecutorTest {

    private final LocalLockService lock = new LocalLockService();
    private final StockSegmentService seg = new InMemoryStockSegmentService();
    private final GuardrailMetrics metrics = new GuardrailMetrics();
    private final ControlInstructionExecutor executor =
            new ControlInstructionExecutor(lock, seg, metrics, new InMemoryPriceStore());

    @Test
    void reallocateAllocatesNewSegment() {
        ControlInstruction ci = new ControlInstruction(1, 1, Action.REALLOCATE,
                Map.of("amount", 50), RiskLevel.MEDIUM, "t");
        var r = executor.execute(ci);
        assertTrue(r.applied());
        assertEquals(1, seg.segmentCount(1, 1));
        assertEquals(50, seg.available(1, 1)); // executor 内部已 confirmPending
    }

    @Test
    void alertDoesNotWriteSharedState() {
        ControlInstruction ci = new ControlInstruction(1, 1, Action.ALERT,
                Map.of("reason", "x"), RiskLevel.LOW, "t");
        var r = executor.execute(ci);
        assertTrue(r.applied());
        assertEquals(0, seg.segmentCount(1, 1));
    }

    @Test
    void lockIsFailFastNotBlocking() throws Exception {
        String key = "a:1:s:1";
        // 模拟「用户线程」持有锁（ReentrantLock 可重入，必须用另一个线程才能触发跨线程 Fail-Fast）
        AtomicInteger holderState = new AtomicInteger(0); // 0=未持锁 1=已持锁 2=已释放
        Thread holder = new Thread(() -> {
            lock.tryLock(key);
            holderState.set(1);
            while (holderState.get() == 1) { Thread.onSpinWait(); } // 持有直到主线程通知释放
            lock.unlock(key);
            holderState.set(2);
        });
        holder.start();
        // 等用户线程真正拿到锁
        while (holderState.get() != 1) { Thread.onSpinWait(); }
        // 主线程（AI 调拨）抢同一把锁 → 立即失败（false），绝不阻塞用户热路径
        assertFalse(lock.tryLock(key), "AI 调拨抢不到用户持有的锁，应立即放弃（Fail-Fast）");
        holderState.set(0); // 通知用户线程释放锁
        holder.join(5000);
        // 用户线程释放后，主线程应能重新获取
        assertTrue(lock.tryLock(key));
        lock.unlock(key);
    }

    @Test
    void concurrentContentionKeepsFailFast() throws Exception {
        int threads = 100;
        ExecutorService es = Executors.newFixedThreadPool(threads);
        AtomicInteger acquired = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            es.submit(() -> {
                try {
                    start.await();
                    if (lock.tryLock("contend")) {
                        acquired.incrementAndGet();
                        Thread.sleep(20);
                        lock.unlock("contend");
                    }
                } catch (Exception ignored) {
                    // 并发竞争下的异常忽略
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        es.shutdownNow();
        // Fail-Fast：绝大多数线程立即失败，不会 100 人都拿到锁——用户流量优先的硬保障
        assertTrue(acquired.get() >= 1);
        assertTrue(acquired.get() < threads);
    }
}
