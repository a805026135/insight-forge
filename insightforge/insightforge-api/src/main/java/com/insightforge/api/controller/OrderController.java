package com.insightforge.api.controller;

import com.insightforge.common.result.ApiResult;
import com.insightforge.seckill.core.service.OrderStateService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单状态机接入层（Phase 6）：演示订单生命周期迁转。
 *
 * POST /api/order/{orderId}/cancel   取消并回补库存（CREATE_ORDER → CANCEL，有真实副作用）
 * POST /api/order/{orderId}/pay      标记支付（CREATE_ORDER → PAY → DONE，仅演示状态机，无真实支付）
 */
@RestController
@RequestMapping("/api/order")
public class OrderController {

    private final OrderStateService orderStateService;

    public OrderController(OrderStateService orderStateService) {
        this.orderStateService = orderStateService;
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
