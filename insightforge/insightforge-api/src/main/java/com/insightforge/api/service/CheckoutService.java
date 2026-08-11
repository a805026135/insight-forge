package com.insightforge.api.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.insightforge.api.auth.AuthContextHolder;
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
import com.insightforge.seckill.core.state.OrderStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 购物车结算服务（Phase 18）：支持两类商品下单
 *  - 秒杀商品（cart.activity_sku_id 非空）：秒杀价 + 扣 activity_sku 活动库存
 *  - 普通商品（仅 product_id）：市场价 + 扣 product 独立库存
 * 均为 DB 原子条件更新（stock >= qty），与秒杀热路径共用防超卖语义。
 */
@Service
public class CheckoutService {

    private final CartItemMapper cartItemMapper;
    private final ActivitySkuMapper activitySkuMapper;
    private final ProductMapper productMapper;
    private final MerchantMapper merchantMapper;
    private final UserAddressMapper userAddressMapper;
    private final OrdersMapper ordersMapper;
    private final OrderItemMapper orderItemMapper;

    public CheckoutService(CartItemMapper cartItemMapper, ActivitySkuMapper activitySkuMapper,
                           ProductMapper productMapper, MerchantMapper merchantMapper,
                           UserAddressMapper userAddressMapper, OrdersMapper ordersMapper,
                           OrderItemMapper orderItemMapper) {
        this.cartItemMapper = cartItemMapper;
        this.activitySkuMapper = activitySkuMapper;
        this.productMapper = productMapper;
        this.merchantMapper = merchantMapper;
        this.userAddressMapper = userAddressMapper;
        this.ordersMapper = ordersMapper;
        this.orderItemMapper = orderItemMapper;
    }

    @Transactional
    public ApiResult<String> checkout(Long addressId, List<Long> cartItemIds) {
        long uid = AuthContextHolder.requireUserId();
        if (cartItemIds == null || cartItemIds.isEmpty()) {
            return ApiResult.fail(400, "请选择要结算的商品");
        }
        UserAddress addr = userAddressMapper.selectById(addressId);
        if (addr == null || addr.getUserId() == null || addr.getUserId() != uid) {
            return ApiResult.fail(400, "收货地址无效");
        }

        List<CartItem> cartItems = cartItemMapper.selectList(
                new QueryWrapper<CartItem>().in("id", cartItemIds).eq("user_id", uid));
        if (cartItems.isEmpty()) {
            return ApiResult.fail(400, "购物车条目不存在");
        }

        String orderId = "OF-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 6);
        Orders order = new Orders();
        order.setOrderId(orderId);
        order.setUserId(uid);
        order.setStatus(OrderStatus.CREATE_ORDER.name());
        order.setCreatedAt(LocalDateTime.now());
        order.setReceiver(addr.getReceiver());
        order.setPhone(addr.getPhone());
        order.setAddressSnapshot(String.format("%s %s %s %s",
                addr.getProvince(), addr.getCity(), addr.getDistrict(), addr.getDetail()));
        ordersMapper.insert(order);

        BigDecimal total = BigDecimal.ZERO;
        Map<Long, Product> productMap = loadProducts(cartItems);
        Map<Long, Merchant> merchantMap = loadMerchants(cartItems);
        Map<Long, ActivitySku> skuMap = loadSkus(cartItems);

        for (CartItem ci : cartItems) {
            // ?????????????????????????????????
            Product first = productMap.get(ci.getProductId());
            if (order.getSkuId() == null && first != null) {
                order.setSkuId(first.getId());
                order.setQty(ci.getQty());
                ActivitySku firstSku = ci.getActivitySkuId() != null ? skuMap.get(ci.getActivitySkuId()) : null;
                order.setActivityId(firstSku == null ? null : firstSku.getActivityId());
            }
            Product p = productMap.get(ci.getProductId());
            if (p == null) {
                throw new IllegalStateException("商品不存在: product=" + ci.getProductId());
            }
            ActivitySku sku = ci.getActivitySkuId() != null ? skuMap.get(ci.getActivitySkuId()) : null;
            BigDecimal unitPrice;
            if (sku != null) {
                // 秒杀路径：扣活动库存
                int affected = activitySkuMapper.decrementStock(sku.getId(), ci.getQty());
                if (affected == 0) {
                    throw new IllegalStateException("秒杀库存不足: " + p.getName());
                }
                unitPrice = sku.getSeckillPrice();
            } else {
                // 普通路径：扣商品独立库存
                int affected = productMapper.decrementStock(p.getId(), ci.getQty());
                if (affected == 0) {
                    throw new IllegalStateException("库存不足: " + p.getName());
                }
                unitPrice = p.getMarketPrice();
            }
            Merchant m = p != null && p.getMerchantId() != null ? merchantMap.get(p.getMerchantId()) : null;
            BigDecimal line = unitPrice.multiply(BigDecimal.valueOf(ci.getQty()));
            total = total.add(line);

            OrderItem oi = new OrderItem();
            oi.setOrderId(orderId);
            oi.setActivitySkuId(sku != null ? sku.getId() : null);
            oi.setProductId(p.getId());
            oi.setProductName(p.getName());
            oi.setProductImage(p.getImageUrl());
            oi.setMerchantId(m != null ? m.getId() : null);
            oi.setMerchantName(m != null ? m.getName() : "未知商家");
            oi.setQty(ci.getQty());
            oi.setPrice(unitPrice);
            oi.setCreatedAt(LocalDateTime.now());
            orderItemMapper.insert(oi);
            cartItemMapper.deleteById(ci.getId());
        }

        order.setTotalAmount(total);
        ordersMapper.updateById(order);
        return ApiResult.ok(orderId);
    }

    private Map<Long, ActivitySku> loadSkus(List<CartItem> items) {
        List<Long> ids = items.stream().map(CartItem::getActivitySkuId)
                .filter(java.util.Objects::nonNull).distinct().collect(Collectors.toList());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return activitySkuMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(ActivitySku::getId, s -> s));
    }

    private Map<Long, Product> loadProducts(List<CartItem> items) {
        List<Long> productIds = items.stream().map(CartItem::getProductId)
                .filter(java.util.Objects::nonNull).distinct().collect(Collectors.toList());
        if (productIds.isEmpty()) {
            return Map.of();
        }
        return productMapper.selectBatchIds(productIds).stream()
                .collect(Collectors.toMap(Product::getId, p -> p));
    }

    private Map<Long, Merchant> loadMerchants(List<CartItem> items) {
        Map<Long, Product> products = loadProducts(items);
        List<Long> merchantIds = products.values().stream().map(Product::getMerchantId)
                .filter(java.util.Objects::nonNull).distinct().collect(Collectors.toList());
        if (merchantIds.isEmpty()) {
            return Map.of();
        }
        return merchantMapper.selectBatchIds(merchantIds).stream()
                .collect(Collectors.toMap(Merchant::getId, m -> m));
    }
}
