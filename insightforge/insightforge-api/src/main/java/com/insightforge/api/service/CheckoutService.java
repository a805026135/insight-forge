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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 购物车结算服务（Phase 17）：多商品下单，原子扣减活动库存，写订单头+订单明细+收货快照。
 * 与秒杀热路径（Redis Lua）共用 activity_sku 库存表，但走 DB 原子条件更新兜底路径。
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
        Map<Long, ActivitySku> skuMap = loadSkus(cartItems);
        Map<Long, Product> productMap = loadProducts(cartItems);
        Map<Long, Merchant> merchantMap = loadMerchants(cartItems);

        for (CartItem ci : cartItems) {
            ActivitySku sku = skuMap.get(ci.getActivitySkuId());
            if (sku == null) {
                throw new IllegalStateException("商品已下架: activitySku=" + ci.getActivitySkuId());
            }
            int affected = activitySkuMapper.decrementStock(sku.getId(), ci.getQty());
            if (affected == 0) {
                throw new IllegalStateException("库存不足: " + productMap.get(sku.getSkuId()).getName());
            }
            Product p = productMap.get(sku.getSkuId());
            Merchant m = p != null && p.getMerchantId() != null ? merchantMap.get(p.getMerchantId()) : null;
            BigDecimal line = sku.getSeckillPrice().multiply(BigDecimal.valueOf(ci.getQty()));
            total = total.add(line);

            OrderItem oi = new OrderItem();
            oi.setOrderId(orderId);
            oi.setActivitySkuId(sku.getId());
            oi.setProductId(p != null ? p.getId() : null);
            oi.setProductName(p != null ? p.getName() : "未知商品");
            oi.setProductImage(p != null ? p.getImageUrl() : "");
            oi.setMerchantId(m != null ? m.getId() : null);
            oi.setMerchantName(m != null ? m.getName() : "未知商家");
            oi.setQty(ci.getQty());
            oi.setPrice(sku.getSeckillPrice());
            oi.setCreatedAt(LocalDateTime.now());
            orderItemMapper.insert(oi);
            cartItemMapper.deleteById(ci.getId());
        }

        order.setTotalAmount(total);
        ordersMapper.updateById(order);
        return ApiResult.ok(orderId);
    }

    private Map<Long, ActivitySku> loadSkus(List<CartItem> items) {
        List<Long> ids = items.stream().map(CartItem::getActivitySkuId).distinct().collect(Collectors.toList());
        return activitySkuMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(ActivitySku::getId, s -> s));
    }

    private Map<Long, Product> loadProducts(List<CartItem> items) {
        List<Long> ids = items.stream().map(CartItem::getActivitySkuId).distinct().collect(Collectors.toList());
        List<ActivitySku> skus = activitySkuMapper.selectBatchIds(ids);
        List<Long> productIds = skus.stream().map(ActivitySku::getSkuId).distinct().collect(Collectors.toList());
        if (productIds.isEmpty()) {
            return Map.of();
        }
        return productMapper.selectBatchIds(productIds).stream()
                .collect(Collectors.toMap(Product::getId, p -> p));
    }

    private Map<Long, Merchant> loadMerchants(List<CartItem> items) {
        List<Long> ids = items.stream().map(CartItem::getActivitySkuId).distinct().collect(Collectors.toList());
        List<ActivitySku> skus = activitySkuMapper.selectBatchIds(ids);
        List<Long> productIds = skus.stream().map(ActivitySku::getSkuId).distinct().collect(Collectors.toList());
        if (productIds.isEmpty()) {
            return Map.of();
        }
        List<Product> products = productMapper.selectBatchIds(productIds);
        List<Long> merchantIds = products.stream().map(Product::getMerchantId)
                .filter(java.util.Objects::nonNull).distinct().collect(Collectors.toList());
        if (merchantIds.isEmpty()) {
            return Map.of();
        }
        return merchantMapper.selectBatchIds(merchantIds).stream()
                .collect(Collectors.toMap(Merchant::getId, m -> m));
    }
}
