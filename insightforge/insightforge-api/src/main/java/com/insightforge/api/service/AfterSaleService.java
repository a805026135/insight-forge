package com.insightforge.api.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.insightforge.persistence.entity.AfterSale;
import com.insightforge.persistence.entity.OrderItem;
import com.insightforge.persistence.entity.Orders;
import com.insightforge.persistence.mapper.AfterSaleMapper;
import com.insightforge.persistence.mapper.OrderItemMapper;
import com.insightforge.persistence.mapper.OrdersMapper;
import com.insightforge.persistence.mapper.ProductMapper;
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
    private final ProductMapper productMapper;

    public AfterSaleService(AfterSaleMapper mapper, OrdersMapper ordersMapper,
                            OrderItemMapper orderItemMapper, ProductMapper productMapper) {
        this.mapper = mapper;
        this.ordersMapper = ordersMapper;
        this.orderItemMapper = orderItemMapper;
        this.productMapper = productMapper;
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
                .in("status", "APPLIED", "APPROVED", "REFUNDING", "APPEAL"));
        if (existing > 0) {
            throw new IllegalArgumentException("该订单已有退款申请，请勿重复提交");
        }
        OrderItem item = orderItemMapper.selectOne(
                new QueryWrapper<OrderItem>().eq("order_id", orderId).last("LIMIT 1"));
        Long merchantId = item != null ? item.getMerchantId() : null;
        if (merchantId == null && o.getSkuId() != null) {
            var p = productMapper.selectById(o.getSkuId());
            merchantId = p == null ? null : p.getMerchantId();
        }

        AfterSale a = new AfterSale();
        a.setUserId(userId);
        a.setOrderId(orderId);
        a.setSkuId(item == null ? null : item.getProductId());
        a.setMerchantId(merchantId);
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

    /** 商家维度售后工单列表（按店铺隔离） */
    public List<AfterSale> merchantList(long merchantId, String status) {
        QueryWrapper<AfterSale> qw = new QueryWrapper<AfterSale>()
                .eq("merchant_id", merchantId).orderByDesc("id");
        if (status != null && !status.isBlank()) {
            qw.eq("status", status.trim().toUpperCase());
        }
        return mapper.selectList(qw);
    }

    /** 商家审核：APPLIED → APPROVED（退款通过，进入退款处理）或 REJECTED（驳回，可申诉） */
    public void review(long merchantId, long id, boolean approve, String note) {
        AfterSale a = getForMerchant(merchantId, id);
        if (!"APPLIED".equals(a.getStatus())) {
            throw new IllegalArgumentException("仅待审核工单可审核（当前：" + a.getStatus() + "）");
        }
        a.setStatus(approve ? "APPROVED" : "REJECTED");
        a.setHandledBy("merchant/" + merchantId);
        a.setHandledAt(LocalDateTime.now());
        a.setNote(note == null || note.isBlank() ? (approve ? "商家同意退款" : "商家驳回申请") : note.trim());
        a.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(a);
    }

    /** 商家/平台复核申诉：APPEAL → APPROVED 或 REJECTED（终局） */
    public void reviewAppeal(long merchantId, long id, boolean approve, String note) {
        AfterSale a = getForMerchant(merchantId, id);
        if (!"APPEAL".equals(a.getStatus())) {
            throw new IllegalArgumentException("仅申诉中的工单可复核（当前：" + a.getStatus() + "）");
        }
        a.setStatus(approve ? "APPROVED" : "REJECTED");
        a.setHandledBy("merchant/" + merchantId);
        a.setHandledAt(LocalDateTime.now());
        a.setNote(note == null || note.isBlank() ? (approve ? "申诉成立，同意退款" : "申诉不成立，维持驳回") : note.trim());
        a.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(a);
    }

    /** 标记退款完成：APPROVED → REFUNDING → REFUNDED（模拟资金到账） */
    public void refund(long merchantId, long id) {
        AfterSale a = getForMerchant(merchantId, id);
        if (!"APPROVED".equals(a.getStatus())) {
            throw new IllegalArgumentException("仅已通过工单可退款（当前：" + a.getStatus() + "）");
        }
        a.setStatus("REFUNDED");
        a.setHandledBy("merchant/" + merchantId);
        a.setHandledAt(LocalDateTime.now());
        a.setNote("退款已到账（模拟）");
        a.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(a);
    }

    /** 买家申诉：REJECTED → APPEAL */
    public void appeal(long userId, long id, String reason) {
        AfterSale a = mapper.selectById(id);
        if (a == null || a.getUserId() == null || a.getUserId() != userId) {
            throw new IllegalArgumentException("工单不存在或无权操作");
        }
        if (!"REJECTED".equals(a.getStatus())) {
            throw new IllegalArgumentException("仅已驳回工单可发起申诉（当前：" + a.getStatus() + "）");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("申诉理由不能为空");
        }
        a.setStatus("APPEAL");
        a.setAppealReason(reason.trim());
        a.setAppealAt(LocalDateTime.now());
        a.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(a);
    }

    private AfterSale getForMerchant(long merchantId, long id) {
        AfterSale a = mapper.selectById(id);
        if (a == null || a.getMerchantId() == null || a.getMerchantId() != merchantId) {
            throw new IllegalArgumentException("工单不存在或无权操作");
        }
        return a;
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
