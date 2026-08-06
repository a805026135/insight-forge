package com.insightforge.api;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.insightforge.persistence.entity.ActivitySku;
import com.insightforge.persistence.mapper.ActivitySkuMapper;
import com.insightforge.persistence.mapper.OrdersMapper;
import com.insightforge.seckill.core.dto.SeckillRequest;
import com.insightforge.seckill.core.service.SeckillService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Phase 4 幂等回归测试（local 零依赖路径）。
 *
 * 证明：同一个用户、同一个活动、同一个 SKU，重复下单两次，最终只生成 1 张订单，
 * 且 DB 库存只被扣减 1 次（绝不因重复请求而多扣 / 多建单）。
 * 这正是 Phase 4 引入的「order_id 唯一约束幂等」要守护的不变量。
 *
 * 若有人日后改坏了幂等逻辑（比如又用回随机 UUID 订单号），这条测试会立刻变红。
 */
@SpringBootTest
@ActiveProfiles("local")
class SeckillIdempotencyTest {

    @Autowired
    private SeckillService seckillService;
    @Autowired
    private ActivitySkuMapper activitySkuMapper;
    @Autowired
    private OrdersMapper ordersMapper;

    /** 每个测试前把 sku=1 的库存复位成 100、已售 0，并清空订单表，保证独立可重复。 */
    @BeforeEach
    void reset() {
        ActivitySku sku = activitySkuMapper.selectById(1L);
        sku.setStock(100);
        sku.setSold(0);
        activitySkuMapper.updateById(sku);
        ordersMapper.delete(new QueryWrapper<>()); // 清空订单表
    }

    @Test
    void same_user_duplicate_request_creates_only_one_order() {
        SeckillRequest req = new SeckillRequest();
        req.setActivityId(1L);
        req.setSkuId(1L);
        req.setUserId(7777L);   // 同一用户
        req.setQty(1);

        // 第一次抢购
        var r1 = seckillService.seckill(req);
        // 第二次：模拟用户重复点击 / 网络重试（完全相同的请求）
        var r2 = seckillService.seckill(req);

        // 两次都应成功（幂等返回既有订单号，而不是报错）
        assertEquals(0, r1.getCode(), "首次抢购应成功");
        assertEquals(0, r2.getCode(), "重复请求应幂等成功（返回既有订单号）");
        // 两次返回的订单号必须一致（确定性 orderId）
        assertEquals(r1.getData(), r2.getData(), "两次订单号必须一致（确定性幂等键）");

        // 核心断言：只生成 1 张订单、库存只扣 1 次
        long orderCount = ordersMapper.selectCount(null);
        ActivitySku sku = activitySkuMapper.selectById(1L);
        assertEquals(1, orderCount, "同一用户重复抢购只能生成 1 张订单（exactly-once）");
        assertEquals(99, sku.getStock(), "库存只应被扣减 1 次");
        assertEquals(1, sku.getSold(), "已售数只应 +1");
    }
}
