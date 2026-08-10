package com.insightforge.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.insightforge.api.auth.AuthContextHolder;
import com.insightforge.api.dto.CartItemView;
import com.insightforge.api.dto.CheckoutRequest;
import com.insightforge.api.dto.OrderDetailView;
import com.insightforge.api.dto.OrderItemView;
import com.insightforge.api.service.CheckoutService;
import com.insightforge.common.result.ApiResult;
import com.insightforge.persistence.entity.ActivitySku;
import com.insightforge.persistence.entity.CartItem;
import com.insightforge.persistence.entity.Merchant;
import com.insightforge.persistence.entity.OrderItem;
import com.insightforge.persistence.entity.Orders;
import com.insightforge.persistence.entity.Product;
import com.insightforge.persistence.entity.UserAddress;
import com.insightforge.persistence.mapper.ActivitySkuMapper;
import com.insightforge.persistence.mapper.CartItemMapper;
import com.insightforge.persistence.mapper.MerchantMapper;
import com.insightforge.persistence.mapper.OrderItemMapper;
import com.insightforge.persistence.mapper.OrdersMapper;
import com.insightforge.persistence.mapper.ProductMapper;
import com.insightforge.persistence.mapper.UserAddressMapper;
import com.insightforge.seckill.core.service.OrderStateService;
import com.insightforge.seckill.core.state.OrderStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 用户中心 API（Phase 17）：收货地址 CRUD、购物车、购物车结算、订单中心。
 * 所有数据以 JWT 中的 userId 为准（强制隔离他人数据）。
 */
@RestController
@RequestMapping("/api/user")
public class UserCenterController {

    private final UserAddressMapper addressMapper;
    private final CartItemMapper cartItemMapper;
    private final ActivitySkuMapper activitySkuMapper;
    private final ProductMapper productMapper;
    private final MerchantMapper merchantMapper;
    private final OrdersMapper ordersMapper;
    private final OrderItemMapper orderItemMapper;
    private final CheckoutService checkoutService;
    private final OrderStateService orderStateService;

    public UserCenterController(UserAddressMapper addressMapper, CartItemMapper cartItemMapper,
                                ActivitySkuMapper activitySkuMapper, ProductMapper productMapper,
                                MerchantMapper merchantMapper, OrdersMapper ordersMapper,
                                OrderItemMapper orderItemMapper, CheckoutService checkoutService,
                                OrderStateService orderStateService) {
        this.addressMapper = addressMapper;
        this.cartItemMapper = cartItemMapper;
        this.activitySkuMapper = activitySkuMapper;
        this.productMapper = productMapper;
        this.merchantMapper = merchantMapper;
        this.ordersMapper = ordersMapper;
        this.orderItemMapper = orderItemMapper;
        this.checkoutService = checkoutService;
        this.orderStateService = orderStateService;
    }

    // ---------- 收货地址 ----------

    @GetMapping("/addresses")
    public ApiResult<List<UserAddress>> addresses() {
        long uid = AuthContextHolder.requireUserId();
        return ApiResult.ok(addressMapper.selectList(
                new QueryWrapper<UserAddress>().eq("user_id", uid).orderByDesc("is_default").orderByDesc("id")));
    }

    @PostMapping("/addresses")
    @Transactional
    public ApiResult<UserAddress> createAddress(@RequestBody UserAddress req) {
        long uid = AuthContextHolder.requireUserId();
        if (req.getReceiver() == null || req.getReceiver().isBlank()
                || req.getPhone() == null || req.getPhone().isBlank()
                || req.getDetail() == null || req.getDetail().isBlank()) {
            return ApiResult.fail(400, "收件人/电话/详细地址不能为空");
        }
        req.setId(null);
        req.setUserId(uid);
        req.setCreatedAt(LocalDateTime.now());
        if (Boolean.TRUE.equals(req.getIsDefault())) {
            clearDefault(uid);
        } else if (addressMapper.selectCount(new QueryWrapper<UserAddress>().eq("user_id", uid)) == 0) {
            req.setIsDefault(true);
        }
        addressMapper.insert(req);
        return ApiResult.ok(req);
    }

    @PutMapping("/addresses/{id}")
    @Transactional
    public ApiResult<UserAddress> updateAddress(@PathVariable Long id, @RequestBody UserAddress req) {
        long uid = AuthContextHolder.requireUserId();
        UserAddress addr = addressMapper.selectById(id);
        if (addr == null || addr.getUserId() == null || addr.getUserId() != uid) {
            return ApiResult.fail(403, "无权操作该地址");
        }
        if (req.getReceiver() != null) addr.setReceiver(req.getReceiver());
        if (req.getPhone() != null) addr.setPhone(req.getPhone());
        if (req.getProvince() != null) addr.setProvince(req.getProvince());
        if (req.getCity() != null) addr.setCity(req.getCity());
        if (req.getDistrict() != null) addr.setDistrict(req.getDistrict());
        if (req.getDetail() != null) addr.setDetail(req.getDetail());
        if (Boolean.TRUE.equals(req.getIsDefault())) {
            clearDefault(uid);
            addr.setIsDefault(true);
        }
        addressMapper.updateById(addr);
        return ApiResult.ok(addr);
    }

