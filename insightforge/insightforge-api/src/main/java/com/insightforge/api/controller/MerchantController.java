package com.insightforge.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.insightforge.api.auth.AuthContextHolder;
import com.insightforge.api.dto.MerchantProductRequest;
import com.insightforge.api.dto.MerchantProductView;
import com.insightforge.api.dto.AssistantReply;
import com.insightforge.api.service.MerchantAssistantService;
import com.insightforge.api.service.MerchantActionService;
import com.insightforge.common.result.ApiResult;
import com.insightforge.persistence.entity.ActivitySku;
import com.insightforge.persistence.entity.OrderItem;
import com.insightforge.persistence.entity.Merchant;
import com.insightforge.persistence.entity.Orders;
import com.insightforge.persistence.entity.OrderItem;
import com.insightforge.api.dto.OrderDetailView;
import com.insightforge.api.dto.OrderItemView;
import com.insightforge.seckill.core.state.OrderStatus;
import com.insightforge.persistence.entity.Product;
import com.insightforge.persistence.mapper.ActivitySkuMapper;
import com.insightforge.persistence.mapper.MerchantMapper;
import com.insightforge.persistence.mapper.OrderItemMapper;
import com.insightforge.persistence.mapper.OrdersMapper;
import com.insightforge.persistence.mapper.ProductMapper;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 商家工作台 API（Phase 17）：商家身份强制隔离——所有查询/写入都以 JWT 中的 merchantId 为准，
 * 商家只能看到/操作自己的店铺数据（商品/订单/活动）。
 */
@RestController
@RequestMapping("/api/merchant")
public class MerchantController {

    private final MerchantMapper merchantMapper;
    private final ProductMapper productMapper;
    private final ActivitySkuMapper activitySkuMapper;
    private final OrdersMapper ordersMapper;
    private final OrderItemMapper orderItemMapper;
    private final MerchantAssistantService assistantService;
    private final MerchantActionService actionService;

    public MerchantController(MerchantMapper merchantMapper, ProductMapper productMapper,
                              ActivitySkuMapper activitySkuMapper, OrdersMapper ordersMapper,
                              OrderItemMapper orderItemMapper, MerchantAssistantService assistantService,
                              MerchantActionService actionService) {
        this.merchantMapper = merchantMapper;
        this.productMapper = productMapper;
        this.activitySkuMapper = activitySkuMapper;
        this.ordersMapper = ordersMapper;
        this.orderItemMapper = orderItemMapper;
        this.assistantService = assistantService;
        this.actionService = actionService;
    }

    /** GET /api/merchant/profile 当前商家店铺信息 */
    @GetMapping("/profile")
    public ApiResult<Merchant> profile() {
        long mid = AuthContextHolder.requireMerchantId();
        Merchant m = merchantMapper.selectById(mid);
        if (m == null) {
            return ApiResult.fail(404, "商家不存在");
        }
        return ApiResult.ok(m);
    }

    /** GET /api/merchant/overview 商家经营概览（商品/库存/订单/Agent 数据，全部商家维度） */
    @GetMapping("/overview")
    public ApiResult<Map<String, Object>> overview() {
        long mid = AuthContextHolder.requireMerchantId();
        List<Product> products = productMapper.selectList(
                new QueryWrapper<Product>().eq("merchant_id", mid));
        List<Long> productIds = products.stream().map(Product::getId).collect(Collectors.toList());
        List<ActivitySku> skus = productIds.isEmpty() ? List.of()
                : activitySkuMapper.selectList(new QueryWrapper<ActivitySku>().in("sku_id", productIds));
        long totalStock = skus.stream().mapToLong(s -> s.getStock() == null ? 0 : s.getStock()).sum();
        long totalSold = skus.stream().mapToLong(s -> s.getSold() == null ? 0 : s.getSold()).sum();
        long totalGmv = orderItemMapper.selectList(
                        new QueryWrapper<OrderItem>().eq("merchant_id", mid)).stream()
                .mapToLong(oi -> oi.getPrice() == null ? 0L : oi.getPrice().longValue() * oi.getQty())
                .sum();
        Map<String, Object> data = new HashMap<>();
        data.put("merchantId", mid);
        data.put("productCount", products.size());
        data.put("skuCount", skus.size());
        data.put("totalStock", totalStock);
        data.put("totalSold", totalSold);
        data.put("totalGmv", totalGmv);
        data.put("sellThroughRate", totalStock + totalSold == 0 ? 0
                : Math.round(totalSold * 10000.0 / (totalStock + totalSold)) / 100.0);
        data.put("generatedAt", java.time.LocalDateTime.now().toString());
        return ApiResult.ok(data);
    }

    /** POST /api/merchant/assistant 商家 AI 运营助手 */
    @PostMapping("/assistant")
    public ApiResult<AssistantReply> assistant(@RequestBody Map<String, String> body) {
        long mid = AuthContextHolder.requireMerchantId();
        String question = body.get("question");
        if (question == null || question.isBlank()) {
            return ApiResult.fail(400, "问题不能为空");
        }
        return ApiResult.ok(assistantService.ask(mid, question.trim()));
    }

