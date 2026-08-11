package com.insightforge.api.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.insightforge.persistence.entity.AfterSale;
import com.insightforge.persistence.entity.OrderItem;
import com.insightforge.persistence.entity.Orders;
import com.insightforge.persistence.mapper.AfterSaleMapper;
import com.insightforge.persistence.mapper.OrderItemMapper;
import com.insightforge.persistence.mapper.OrdersMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 退款/售后工单（Phase 19f）：真实退款闭环——
 * 仅本人订单可申请；PAY/DONE/SHIPPED 状态可退；同订单不可重复申请；
 * APPLIED 可取消，之后由平台侧流转 APPROVED→REFUNDED 或 REJECTED。
 */
@Service
public class AfterSaleService {

    private final AfterSaleMapper mapper;
    private final OrdersMapper ordersMapper;
    private final OrderItemMapper orderItemMapper;

    public AfterSaleService(AfterSaleMapper mapper, OrdersMapper ordersMapper, OrderItemMapper orderItemMapper) {
        this.mapper = mapper;
        this.ordersMapper = ordersMapper;
        this.orderItemMapper = orderItemMapper;
    }

    public AfterSale apply(long userId, String orderId, String reason) {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("缺少订单号");
        }
        Orders o = ordersMapper.selectOne(new QueryWrapper<Orders>().eq("order_id", orderId));
        if (o == null || o.getUserId() == null || o.getUserId() != userId) {
            throw new IllegalArgumentException("订单不存在或无权操作");
        }
        String status = o.getStatus();
        if (!("PAY".equals(status) || "DONE".equals(status) || "SHIPPED".equals(status))) {
            throw new IllegalArgumentException("当前订单状态（" + status + "）不可申请退款");
        }
        long existing = mapper.selectCount(new QueryWrapper<AfterSale>()
                .eq("user_id", userId).eq("order_id", orderId)
                .in("status", "APPLIED", "APPROVED"));
        if (existing > 0) {
            throw new IllegalArgumentException("该订单已有退款申请，请勿重复提交");
        }
        OrderItem item = orderItemMapper.selectOne(
                new QueryWrapper<OrderItem>().eq("order_id", orderId).last("LIMIT 1"));

        AfterSale a = new AfterSale();
        a.setUserId(userId);
        a.setOrderId(orderId);
        a.setSkuId(item == null ? null : item.getProductId());
        a.setProductName(item == null ? null : item.getProductName());
        a.setQty(item == null ? null : item.getQty());
        a.setAmount(o.getTotalAmount());
        a.setReason(reason == null || reason.isBlank() ? "7天无理由退货" : reason.trim());
        a.setStatus("APPLIED");
        a.setCreatedAt(LocalDateTime.now());
        a.setUpdatedAt(LocalDateTime.now());
        mapper.insert(a);
        return a;
    }

    public List<AfterSale> list(long userId) {
        return mapper.selectList(new QueryWrapper<AfterSale>()
                .eq("user_id", userId).orderByDesc("id"));
    }

    public void cancel(long userId, long id) {
        AfterSale a = mapper.selectById(id);
        if (a == null || a.getUserId() == null || a.getUserId() != userId) {
            throw new IllegalArgumentException("工单不存在或无权操作");
        }
        if (!"APPLIED".equals(a.getStatus())) {
            throw new IllegalArgumentException("仅待审核状态的工单可取消");
        }
        a.setStatus("CANCELLED");
        a.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(a);
    }
}
