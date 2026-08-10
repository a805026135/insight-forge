package com.insightforge.api.controller;

import com.insightforge.common.result.ApiResult;
import com.insightforge.api.auth.AuthContextHolder;
import com.insightforge.persistence.entity.Orders;
import com.insightforge.persistence.mapper.OrdersMapper;
import com.insightforge.seckill.core.service.OrderStateService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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

    public OrderController(OrderStateService orderStateService, OrdersMapper ordersMapper) {
        this.orderStateService = orderStateService;
        this.ordersMapper = ordersMapper;
    }

    /** 按用户 id 查订单列表（最近下单的排前面），给前端「我的订单」页用。 */
    @GetMapping
    public ApiResult<List<Orders>> listByUser(@RequestParam("userId") Long userId) {
        long uid = AuthContextHolder.requireUserId();
        if (userId != null && userId != uid) {
            return ApiResult.fail(403, "无权查看他人订单");
        }
        return ApiResult.ok(ordersMapper.findByUserId(uid));
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