    /** POST /api/merchant/assistant/stream 流式问答（SSE，打字机效果） */
    @PostMapping("/assistant/stream")
    public SseEmitter assistantStream(@RequestBody Map<String, String> body) {
        long mid = AuthContextHolder.requireMerchantId();
        String question = body.get("question");
        if (question == null || question.isBlank()) {
            SseEmitter bad = new SseEmitter();
            try {
                bad.send(SseEmitter.event().name("error").data("问题不能为空"));
            } catch (Exception ignored) {
                // ignore
            }
            bad.complete();
            return bad;
        }
        SseEmitter emitter = new SseEmitter(120_000L);
        Thread t = new Thread(() -> {
            try {
                assistantService.askStream(mid, question.trim(),
                        chunk -> {
                            try {
                                emitter.send(SseEmitter.event().name("chunk").data(chunk));
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        });
                emitter.send(SseEmitter.event().name("done").data(""));
            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
                } catch (Exception ignored) {
                    // ignore
                }
            } finally {
                emitter.complete();
            }
        });
        t.setDaemon(true);
        t.start();
        return emitter;
    }

    /** POST /api/merchant/actions/execute 商家批准后执行 Agent 动作 */
    @PostMapping("/actions/execute")
    public ApiResult<String> executeAction(@RequestBody Map<String, Object> body) {
        long mid = AuthContextHolder.requireMerchantId();
        String type = (String) body.get("type");
        Long productId = body.get("productId") != null ? ((Number) body.get("productId")).longValue() : null;
        Integer amount = body.get("amount") != null ? ((Number) body.get("amount")).intValue() : null;
        BigDecimal price = body.get("price") != null ? new BigDecimal(body.get("price").toString()) : null;
        return actionService.execute(mid, type, productId, amount, price);
    }

    /** GET /api/merchant/products 当前商家的商品列表（只读自己的） */
    @GetMapping("/products")
    public ApiResult<List<MerchantProductView>> products() {
        long mid = AuthContextHolder.requireMerchantId();
        List<Product> products = productMapper.selectList(
                new QueryWrapper<Product>().eq("merchant_id", mid).orderByDesc("id"));
        Merchant m = merchantMapper.selectById(mid);
        String merchantName = m != null ? m.getName() : "";
        return ApiResult.ok(products.stream().map(p -> toView(p, mid, merchantName))
                .collect(Collectors.toList()));
    }

    /** POST /api/merchant/products 创建商品（归属当前商家） */
    @PostMapping("/products")
    public ApiResult<MerchantProductView> create(@RequestBody MerchantProductRequest req) {
        long mid = AuthContextHolder.requireMerchantId();
        if (req.name() == null || req.name().isBlank()) {
            return ApiResult.fail(400, "商品名称不能为空");
        }
        Product p = new Product();
        p.setName(req.name().trim());
        p.setSku(req.sku() == null || req.sku().isBlank() ? "SKU-" + System.currentTimeMillis() % 100000 : req.sku().trim());
        p.setCostPrice(req.costPrice());
        p.setMarketPrice(req.marketPrice());
        p.setImageUrl(req.imageUrl());
        p.setCategory(req.category());
        p.setDescription(req.description());
        p.setMerchantId(mid);
        p.setStatus("ON_SALE");
        productMapper.insert(p);
        Merchant m = merchantMapper.selectById(mid);
        return ApiResult.ok(toView(p, mid, m != null ? m.getName() : ""));
    }

    /** PUT /api/merchant/products/{id} 更新商品（校验归属） */
    @PutMapping("/products/{id}")
    public ApiResult<MerchantProductView> update(@PathVariable Long id, @RequestBody MerchantProductRequest req) {
        long mid = AuthContextHolder.requireMerchantId();
        Product p = productMapper.selectById(id);
        if (p == null || !midEquals(p.getMerchantId(), mid)) {
            return ApiResult.fail(403, "无权操作该商品");
        }
        if (req.name() != null && !req.name().isBlank()) {
            p.setName(req.name().trim());
        }
        if (req.sku() != null && !req.sku().isBlank()) {
            p.setSku(req.sku().trim());
        }
        if (req.costPrice() != null) p.setCostPrice(req.costPrice());
        if (req.marketPrice() != null) p.setMarketPrice(req.marketPrice());
        if (req.imageUrl() != null) p.setImageUrl(req.imageUrl());
        if (req.category() != null) p.setCategory(req.category());
        if (req.description() != null) p.setDescription(req.description());
        productMapper.updateById(p);
        Merchant m = merchantMapper.selectById(mid);
        return ApiResult.ok(toView(p, mid, m != null ? m.getName() : ""));
    }

