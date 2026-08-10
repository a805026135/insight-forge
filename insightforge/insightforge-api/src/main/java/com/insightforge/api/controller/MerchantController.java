package com.insightforge.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.insightforge.api.auth.AuthContextHolder;
import com.insightforge.api.dto.MerchantProductRequest;
import com.insightforge.api.dto.MerchantProductView;
import com.insightforge.common.result.ApiResult;
import com.insightforge.persistence.entity.Merchant;
import com.insightforge.persistence.entity.Product;
import com.insightforge.persistence.mapper.MerchantMapper;
import com.insightforge.persistence.mapper.ProductMapper;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
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

    public MerchantController(MerchantMapper merchantMapper, ProductMapper productMapper) {
        this.merchantMapper = merchantMapper;
        this.productMapper = productMapper;
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

    private boolean midEquals(Long a, long b) {
        return a != null && a == b;
    }

    private MerchantProductView toView(Product p, long mid, String merchantName) {
        return new MerchantProductView(
                p.getId(), p.getName(), p.getSku(), p.getCostPrice(), p.getMarketPrice(),
                null, p.getImageUrl(), p.getCategory(), p.getDescription(), p.getStatus(),
                mid, merchantName, null, null);
    }
}
