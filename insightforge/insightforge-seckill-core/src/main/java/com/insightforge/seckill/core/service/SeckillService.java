package com.insightforge.seckill.core.service;

import com.insightforge.common.result.ApiResult;
import com.insightforge.persistence.entity.ActivitySku;
import com.insightforge.persistence.entity.Orders;
import com.insightforge.persistence.mapper.ActivitySkuMapper;
import com.insightforge.persistence.mapper.OrdersMapper;
import com.insightforge.seckill.core.dto.SeckillRequest;
import com.insightforge.seckill.core.dto.StockInfo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 最小秒杀主流程（Phase 2，DB 层防超卖）。
 *
 * 步骤：快速查库存（友好失败，非原子）→ ★原子条件更新扣库存★（真正的防线）
 *       → 据受影响行数判断 → 扣成功才建订单。
 * 扣库存与建订单包在同一事务里：要么都成、要么都回滚，保证 stock 与 order 不割裂。
 *
 * 与 Phase 1 的关键差异：
 * - Phase 1：先 SELECT 判断「有货」，再无脑 UPDATE（两步不原子）→ 并发超卖。
 * - Phase 2：把「库存 >= 扣减」的判断塞进 UPDATE 的 WHERE 子句，靠 DB 单行更新的
 *            原子性兜底——即使并发打满，库存也绝不为负，订单数绝不超过初始库存。
 */
@Service
public class SeckillService {

    private final ActivitySkuMapper activitySkuMapper;
    private final OrdersMapper ordersMapper;

    public SeckillService(ActivitySkuMapper activitySkuMapper, OrdersMapper ordersMapper) {
        this.activitySkuMapper = activitySkuMapper;
        this.ordersMapper = ordersMapper;
    }

    @Transactional
    public ApiResult<String> seckill(SeckillRequest req) {
        // 1) 查库存：友好的「快速失败」优化，避免明知售罄还去打一次 UPDATE。
        //    ⚠️ 这一步不是原子保护、也不是防超卖的防线；真正防线是第 2 步的 SQL。
        ActivitySku sku = activitySkuMapper.selectById(req.getSkuId());
        if (sku == null) {
            return ApiResult.fail(404, "商品不存在");
        }
        if (sku.getStock() < req.getQty()) {
            return ApiResult.fail(409, "已售罄");
        }

        // 2) ★原子条件更新★：只有 stock >= qty 时 DB 才会真正扣减（单行更新原子执行）。
        //    并发下多个事务会在这行上串行排队，每个都在锁内重新校验 stock>=qty，
        //    所以绝不会有两个事务同时把库存扣穿。
        int affected = activitySkuMapper.decrementStock(sku.getId(), req.getQty());

        // 3) 受影响行数 = 真实结果：0 表示并发下这最后一次已被别人抢光（条件未命中），不建单。
        if (affected == 0) {
            return ApiResult.fail(409, "已售罄");
        }

        // 4) 扣减成功，才落订单（与扣减同事务，失败一起回滚，不会「扣了库存却没单」）。
        Orders order = new Orders();
        order.setOrderId(UUID.randomUUID().toString().replace("-", ""));
        order.setUserId(req.getUserId());
        order.setActivityId(req.getActivityId());
        order.setSkuId(req.getSkuId());
        order.setQty(req.getQty());
        order.setStatus("CREATED");
        order.setCreatedAt(LocalDateTime.now());
        ordersMapper.insert(order);

        return ApiResult.ok(order.getOrderId());
    }

    /** 查询某 SKU 的库存情况，便于手动验证与演示超卖。 */
    public ApiResult<StockInfo> stockInfo(Long skuId) {
        ActivitySku sku = activitySkuMapper.selectById(skuId);
        if (sku == null) {
            return ApiResult.fail(404, "商品不存在");
        }
        StockInfo info = new StockInfo();
        info.setSkuId(skuId);
        info.setTotalStock(sku.getTotalStock());
        info.setStock(sku.getStock());
        info.setSold(sku.getSold());
        return ApiResult.ok(info);
    }
}
