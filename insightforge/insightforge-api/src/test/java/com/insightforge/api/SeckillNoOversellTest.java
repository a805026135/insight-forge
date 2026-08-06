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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 2 防超卖回归测试（替代 Phase 1 的超卖缺陷演示）。
 *
 * Phase 1 用这条测试证明「查→判→扣」三步非原子会导致超卖（stock=-200、订单 300）。
 * Phase 2 把「库存 >= 扣减」塞进 UPDATE 的 WHERE（原子条件更新）后，同样的 300 并发
 * 必须收敛到：stock=0、订单=100、绝不为负——这就是 DB 级超卖兜底的证明。
 *
 * 若日后有人在热路径改坏了扣减 SQL（比如又把 WHERE stock>=qty 删了），
 * 这条测试会立刻由「绿」变「红」，挡住回归。这正是它的看护价值。
 * （Phase 1 那段「故意超卖」的复现步骤见 tutorials/phase1.md，作为历史教学资产。）
 */
@SpringBootTest
@ActiveProfiles("local")
class SeckillNoOversellTest {

    @Autowired
    private SeckillService seckillService;
    @Autowired
    private ActivitySkuMapper activitySkuMapper;
    @Autowired
    private OrdersMapper ordersMapper;

    @Test
    void demo_phase2_no_oversell_regression() throws InterruptedException {
        Long skuId = 1L;
        int totalStock = 100;
        int threadCount = 300; // 远大于库存，制造激烈竞态
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
                    // 售罄等「正常业务失败」忽略，重点是并发下绝不能超卖
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
        System.out.println(">>> Phase2 防超卖回归结果：");
        System.out.println(">>>   初始库存 totalStock = " + totalStock);
        System.out.println(">>>   剩余库存 stock      = " + sku.getStock());
        System.out.println(">>>   已售 sold           = " + sku.getSold());
        System.out.println(">>>   订单总数            = " + orderCount);
        System.out.println(">>>   是否超卖            = " + (sku.getStock() < 0 || orderCount > totalStock));
        System.out.println("==============================================");

        // Phase 2 修复后的硬断言：绝不超卖，且库存被精确抢光
        assertTrue(sku.getStock() >= 0, "剩余库存绝不能为负（超卖）");
        assertEquals(0, sku.getStock(), "并发抢光 100 件后剩余库存必须收敛到 0");
        assertEquals((long) totalStock, orderCount.longValue(), "订单数必须等于初始库存 100（无超额下单）");
        assertEquals((long) totalStock, sku.getSold().longValue(), "已售数必须等于初始库存 100（sold = total - stock 对账成立）");
    }
}
