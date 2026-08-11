package com.insightforge.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.insightforge.common.result.ApiResult;
import com.insightforge.api.auth.AuthContextHolder;
import com.insightforge.api.dto.OrderView;
import com.insightforge.api.dto.PayInfoView;
import com.insightforge.api.service.PaymentTokenService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
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
    private final PaymentTokenService paymentTokenService;

    public OrderController(OrderStateService orderStateService, OrdersMapper ordersMapper,
                           ProductMapper productMapper, ActivityMapper activityMapper,
                           PaymentTokenService paymentTokenService) {
        this.orderStateService = orderStateService;
        this.ordersMapper = ordersMapper;
        this.productMapper = productMapper;
        this.activityMapper = activityMapper;
        this.paymentTokenService = paymentTokenService;
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

    /** POST /api/order/{orderId}/pay-request 发起支付：生成一次性确认令牌（手机端确认用） */
    @PostMapping("/{orderId}/pay-request")
    public ApiResult<Map<String, String>> payRequest(@PathVariable String orderId) {
        long userId = AuthContextHolder.requireUserId();
        Orders o = ordersMapper.selectOne(new QueryWrapper<Orders>().eq("order_id", orderId));
        if (o == null || o.getUserId() == null || o.getUserId() != userId) {
            return ApiResult.fail(403, "订单不存在或无权操作");
        }
        if (!"CREATE_ORDER".equals(o.getStatus())) {
            return ApiResult.fail(400, "当前订单状态（" + o.getStatus() + "）无需支付");
        }
        PaymentTokenService.PaymentToken pt = paymentTokenService.issue(orderId, userId);
        return ApiResult.ok(Map.of("token", pt.token()));
    }

    /** GET /api/order/pay-info?token=... 支付确认页信息（扫码后手机端展示） */
    @GetMapping("/pay-info")
    public ApiResult<PayInfoView> payInfo(@RequestParam String token) {
        try {
            PaymentTokenService.PaymentToken pt = paymentTokenService.peek(token);
            Orders o = ordersMapper.selectOne(
                    new QueryWrapper<Orders>().eq("order_id", pt.orderId()));
            if (o == null) {
                return ApiResult.fail(404, "订单不存在");
            }
            Product p = o.getSkuId() == null ? null : productMapper.selectById(o.getSkuId());
            BigDecimal amount = o.getPrice() == null ? null
                    : o.getPrice().multiply(BigDecimal.valueOf(o.getQty() == null ? 1 : o.getQty()));
            return ApiResult.ok(new PayInfoView(o.getOrderId(),
                    p == null ? "未知商品" : p.getName(), amount, o.getStatus()));
        } catch (IllegalArgumentException e) {
            return ApiResult.fail(400, e.getMessage());
        }
    }

    /** POST /api/order/pay-confirm 手机端确认支付：令牌消费 + 状态 CREATE_ORDER → PAY */
    @PostMapping("/pay-confirm")
    public ApiResult<Map<String, Object>> payConfirm(@RequestBody Map<String, String> body) {
        String token = body.get("token");
        try {
            PaymentTokenService.PaymentToken pt = paymentTokenService.consume(token);
            Orders o = ordersMapper.selectOne(
                    new QueryWrapper<Orders>().eq("order_id", pt.orderId()));
            if (o == null || o.getUserId() == null || o.getUserId() != pt.userId()) {
                return ApiResult.fail(403, "订单不存在或令牌与订单不匹配");
            }
            if (!"CREATE_ORDER".equals(o.getStatus())) {
                return ApiResult.ok(Map.of("status", o.getStatus(), "message", "该订单已处理，无需重复支付"));
            }
            orderStateService.markPaid(o.getOrderId());
            return ApiResult.ok(Map.of("status", "PAY", "message", "支付成功（模拟）"));
        } catch (IllegalArgumentException e) {
            return ApiResult.fail(400, e.getMessage());
        }
    }
}