    @DeleteMapping("/addresses/{id}")
    public ApiResult<Void> deleteAddress(@PathVariable Long id) {
        long uid = AuthContextHolder.requireUserId();
        UserAddress addr = addressMapper.selectById(id);
        if (addr == null || addr.getUserId() == null || addr.getUserId() != uid) {
            return ApiResult.fail(403, "无权操作该地址");
        }
        addressMapper.deleteById(id);
        return ApiResult.ok(null);
    }

    @PostMapping("/addresses/{id}/default")
    @Transactional
    public ApiResult<UserAddress> setDefault(@PathVariable Long id) {
        long uid = AuthContextHolder.requireUserId();
        UserAddress addr = addressMapper.selectById(id);
        if (addr == null || addr.getUserId() == null || addr.getUserId() != uid) {
            return ApiResult.fail(403, "无权操作该地址");
        }
        clearDefault(uid);
        addr.setIsDefault(true);
        addressMapper.updateById(addr);
        return ApiResult.ok(addr);
    }

    private void clearDefault(long uid) {
        List<UserAddress> all = addressMapper.selectList(new QueryWrapper<UserAddress>().eq("user_id", uid));
        for (UserAddress a : all) {
            if (Boolean.TRUE.equals(a.getIsDefault())) {
                a.setIsDefault(false);
                addressMapper.updateById(a);
            }
        }
    }

    // ---------- 购物车 ----------

    @GetMapping("/cart")
    public ApiResult<List<CartItemView>> cart() {
        long uid = AuthContextHolder.requireUserId();
        List<CartItem> items = cartItemMapper.selectList(
                new QueryWrapper<CartItem>().eq("user_id", uid).orderByDesc("id"));
        if (items.isEmpty()) {
            return ApiResult.ok(List.of());
        }
        List<Long> productIds = items.stream().map(CartItem::getProductId)
                .filter(java.util.Objects::nonNull).distinct().collect(Collectors.toList());
        Map<Long, Product> productMap = new HashMap<>();
        if (!productIds.isEmpty()) {
            productMapper.selectBatchIds(productIds)
                    .forEach(p -> productMap.put(p.getId(), p));
        }
        List<Long> skuIds = items.stream().map(CartItem::getActivitySkuId)
                .filter(java.util.Objects::nonNull).distinct().collect(Collectors.toList());
        Map<Long, ActivitySku> skuMap = new HashMap<>();
        if (!skuIds.isEmpty()) {
            activitySkuMapper.selectBatchIds(skuIds)
                    .forEach(s -> skuMap.put(s.getId(), s));
        }
        List<Long> merchantIds = productMap.values().stream().map(Product::getMerchantId)
                .filter(java.util.Objects::nonNull).distinct().collect(Collectors.toList());
        Map<Long, Merchant> merchantMap = new HashMap<>();
        if (!merchantIds.isEmpty()) {
            merchantMapper.selectBatchIds(merchantIds)
                    .forEach(m -> merchantMap.put(m.getId(), m));
        }

        return ApiResult.ok(items.stream().map(ci -> {
            ActivitySku sku = skuMap.get(ci.getActivitySkuId());
            Product p = productMap.get(ci.getProductId());
            Merchant m = p != null && p.getMerchantId() != null ? merchantMap.get(p.getMerchantId()) : null;
            return new CartItemView(
                    ci.getId(), ci.getActivitySkuId(), ci.getProductId(),
                    p != null ? p.getName() : "unknown",
                    p != null ? p.getImageUrl() : "",
                    m != null ? m.getId() : null,
                    m != null ? m.getName() : "unknown merchant",
                    sku != null ? sku.getSeckillPrice() : (p != null ? p.getMarketPrice() : null),
                    p != null ? p.getMarketPrice() : null,
                    ci.getQty(),
                    sku != null ? sku.getStock() : (p != null ? p.getStock() : null),
                    sku != null ? sku.getSold() : null,
                    p != null ? p.getStatus() : "OFF_SHELF");
        }).collect(Collectors.toList()));
    }
    @PostMapping("/cart")
    public ApiResult<Void> addCart(@RequestBody Map<String, Object> body) {
        long uid = AuthContextHolder.requireUserId();
        Object productIdObj = body.get("productId") != null ? body.get("productId") : body.get("activitySkuId");
        if (productIdObj == null) {
            return ApiResult.fail(400, "缺少商品标识");
        }
        long productId = ((Number) productIdObj).longValue();
        int qty = body.get("qty") == null ? 1 : ((Number) body.get("qty")).intValue();
        if (qty <= 0 || qty > 99) {
            return ApiResult.fail(400, "数量需在 1~99 之间");
        }
        Product product = productMapper.selectById(productId);
        if (product == null || !"ON_SALE".equals(product.getStatus())) {
            return ApiResult.fail(404, "?????");
        }
        ActivitySku sku = activitySkuMapper.selectOne(
                new QueryWrapper<ActivitySku>().eq("sku_id", productId).orderByDesc("id").last("LIMIT 1"));
        Long activitySkuId = sku != null ? sku.getId() : null;
        CartItem existing = cartItemMapper.selectOne(
                new QueryWrapper<CartItem>().eq("user_id", uid).eq("product_id", productId));
        if (existing != null) {
            existing.setQty(Math.min(existing.getQty() + qty, 99));
            if (existing.getActivitySkuId() == null) {
                existing.setActivitySkuId(activitySkuId);
            }
            cartItemMapper.updateById(existing);
        } else {
            CartItem ci = new CartItem();
            ci.setUserId(uid);
            ci.setProductId(productId);
            ci.setActivitySkuId(activitySkuId);
            ci.setQty(qty);
            ci.setCreatedAt(LocalDateTime.now());
            cartItemMapper.insert(ci);
        }
        return ApiResult.ok(null);
    }

