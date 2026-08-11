package com.insightforge.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.insightforge.api.auth.AuthContextHolder;
import com.insightforge.agent.core.kb.KnowledgeBaseService;
import com.insightforge.agent.core.kb.KnowledgeBaseSeeder;
import com.insightforge.agent.core.kb.MerchantKbRefresher;
import com.insightforge.api.dto.MerchantProductRequest;
import com.insightforge.api.dto.MerchantProductView;
import com.insightforge.api.dto.AssistantReply;
import com.insightforge.api.service.MerchantAssistantService;
import com.insightforge.api.service.MerchantActionService;
import com.insightforge.api.service.AfterSaleService;
import com.insightforge.api.service.LogisticsReviewService;
import com.insightforge.api.service.MerchantKbCustomService;
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
import com.insightforge.persistence.entity.AgentKbCustomDoc;
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
import org.springframework.web.bind.annotation.RequestParam;
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
    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeBaseSeeder knowledgeBaseSeeder;
    private final MerchantKbRefresher merchantKbRefresher;
    private final MerchantKbCustomService kbCustomService;
    private final LogisticsReviewService logisticsReviewService;
    private final AfterSaleService afterSaleService;

    public MerchantController(MerchantMapper merchantMapper, ProductMapper productMapper,
                              ActivitySkuMapper activitySkuMapper, OrdersMapper ordersMapper,
                              OrderItemMapper orderItemMapper, MerchantAssistantService assistantService,
                              MerchantActionService actionService,
                              KnowledgeBaseService knowledgeBaseService,
                              KnowledgeBaseSeeder knowledgeBaseSeeder,
                              MerchantKbRefresher merchantKbRefresher,
                              MerchantKbCustomService kbCustomService,
                              LogisticsReviewService logisticsReviewService,
                              AfterSaleService afterSaleService) {
        this.merchantMapper = merchantMapper;
        this.productMapper = productMapper;
        this.activitySkuMapper = activitySkuMapper;
        this.ordersMapper = ordersMapper;
        this.orderItemMapper = orderItemMapper;
        this.assistantService = assistantService;
        this.actionService = actionService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.knowledgeBaseSeeder = knowledgeBaseSeeder;
        this.merchantKbRefresher = merchantKbRefresher;
        this.kbCustomService = kbCustomService;
        this.logisticsReviewService = logisticsReviewService;
        this.afterSaleService = afterSaleService;
    }

    /** POST /api/merchant/kb/reindex 手动刷新当前商家的知识库（商品 + 历史决策） */
    @PostMapping("/kb/reindex")
    public ApiResult<Map<String, Object>> kbReindex() {
        long mid = AuthContextHolder.requireMerchantId();
        Map<String, Object> data = new HashMap<>(knowledgeBaseSeeder.reindexMerchant(mid));
        data.put("merchantId", mid);
        data.put("provider", knowledgeBaseService.providerName());
        data.put("dimensions", knowledgeBaseService.providerDimensions());
        data.put("totalChunks", knowledgeBaseService.chunkCount());
        return ApiResult.ok(data);
    }

    /** GET /api/merchant/kb/stats 知识库状态（供前端展示/排查） */
    @GetMapping("/kb/stats")
    public ApiResult<Map<String, Object>> kbStats() {
        Map<String, Object> data = new HashMap<>();
        data.put("enabled", knowledgeBaseService.properties().isEnabled());
        data.put("provider", knowledgeBaseService.providerName());
        data.put("dimensions", knowledgeBaseService.providerDimensions());
        data.put("vectorStore", knowledgeBaseService.vectorStoreName());
        data.put("documents", knowledgeBaseService.documentCount());
        data.put("chunks", knowledgeBaseService.chunkCount());
        data.put("merchantId", AuthContextHolder.requireMerchantId());
        return ApiResult.ok(data);
    }

    /** GET /api/merchant/kb/docs 当前商家可见的知识库文档列表（平台级 + 本商家） */
    @GetMapping("/kb/docs")
    public ApiResult<Map<String, Object>> kbDocs() {
        long mid = AuthContextHolder.requireMerchantId();
        Map<String, Object> data = new HashMap<>();
        data.put("provider", knowledgeBaseService.providerName());
        data.put("dimensions", knowledgeBaseService.providerDimensions());
        data.put("vectorStore", knowledgeBaseService.vectorStoreName());
        data.put("documents", knowledgeBaseService.listVisibleDocuments(mid));
        return ApiResult.ok(data);
    }

    /** POST /api/merchant/kb/custom 上传自定义知识（JSON: title + content，txt/md 文本） */
    @PostMapping("/kb/custom")
    public ApiResult<Map<String, Object>> kbCustomUpload(@RequestBody Map<String, String> body) {
        long mid = AuthContextHolder.requireMerchantId();
        try {
            return ApiResult.ok(new HashMap<>(kbCustomService.upload(mid, body.get("title"), body.get("content"))));
        } catch (IllegalArgumentException e) {
            return ApiResult.fail(400, e.getMessage());
        }
    }

    /** GET /api/merchant/kb/custom 我的自定义知识列表 */
    @GetMapping("/kb/custom")
    public ApiResult<List<AgentKbCustomDoc>> kbCustomList() {
        return ApiResult.ok(kbCustomService.list(AuthContextHolder.requireMerchantId()));
    }

    /** GET /api/merchant/kb/custom/detail?docKey=... 查看自定义知识全文 */
    @GetMapping("/kb/custom/detail")
    public ApiResult<AgentKbCustomDoc> kbCustomDetail(@RequestParam String docKey) {
        try {
            return ApiResult.ok(kbCustomService.get(AuthContextHolder.requireMerchantId(), docKey));
        } catch (IllegalArgumentException e) {
            return ApiResult.fail(400, e.getMessage());
        }
    }

    /** PUT /api/merchant/kb/custom 编辑自定义知识（同 docKey 覆盖重入库） */
    @PutMapping("/kb/custom")
    public ApiResult<Map<String, Object>> kbCustomUpdate(@RequestBody Map<String, String> body) {
        long mid = AuthContextHolder.requireMerchantId();
        try {
            return ApiResult.ok(new HashMap<>(kbCustomService.update(
                    mid, body.get("docKey"), body.get("title"), body.get("content"))));
        } catch (IllegalArgumentException e) {
            return ApiResult.fail(400, e.getMessage());
        }
    }

    /** DELETE /api/merchant/kb/custom?docKey=... 删除自定义知识（同时清向量库与切块） */
    @DeleteMapping("/kb/custom")
    public ApiResult<Void> kbCustomDelete(@RequestParam String docKey) {
        long mid = AuthContextHolder.requireMerchantId();
        try {
            kbCustomService.delete(mid, docKey);
            return ApiResult.ok(null);
        } catch (IllegalArgumentException e) {
            return ApiResult.fail(400, e.getMessage());
        }
    }

    /** POST /api/merchant/store-info 更新店铺名/客服电话（公开知识，买家客服可检索） */
    @PostMapping("/store-info")
    public ApiResult<Map<String, Object>> updateStoreInfo(@RequestBody Map<String, String> body) {
        long mid = AuthContextHolder.requireMerchantId();
        String name = body.get("name");
        if (name != null && !name.isBlank() && name.trim().length() > 64) {
            return ApiResult.fail(400, "店铺名不能超过 64 字符");
        }
        String phone = body.get("servicePhone");
        if (phone == null || phone.isBlank() || phone.trim().length() > 32) {
            return ApiResult.fail(400, "客服电话不能为空且不超过 32 字符");
        }
        Merchant m = merchantMapper.selectById(mid);
        if (m == null) {
            return ApiResult.fail(404, "商家不存在");
        }
        if (name != null && !name.isBlank()) {
            m.setName(name.trim());
        }
        m.setServicePhone(phone.trim());
        merchantMapper.updateById(m);
        String text = String.format("店铺公开信息：%s；客服电话：%s；店铺简介：%s；评分：%s；认证：%s",
                m.getName() == null ? "" : m.getName(), phone.trim(),
                m.getDescription() == null ? "" : m.getDescription(),
                m.getRating(), m.getVerified());
        int chunks = knowledgeBaseService.ingestText(
                "merchant/" + mid + "/store-info", "商家公开信息", "STORE_INFO", mid, text);
        Map<String, Object> data = new HashMap<>();
        data.put("name", m.getName());
        data.put("servicePhone", phone.trim());
        data.put("chunks", chunks);
        return ApiResult.ok(data);
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
        merchantKbRefresher.schedule(mid);
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
        merchantKbRefresher.schedule(mid);
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
        merchantKbRefresher.schedule(mid);
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
        merchantKbRefresher.schedule(mid);
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
        merchantKbRefresher.schedule(mid);
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
        /** GET /api/merchant/after-sales?status= ????????????? */
    @GetMapping("/after-sales")
    public ApiResult<List<com.insightforge.persistence.entity.AfterSale>> afterSales(
            @org.springframework.web.bind.annotation.RequestParam(required = false) String status) {
        long mid = AuthContextHolder.requireMerchantId();
        return ApiResult.ok(afterSaleService.merchantList(mid, status));
    }

    /** POST /api/merchant/after-sales/{id}/review?approve=true ?????? */
    @PostMapping("/after-sales/{id}/review")
    public ApiResult<Void> afterSaleReview(@PathVariable Long id,
            @org.springframework.web.bind.annotation.RequestParam boolean approve,
            @RequestBody(required = false) Map<String, String> body) {
        long mid = AuthContextHolder.requireMerchantId();
        try {
            afterSaleService.review(mid, id, approve, body == null ? null : body.get("note"));
            return ApiResult.ok(null);
        } catch (IllegalArgumentException e) {
            return ApiResult.fail(400, e.getMessage());
        }
    }

    /** POST /api/merchant/after-sales/{id}/appeal-review?approve=true ???? */
    @PostMapping("/after-sales/{id}/appeal-review")
    public ApiResult<Void> afterSaleAppealReview(@PathVariable Long id,
            @org.springframework.web.bind.annotation.RequestParam boolean approve,
            @RequestBody(required = false) Map<String, String> body) {
        long mid = AuthContextHolder.requireMerchantId();
        try {
            afterSaleService.reviewAppeal(mid, id, approve, body == null ? null : body.get("note"));
            return ApiResult.ok(null);
        } catch (IllegalArgumentException e) {
            return ApiResult.fail(400, e.getMessage());
        }
    }

    /** POST /api/merchant/after-sales/{id}/refund ?????? */
    @PostMapping("/after-sales/{id}/refund")
    public ApiResult<Void> afterSaleRefund(@PathVariable Long id) {
        long mid = AuthContextHolder.requireMerchantId();
        try {
            afterSaleService.refund(mid, id);
            return ApiResult.ok(null);
        } catch (IllegalArgumentException e) {
            return ApiResult.fail(400, e.getMessage());
        }
    }

@PostMapping("/orders/{orderId}/ship")
    public ApiResult<Void> ship(@PathVariable String orderId) {
        long mid = AuthContextHolder.requireMerchantId();
        return logisticsReviewService.ship(mid, orderId);
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
