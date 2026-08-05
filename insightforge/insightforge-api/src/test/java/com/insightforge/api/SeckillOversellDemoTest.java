package com.insightforge.api;

import com.insightforge.persistence.entity.ActivitySku;
import com.insightforge.persistence.mapper.ActivitySkuMapper;
import com.insightforge.persistence.mapper.OrdersMapper;
import com.insightforge.seckill.core.dto.SeckillRequest;
import com.insightforge.seckill.core.service.SeckillService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Phase 1 超卖缺陷演示（重要教学资产，不是普通业务测试）。
 *
 * 它用 300 个并发线程同时抢购「库存仅 100」的 SKU，复现「查库存 → 判断 → 扣减」
 * 三步非原子导致的超卖：要么剩余库存被扣成负数，要么订单数超过初始库存。
 *
 * ⚠️ 这条测试在 Phase 1 是“预期通过”的（它要证明缺陷确实存在）。
 *    一旦 Phase 2（DB 条件更新兜底）或 Phase 3（Redis+Lua 原子扣减）修好超卖，
 *    本测试会开始失败——届时请把它删除或改成断言“不再超卖”，作为修复的回归看护。
 */
@SpringBootTest
@ActiveProfiles("local")
class SeckillOversellDemoTest {

    @Autowired
    private SeckillService seckillService;
    @Autowired
    private ActivitySkuMapper activitySkuMapper;
    @Autowired
    private OrdersMapper ordersMapper;

    @Test
    void demo_phase1_oversell_bug() throws InterruptedException {
        Long skuId = 1L;
        int threadCount = 300;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final long uid = 1000L + i;
            pool.submit(() -> {
                try {
                    startGate.await(); // 让所有线程同时起跑，最大化竞态窗口
                    SeckillRequest req = new SeckillRequest();
                    req.setActivityId(1L);
                    req.setSkuId(skuId);
                    req.setUserId(uid);
                    req.setQty(1);
                    seckillService.seckill(req);
                } catch (Exception ignored) {
                    // 单线程视角下的“售罄”等异常忽略，重点是并发竞态
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown();
        doneGate.await();
        pool.shutdown();

        ActivitySku sku = activitySkuMapper.selectById(skuId);
        Long orderCount = ordersMapper.selectCount(null);

        System.out.println("==============================================");
        System.out.println(">>> Phase1 超卖演示结果：");
        System.out.println(">>>   初始库存 = 100");
        System.out.println(">>>   剩余库存 stock = " + sku.getStock());
        System.out.println(">>>   订单总数       = " + orderCount);
        System.out.println(">>>   是否超卖 = " + (sku.getStock() < 0 || orderCount > 100));
        System.out.println("==============================================");

        // 预期：Phase 1 存在超卖缺陷（库存<0 或 订单数>100）
        org.junit.jupiter.api.Assertions.assertTrue(
                sku.getStock() < 0 || orderCount > 100,
                "预期 Phase1 存在超卖缺陷；若未触发，请加大并发线程数后再观察（该缺陷将在 Phase 2/3 修复）"
        );
    }
}