    @PutMapping("/cart/{id}")
    public ApiResult<Void> updateCartQty(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        long uid = AuthContextHolder.requireUserId();
        CartItem ci = cartItemMapper.selectById(id);
        if (ci == null || ci.getUserId() == null || ci.getUserId() != uid) {
            return ApiResult.fail(403, "无权操作该购物车条目");
        }
        int qty = ((Number) body.get("qty")).intValue();
        if (qty <= 0 || qty > 99) {
            return ApiResult.fail(400, "数量需在 1~99 之间");
        }
        ci.setQty(qty);
        cartItemMapper.updateById(ci);
        return ApiResult.ok(null);
    }

    @DeleteMapping("/cart/{id}")
    public ApiResult<Void> removeCart(@PathVariable Long id) {
        long uid = AuthContextHolder.requireUserId();
        CartItem ci = cartItemMapper.selectById(id);
        if (ci == null || ci.getUserId() == null || ci.getUserId() != uid) {
            return ApiResult.fail(403, "无权操作该购物车条目");
        }
        cartItemMapper.deleteById(id);
        return ApiResult.ok(null);
    }

    // ---------- 下单 / 订单中心 ----------

    @PostMapping("/checkout")
    public ApiResult<String> checkout(@RequestBody CheckoutRequest req) {
        return checkoutService.checkout(req.addressId(), req.cartItemIds());
    }

    @GetMapping("/orders")
    public ApiResult<List<OrderDetailView>> orders() {
        long uid = AuthContextHolder.requireUserId();
        List<Orders> orders = ordersMapper.selectList(
                new QueryWrapper<Orders>().eq("user_id", uid).orderByDesc("created_at"));
        if (orders.isEmpty()) {
            return ApiResult.ok(List.of());
        }
        List<String> orderIds = orders.stream().map(Orders::getOrderId).collect(Collectors.toList());
        List<OrderItem> items = orderItemMapper.selectList(
                new QueryWrapper<OrderItem>().in("order_id", orderIds));
        Map<String, List<OrderItem>> byOrder = items.stream()
                .collect(Collectors.groupingBy(OrderItem::getOrderId));
        return ApiResult.ok(orders.stream().map(o -> new OrderDetailView(
                o.getOrderId(), o.getStatus(), o.getTotalAmount(), o.getReceiver(), o.getPhone(),
                o.getAddressSnapshot(), o.getCreatedAt(), o.getShippedAt(),
                byOrder.getOrDefault(o.getOrderId(), List.of()).stream()
                        .map(oi -> new OrderItemView(
                                oi.getActivitySkuId(), oi.getProductId(), oi.getProductName(),
                                oi.getProductImage(), oi.getMerchantId(), oi.getMerchantName(),
                                oi.getQty(), oi.getPrice()))
                        .collect(Collectors.toList())))
                .collect(Collectors.toList()));
    }

    @PostMapping("/orders/{orderId}/pay")
    public ApiResult<Void> pay(@PathVariable String orderId) {
        return orderStateService.markPaid(orderId);
    }

    @PostMapping("/orders/{orderId}/cancel")
    public ApiResult<Void> cancel(@PathVariable String orderId) {
        return orderStateService.cancel(orderId);
    }

    @PostMapping("/orders/{orderId}/confirm")
    @Transactional
    public ApiResult<Void> confirm(@PathVariable String orderId) {
        Orders order = ordersMapper.selectOne(new QueryWrapper<Orders>().eq("order_id", orderId));
        if (order == null) {
            return ApiResult.fail(404, "订单不存在");
        }
        if (order.getUserId() == null || order.getUserId() != AuthContextHolder.requireUserId()) {
            return ApiResult.fail(403, "无权操作该订单");
        }
        OrderStatus.from(order.getStatus()).transitionTo(OrderStatus.DONE);
        order.setStatus(OrderStatus.DONE.name());
        ordersMapper.updateById(order);
        return ApiResult.ok(null);
    }
}
