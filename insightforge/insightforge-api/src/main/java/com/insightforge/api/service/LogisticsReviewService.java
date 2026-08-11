package com.insightforge.api.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.insightforge.api.auth.AuthContextHolder;
import com.insightforge.common.result.ApiResult;
import com.insightforge.persistence.entity.Merchant;
import com.insightforge.persistence.entity.Orders;
import com.insightforge.persistence.entity.Product;
import com.insightforge.persistence.entity.ProductReview;
import com.insightforge.persistence.entity.Shipment;
import com.insightforge.persistence.entity.ShipmentEvent;
import com.insightforge.persistence.mapper.MerchantMapper;
import com.insightforge.persistence.mapper.OrdersMapper;
import com.insightforge.persistence.mapper.ProductMapper;
import com.insightforge.persistence.mapper.ProductReviewMapper;
import com.insightforge.persistence.mapper.ShipmentEventMapper;
import com.insightforge.persistence.mapper.ShipmentMapper;
import com.insightforge.seckill.core.state.OrderStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 履约与评价闭环（Phase 19o）：
 *  - 商家发货 → 生成运单 + 首条轨迹（揽收）
 *  - 买家确认收货 → SHIPPED → DONE + 追加签收轨迹
 *  - 买家评价 → 回写商品/店铺评分聚合，均分实时可算
 */
@Service
public class LogisticsReviewService {

    private final ShipmentMapper shipmentMapper;
    private final ShipmentEventMapper eventMapper;
    private final OrdersMapper ordersMapper;
    private final ProductMapper productMapper;
    private final MerchantMapper merchantMapper;
    private final ProductReviewMapper reviewMapper;
    private final com.insightforge.persistence.mapper.OrderItemMapper itemMapper;
    private final com.insightforge.persistence.mapper.UserMapper userMapper;

    
    /** 商家发货：PAY → SHIPPED，生成运单与揽收轨迹 */
    @Transactional
    public ApiResult<Void> ship(long merchantId, String orderId) {
        Orders order = ordersMapper.selectOne(new QueryWrapper<Orders>().eq("order_id", orderId));
        if (order == null) {
            return ApiResult.fail(404, "订单不存在");
        }
        // 校验该订单确实包含本店商品
        List<com.insightforge.persistence.entity.OrderItem> items = itemList(orderId);
        if (items.stream().noneMatch(it -> merchantId == (it.getMerchantId() == null ? -1 : it.getMerchantId()))) {
            return ApiResult.fail(403, "无权操作该订单");
        }
                if (!"PAY".equals(order.getStatus()) && !"SHIPPED".equals(order.getStatus())) {
            return ApiResult.fail(400, "仅待发货订单可发货（当前：" + order.getStatus() + "）");
        }
        Shipment existing = shipmentMapper.selectOne(
                new QueryWrapper<Shipment>().eq("order_id", orderId));
        if (existing != null) {
            return ApiResult.fail(400, "该订单已发货（运单：" + existing.getTrackingNo() + "）");
        }
        if (!"SHIPPED".equals(order.getStatus())) {
            order.setStatus(OrderStatus.SHIPPED.name());
            order.setShippedAt(LocalDateTime.now());
            ordersMapper.updateById(order);
        }
        Shipment shipment = new Shipment();
        shipment.setOrderId(orderId);
        shipment.setTrackingNo("SF" + System.currentTimeMillis() + UUID.randomUUID().toString().substring(0, 4).toUpperCase());
        shipment.setCarrier("顺丰速运");
        shipment.setShippedAt(LocalDateTime.now());
        shipmentMapper.insert(shipment);
        appendEvent(shipment.getId(), "PICKED_UP", "商家已发货，顺丰已揽收");
        return ApiResult.ok(null);
    }

    /** 买家确认收货：SHIPPED → DONE + 签收轨迹 */
    @Transactional
    public ApiResult<Void> confirmReceipt(long userId, String orderId) {
        Orders order = ordersMapper.selectOne(new QueryWrapper<Orders>().eq("order_id", orderId));
        if (order == null || order.getUserId() == null || order.getUserId() != userId) {
            return ApiResult.fail(403, "订单不存在或无权操作");
        }
        if (!"SHIPPED".equals(order.getStatus())) {
            return ApiResult.fail(400, "仅待收货订单可确认收货（当前：" + order.getStatus() + "）");
        }
        OrderStatus.from(order.getStatus()).transitionTo(OrderStatus.DONE);
        order.setStatus(OrderStatus.DONE.name());
        ordersMapper.updateById(order);

        Shipment shipment = shipmentMapper.selectOne(
                new QueryWrapper<Shipment>().eq("order_id", orderId));
        if (shipment != null) {
            shipment.setDeliveredAt(LocalDateTime.now());
            shipmentMapper.updateById(shipment);
            appendEvent(shipment.getId(), "DELIVERED", "买家已确认收货，交易完成");
        }
        return ApiResult.ok(null);
    }

