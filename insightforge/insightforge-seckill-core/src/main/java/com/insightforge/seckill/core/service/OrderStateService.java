package com.insightforge.seckill.core.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.insightforge.common.result.ApiResult;
import com.insightforge.persistence.entity.ActivitySku;
import com.insightforge.persistence.entity.Orders;
import com.insightforge.persistence.mapper.ActivitySkuMapper;
import com.insightforge.persistence.mapper.OrdersMapper;
import com.insightforge.seckill.core.cache.SkuCacheService;
import com.insightforge.seckill.core.state.OrderStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 订单状态机服务（Phase 6）。
 *
 * 大白话：一张订单从「下单成功」到「支付完成 / 取消」要按规定走。这个 Service 演示订单
 * 生命周期迁转，并实现唯一一个有真实副作用的操作——「取消订单并回补库存」。
 *
 * 范围克制（诚实）：支付链路（PAY→DONE）属于业务范围红线，本 Phase 只暴露迁转 API 用于
 * 演示状态机合法 / 非法迁转，不接真实支付回调。对账时「成功单」= status != CANCEL 的订单，
 * 因此 CANCEL 不会污染结算公式。
 */
@Service
public class OrderStateService {

    private final OrdersMapper ordersMapper;
    private final ActivitySkuMapper skuMapper;
    private final SkuCacheService skuCacheService;

    public OrderStateService(OrdersMapper ordersMapper,
                             ActivitySkuMapper skuMapper,
                             SkuCacheService skuCacheService) {
        this.ordersMapper = ordersMapper;
        this.skuMapper = skuMapper;
        this.skuCacheService = skuCacheService;
    }

    /**
     * 取消订单：CREATE_ORDER / PAY → CANCEL，并回补库存（stock+=qty, sold-=qty）。
     * - 幂等：已经是 CANCEL 直接返回成功，重复取消不会重复回补。
     * - 状态机守门：DONE（已完成）不允许取消，会抛非法迁转异常。
     * - 库存回补走条件更新（sold >= qty），防止 sold 扣成负数。
     */
    @Transactional
    public ApiResult<Void> cancel(String orderId) {
        Orders order = ordersMapper.selectOne(new QueryWrapper<Orders>().eq("order_id", orderId));
        if (order == null) {
            return ApiResult.fail(404, "订单不存在");
        }
        OrderStatus cur = OrderStatus.from(order.getStatus());
        if (cur == OrderStatus.CANCEL) {
            return ApiResult.ok(null); // 已取消，幂等返回
        }
        cur.transitionTo(OrderStatus.CANCEL); // 仅 CREATE_ORDER / PAY 可取消；DONE 会抛异常

        // 订单里存的是 product.id（=sku_id），库存表主键是 id，需按 (activity_id, sku_id) 定位
        ActivitySku sku = skuMapper.selectOne(new QueryWrapper<ActivitySku>()
                .eq("activity_id", order.getActivityId())
                .eq("sku_id", order.getSkuId()));
        if (sku == null) {
            return ApiResult.fail(404, "库存记录不存在");
        }
        int aff = skuMapper.incrementStock(sku.getId(), order.getQty());
        if (aff == 0) {
            return ApiResult.fail(409, "库存回补失败（sold 不足，可能已回补）");
        }
        order.setStatus(OrderStatus.CANCEL.name());
        ordersMapper.updateById(order);
        skuCacheService.evict(order.getSkuId()); // 失效缓存，下次读回源最新库存
        return ApiResult.ok(null);
    }

    /**
     * 标记支付（仅演示状态机迁转，无真实支付）：CREATE_ORDER → PAY → DONE。
     * 链式 transitionTo：任一步非法（如从 CANCEL 直接 DONE）立即抛异常。
     */
    @Transactional
    public ApiResult<Void> markPaid(String orderId) {
        Orders order = ordersMapper.selectOne(new QueryWrapper<Orders>().eq("order_id", orderId));
        if (order == null) {
            return ApiResult.fail(404, "订单不存在");
        }
        OrderStatus cur = OrderStatus.from(order.getStatus());
        cur.transitionTo(OrderStatus.PAY).transitionTo(OrderStatus.DONE);
        order.setStatus(OrderStatus.DONE.name());
        ordersMapper.updateById(order);
        return ApiResult.ok(null);
    }
}
