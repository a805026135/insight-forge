package com.insightforge.seckill.core.service;

import com.insightforge.common.result.ApiResult;
import com.insightforge.persistence.entity.ActivitySku;
import com.insightforge.persistence.entity.Orders;
import com.insightforge.persistence.mapper.ActivitySkuMapper;
import com.insightforge.persistence.mapper.OrdersMapper;
import com.insightforge.seckill.core.dto.SeckillRequest;
import com.insightforge.seckill.core.dto.StockInfo;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 最小秒杀主流程（Phase 1，★故意不安全★）。
 *
 * 步骤：查库存 → 应用层判断「有货」→ 扣库存 → 建订单。
 * 全程同步、没有任何并发保护（无锁 / 无乐观锁 / 扣减 SQL 无 stock>=qty 条件）。
 *
 * 目的：先让「用户点抢购 → 下单成功」这条业务链路跑通，建立直观理解；
 *       同时把「并发会超卖」这个缺陷故意暴露出来，留给 Phase 2（DB 兜底）
 *       和 Phase 3（Redis+Lua 原子）去解决——这比一上来就上锁更能讲清「为什么需要这些技术」。
 */
@Service
public class SeckillService {

    private final ActivitySkuMapper activitySkuMapper;
    private final OrdersMapper ordersMapper;

    public SeckillService(ActivitySkuMapper activitySkuMapper, OrdersMapper ordersMapper) {
        this.activitySkuMapper = activitySkuMapper;
        this.ordersMapper = ordersMapper;
    }

    public ApiResult<String> seckill(SeckillRequest req) {
        // 1) 查库存（一次独立的 SELECT 查询）
        ActivitySku sku = activitySkuMapper.selectById(req.getSkuId());
        if (sku == null) {
            return ApiResult.fail(404, "商品不存在");
        }

        // 2) 应用层判断「还有货」——⚠️ 仅应用层判断，不是原子的
        if (sku.getStock() < req.getQty()) {
            return ApiResult.fail(409, "已售罄");
        }

        // 3) 扣库存——⚠️ decrementStock 的 SQL 没有 WHERE stock >= qty 条件，
        //    多个线程都通过上面第 2 步后，会一起把库存扣成负数（超卖）。
        activitySkuMapper.decrementStock(sku.getId(), req.getQty());

        // 4) 建订单
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