    /** POST /api/merchant/products/{id}/toggle 上下架切换 */
    @PostMapping("/products/{id}/toggle")
    public ApiResult<MerchantProductView> toggle(@PathVariable Long id) {
        long mid = AuthContextHolder.requireMerchantId();
        Product p = productMapper.selectById(id);
        if (p == null || !midEquals(p.getMerchantId(), mid)) {
            return ApiResult.fail(403, "无权操作该商品");
        }
        p.setStatus("ON_SALE".equals(p.getStatus()) ? "OFF_SHELF" : "ON_SALE");
        productMapper.updateById(p);
        Merchant m = merchantMapper.selectById(mid);
        return ApiResult.ok(toView(p, mid, m != null ? m.getName() : ""));
    }

    /** DELETE /api/merchant/products/{id} 删除商品（仅未参与活动或演示场景直接删） */
    @DeleteMapping("/products/{id}")
    public ApiResult<Void> delete(@PathVariable Long id) {
        long mid = AuthContextHolder.requireMerchantId();
        Product p = productMapper.selectById(id);
        if (p == null || !midEquals(p.getMerchantId(), mid)) {
            return ApiResult.fail(403, "无权操作该商品");
        }
        productMapper.deleteById(id);
        return ApiResult.ok(null);
    }

    /** POST /api/merchant/inventory/{id}/adjust ?????????????? */
    @PostMapping("/inventory/{id}/adjust")
    public ApiResult<MerchantProductView> adjustStock(@PathVariable Long id,
                                                     @RequestBody Map<String, Object> body) {
        long mid = AuthContextHolder.requireMerchantId();
        Product p = productMapper.selectById(id);
        if (p == null || !midEquals(p.getMerchantId(), mid)) {
            return ApiResult.fail(403, "???????");
        }
        int delta = body.get("delta") == null ? 0 : ((Number) body.get("delta")).intValue();
        int cur = p.getStock() == null ? 0 : p.getStock();
        if (cur + delta < 0) {
            return ApiResult.fail(400, "??????");
        }
        p.setStock(cur + delta);
        productMapper.updateById(p);
        Merchant m = merchantMapper.selectById(mid);
        return ApiResult.ok(toView(p, mid, m != null ? m.getName() : ""));
    }

    /** GET /api/merchant/orders ?????????????????? */
    @GetMapping("/orders")
    public ApiResult<List<OrderDetailView>> orders() {
        long mid = AuthContextHolder.requireMerchantId();
        List<OrderItem> myItems = orderItemMapper.selectList(
                new QueryWrapper<OrderItem>().eq("merchant_id", mid));
        if (myItems.isEmpty()) {
            return ApiResult.ok(List.of());
        }
        List<String> orderIds = myItems.stream().map(OrderItem::getOrderId).distinct().collect(Collectors.toList());
        List<Orders> orders = ordersMapper.selectList(
                new QueryWrapper<Orders>().in("order_id", orderIds).orderByDesc("created_at"));
        Map<String, List<OrderItem>> byOrder = myItems.stream()
                .collect(Collectors.groupingBy(OrderItem::getOrderId));
        return ApiResult.ok(orders.stream().map(o -> {
            List<OrderItem> items = byOrder.getOrDefault(o.getOrderId(), List.of());
            return new OrderDetailView(
                    o.getOrderId(), o.getStatus(), o.getTotalAmount(), o.getReceiver(), o.getPhone(),
                    o.getAddressSnapshot(), o.getCreatedAt(), o.getShippedAt(),
                    items.stream().map(oi -> new OrderItemView(
                            oi.getActivitySkuId(), oi.getProductId(), oi.getProductName(),
                            oi.getProductImage(), oi.getMerchantId(), oi.getMerchantName(),
                            oi.getQty(), oi.getPrice())).collect(Collectors.toList()));
        }).collect(Collectors.toList()));
    }

    /** POST /api/merchant/orders/{orderId}/ship ?????PAY ? SHIPPED? */
    @PostMapping("/orders/{orderId}/ship")
    public ApiResult<Void> ship(@PathVariable String orderId) {
        long mid = AuthContextHolder.requireMerchantId();
        long cnt = orderItemMapper.selectCount(
                new QueryWrapper<OrderItem>().eq("order_id", orderId).eq("merchant_id", mid));
        if (cnt == 0) {
            return ApiResult.fail(403, "???????");
        }
        Orders order = ordersMapper.selectOne(new QueryWrapper<Orders>().eq("order_id", orderId));
        if (order == null) {
            return ApiResult.fail(404, "?????");
        }
        OrderStatus.from(order.getStatus()).transitionTo(OrderStatus.SHIPPED);
        order.setStatus(OrderStatus.SHIPPED.name());
        order.setShippedAt(java.time.LocalDateTime.now());
        ordersMapper.updateById(order);
        return ApiResult.ok(null);
    }

    private boolean midEquals(Long a, long b) {
        return a != null && a == b;
    }

    private MerchantProductView toView(Product p, long mid, String merchantName) {
        return new MerchantProductView(
                p.getId(), p.getName(), p.getSku(), p.getCostPrice(), p.getMarketPrice(),
                null, p.getImageUrl(), p.getCategory(), p.getDescription(), p.getStatus(),
                mid, merchantName, p.getStock(), null);
    }
}
