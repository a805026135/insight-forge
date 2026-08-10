package com.insightforge.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.insightforge.api.dto.ProductDetailView;
import com.insightforge.common.result.ApiResult;
import com.insightforge.persistence.entity.ActivitySku;
import com.insightforge.persistence.entity.Merchant;
import com.insightforge.persistence.entity.Product;
import com.insightforge.persistence.mapper.ActivitySkuMapper;
import com.insightforge.persistence.mapper.MerchantMapper;
import com.insightforge.persistence.mapper.ProductMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 用户端商品目录 API（Phase 17）：分类/搜索/详情，商品卡片展示商家名称。
 */
@RestController
@RequestMapping("/api/catalog")
public class CatalogController {

    private final ProductMapper productMapper;
    private final MerchantMapper merchantMapper;
    private final ActivitySkuMapper activitySkuMapper;

    public CatalogController(ProductMapper productMapper, MerchantMapper merchantMapper,
                             ActivitySkuMapper activitySkuMapper) {
        this.productMapper = productMapper;
        this.merchantMapper = merchantMapper;
        this.activitySkuMapper = activitySkuMapper;
    }

    /** GET /api/catalog/products?category=&keyword=&merchantId=&limit= 商品列表（仅上架） */
    @GetMapping("/products")
    public ApiResult<List<ProductDetailView>> products(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long merchantId,
            @RequestParam(defaultValue = "50") int limit) {
        QueryWrapper<Product> qw = new QueryWrapper<Product>().eq("status", "ON_SALE");
        if (category != null && !category.isBlank()) {
            qw.eq("category", category.trim());
        }
        if (merchantId != null) {
            qw.eq("merchant_id", merchantId);
        }
        List<Product> products = productMapper.selectList(qw.orderByDesc("id").last("LIMIT " + Math.min(limit, 100)));
        if (keyword != null && !keyword.isBlank()) {
            String k = keyword.trim().toLowerCase();
            products = products.stream()
                    .filter(p -> (p.getName() != null && p.getName().toLowerCase().contains(k))
                            || (p.getSku() != null && p.getSku().toLowerCase().contains(k))
                            || (p.getDescription() != null && p.getDescription().toLowerCase().contains(k)))
                    .collect(Collectors.toList());
        }
        Map<Long, Merchant> merchantMap = loadMerchants(products);
        Map<Long, ActivitySku> skuMap = loadSeckillPrice(products);
        return ApiResult.ok(products.stream()
                .map(p -> toView(p, merchantMap.get(p.getMerchantId()), skuMap.get(p.getId())))
                .collect(Collectors.toList()));
    }

    /** GET /api/catalog/products/{id} 商品详情（含商家信息） */
    @GetMapping("/products/{id}")
    public ApiResult<ProductDetailView> detail(@PathVariable Long id) {
        Product p = productMapper.selectById(id);
        if (p == null) {
            return ApiResult.fail(404, "商品不存在");
        }
        Merchant m = p.getMerchantId() != null ? merchantMapper.selectById(p.getMerchantId()) : null;
        ActivitySku sku = activitySkuMapper.selectOne(
                new QueryWrapper<ActivitySku>().eq("sku_id", p.getId()).orderByDesc("id").last("LIMIT 1"));
        return ApiResult.ok(toView(p, m, sku));
    }

    /** GET /api/catalog/categories 分类列表 */
    @GetMapping("/categories")
    public ApiResult<List<String>> categories() {
        List<Product> products = productMapper.selectList(
                new QueryWrapper<Product>().eq("status", "ON_SALE").select("DISTINCT category"));
        return ApiResult.ok(products.stream().map(Product::getCategory)
                .filter(c -> c != null && !c.isBlank())
                .distinct().collect(Collectors.toList()));
    }

    /** GET /api/catalog/merchants 商家列表 */
    @GetMapping("/merchants")
    public ApiResult<List<Merchant>> merchants() {
        return ApiResult.ok(merchantMapper.selectList(null));
    }

    private Map<Long, Merchant> loadMerchants(List<Product> products) {
        Set<Long> ids = products.stream().map(Product::getMerchantId).filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return merchantMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Merchant::getId, m -> m));
    }

    private Map<Long, ActivitySku> loadSeckillPrice(List<Product> products) {
        Set<Long> ids = products.stream().map(Product::getId).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<ActivitySku> skus = activitySkuMapper.selectList(
                new QueryWrapper<ActivitySku>().in("sku_id", ids));
        Map<Long, ActivitySku> latest = new HashMap<>();
        for (ActivitySku s : skus) {
            latest.putIfAbsent(s.getSkuId(), s);
        }
        return latest;
    }

    private ProductDetailView toView(Product p, Merchant m, ActivitySku sku) {
        return new ProductDetailView(
                p.getId(),
                sku != null ? sku.getId() : null,
                p.getName(), p.getSku(), p.getMarketPrice(),
                sku != null ? sku.getSeckillPrice() : null,
                p.getImageUrl(), p.getCategory(), p.getDescription(), p.getStatus(),
                p.getMerchantId(),
                m != null ? m.getName() : "未知商家",
                m != null ? m.getRating() : null,
                m != null ? m.getSalesCount() : null,
                m != null ? m.getVerified() : null,
                sku != null ? sku.getStock() : null,
                sku != null ? sku.getSold() : null);
    }
}
