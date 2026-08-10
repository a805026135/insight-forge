package com.insightforge.api.controller;

import com.insightforge.common.result.ApiResult;
import com.insightforge.api.auth.AuthContextHolder;
import com.insightforge.api.dto.OrderView;
import com.insightforge.persistence.entity.Activity;
import com.insightforge.persistence.entity.Orders;
import com.insightforge.persistence.entity.Product;
import com.insightforge.persistence.mapper.ActivityMapper;
import com.insightforge.persistence.mapper.OrdersMapper;
import com.insightforge.persistence.mapper.ProductMapper;
import com.insightforge.seckill.core.service.OrderStateService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 订单状态机接入层（Phase 6）：演示订单生命周期迁转。
 *
 * GET  /api/order?userId={userId}       按用户查订单（我的订单页）
 * POST /api/order/{orderId}/cancel      取消并回补库存（CREATE_ORDER → CANCEL，有真实副作用）
 * POST /api/order/{orderId}/pay         标记支付（CREATE_ORDER → PAY → DONE，仅演示状态机，无真实支付）
 */
@RestController
@RequestMapping("/api/order")
public class OrderController {

    private final OrderStateService orderStateService;
    private final OrdersMapper ordersMapper;
    private final ProductMapper productMapper;
    private final ActivityMapper activityMapper;

    public OrderController(OrderStateService orderStateService, OrdersMapper ordersMapper,
                           ProductMapper productMapper, ActivityMapper activityMapper) {
        this.orderStateService = orderStateService;
        this.ordersMapper = ordersMapper;
        this.productMapper = productMapper;
        this.activityMapper = activityMapper;
    }

    /** 按用户 id 查订单列表（最近下单的排前面），给前端「我的订单」页用。 */
    @GetMapping
    public ApiResult<List<OrderView>> listByUser(@RequestParam("userId") Long userId) {
        long uid = AuthContextHolder.requireUserId();
        if (userId != null && userId != uid) {
            return ApiResult.fail(403, "无权查看他人订单");
        }
        List<Orders> orders = ordersMapper.findByUserId(uid);
        if (orders.isEmpty()) {
            return ApiResult.ok(List.of());
        }
        Set<Long> productIds = orders.stream().map(Orders::getSkuId).collect(Collectors.toSet());
        Map<Long, Product> productMap = productMapper.selectBatchIds(productIds).stream()
                .collect(Collectors.toMap(Product::getId, p -> p));
        Set<Long> activityIds = orders.stream().map(Orders::getActivityId).collect(Collectors.toSet());
        Map<Long, Activity> activityMap = activityMapper.selectBatchIds(activityIds).stream()
                .collect(Collectors.toMap(Activity::getId, a -> a));
        return ApiResult.ok(orders.stream().map(o -> {
            Product p = productMap.get(o.getSkuId());
            Activity a = activityMap.get(o.getActivityId());
            return new OrderView(
                    o.getOrderId(), o.getUserId(), o.getActivityId(), o.getSkuId(), o.getQty(),
                    o.getPrice(), o.getStatus(), o.getCreatedAt(),
                    p != null ? p.getName() : "未知商品",
                    p != null ? p.getImageUrl() : "",
                    a != null ? a.getName() : "未知活动");
        }).collect(Collectors.toList()));
    }

    @PostMapping("/{orderId}/cancel")
    public ApiResult<Void> cancel(@PathVariable String orderId) {
        return orderStateService.cancel(orderId);
    }

    @PostMapping("/{orderId}/pay")
    public ApiResult<Void> pay(@PathVariable String orderId) {
        return orderStateService.markPaid(orderId);
    }
}