    /** 提交评价：DONE 订单可评，同一订单同一商品仅一次；回写评分聚合 */
    @Transactional
    public ApiResult<ProductReview> createReview(long userId, String orderId, Long productId,
                                                 int rating, String content) {
        if (rating < 1 || rating > 5) {
            return ApiResult.fail(400, "评分需在 1~5 星之间");
        }
        Orders order = ordersMapper.selectOne(new QueryWrapper<Orders>().eq("order_id", orderId));
        if (order == null || order.getUserId() == null || order.getUserId() != userId) {
            return ApiResult.fail(403, "订单不存在或无权操作");
        }
        if (!"DONE".equals(order.getStatus())) {
            return ApiResult.fail(400, "订单完成后才能评价");
        }
        Product p = productMapper.selectById(productId);
        if (p == null) {
            return ApiResult.fail(404, "商品不存在");
        }
        long existing = reviewMapper.selectCount(new QueryWrapper<ProductReview>()
                .eq("order_id", orderId).eq("product_id", productId));
        if (existing > 0) {
            return ApiResult.fail(400, "该订单商品已评价过");
        }
        ProductReview review = new ProductReview();
        review.setOrderId(orderId);
        review.setUserId(userId);
        review.setProductId(productId);
        review.setMerchantId(p.getMerchantId());
        review.setRating(rating);
        review.setContent(content == null ? "" : content.trim());
        review.setCreatedAt(LocalDateTime.now());
        reviewMapper.insert(review);

        p.setRatingCount((p.getRatingCount() == null ? 0 : p.getRatingCount()) + 1);
        p.setRatingTotal((p.getRatingTotal() == null ? 0 : p.getRatingTotal()) + rating);
        productMapper.updateById(p);

        if (p.getMerchantId() != null) {
            Merchant m = merchantMapper.selectById(p.getMerchantId());
            if (m != null) {
                m.setRatingCount((m.getRatingCount() == null ? 0 : m.getRatingCount()) + 1);
                m.setRatingTotal((m.getRatingTotal() == null ? 0 : m.getRatingTotal()) + rating);
                m.setRating(java.math.BigDecimal.valueOf(
                        Math.round(m.getRatingTotal() * 100.0 / m.getRatingCount()) / 100.0));
                merchantMapper.updateById(m);
            }
        }
        return ApiResult.ok(review);
    }

    /** 订单物流信息（轨迹倒序） */
    public ApiResult<Map<String, Object>> logistics(long userId, String orderId) {
        Orders order = ordersMapper.selectOne(new QueryWrapper<Orders>().eq("order_id", orderId));
        if (order == null || order.getUserId() == null || order.getUserId() != userId) {
            return ApiResult.fail(403, "订单不存在或无权查看");
        }
        Shipment shipment = shipmentMapper.selectOne(
                new QueryWrapper<Shipment>().eq("order_id", orderId));
        if (shipment == null) {
            return ApiResult.fail(404, "该订单尚未发货");
        }
        List<ShipmentEvent> events = eventMapper.selectList(
                new QueryWrapper<ShipmentEvent>().eq("shipment_id", shipment.getId())
                        .orderByDesc("id"));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderId", orderId);
        data.put("trackingNo", shipment.getTrackingNo());
        data.put("carrier", shipment.getCarrier());
        data.put("status", order.getStatus());
        data.put("events", events);
        return ApiResult.ok(data);
    }

    /** 商品评价列表（含买家昵称） */
    public ApiResult<Map<String, Object>> productReviews(long productId) {
        List<ProductReview> reviews = reviewMapper.selectList(
                new QueryWrapper<ProductReview>().eq("product_id", productId)
                        .orderByDesc("id").last("LIMIT 20"));
        Product p = productMapper.selectById(productId);
        // ?????????????
        List<Map<String, Object>> views = new java.util.ArrayList<>();
        for (ProductReview r : reviews) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", r.getId());
            row.put("orderId", r.getOrderId());
            row.put("userId", r.getUserId());
            row.put("rating", r.getRating());
            row.put("content", r.getContent());
            row.put("createdAt", r.getCreatedAt());
            String name = null;
            if (r.getUserId() != null) {
                var u = userMapper.selectById(r.getUserId());
                name = u == null ? null : u.getDisplayName();
            }
            row.put("userDisplayName", name == null || name.isBlank() ? "????" : name);
            views.add(row);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ratingCount", p == null ? 0 : (p.getRatingCount() == null ? 0 : p.getRatingCount()));
        data.put("ratingTotal", p == null ? 0 : (p.getRatingTotal() == null ? 0 : p.getRatingTotal()));
        data.put("reviews", views);
        return ApiResult.ok(data);
    }

    private void appendEvent(Long shipmentId, String status, String description) {
        ShipmentEvent ev = new ShipmentEvent();
        ev.setShipmentId(shipmentId);
        ev.setEventTime(LocalDateTime.now());
        ev.setStatus(status);
        ev.setDescription(description);
        eventMapper.insert(ev);
    }

    private List<com.insightforge.persistence.entity.OrderItem> itemList(String orderId) {
        return itemMapper.selectList(new QueryWrapper<com.insightforge.persistence.entity.OrderItem>()
                .eq("order_id", orderId));
    }

    public LogisticsReviewService(ShipmentMapper shipmentMapper,
                                  ShipmentEventMapper eventMapper,
                                  OrdersMapper ordersMapper,
                                  ProductMapper productMapper,
                                  MerchantMapper merchantMapper,
                                  ProductReviewMapper reviewMapper,
                                  com.insightforge.persistence.mapper.OrderItemMapper itemMapper,
                                  com.insightforge.persistence.mapper.UserMapper userMapper) {
        this.shipmentMapper = shipmentMapper;
        this.eventMapper = eventMapper;
        this.ordersMapper = ordersMapper;
        this.productMapper = productMapper;
        this.merchantMapper = merchantMapper;
        this.reviewMapper = reviewMapper;
        this.itemMapper = itemMapper;
        this.userMapper = userMapper;
    }
}
