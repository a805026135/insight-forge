package com.insightforge.api.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.insightforge.api.auth.AuthContextHolder;
import com.insightforge.common.result.ApiResult;
import com.insightforge.persistence.entity.Activity;
import com.insightforge.persistence.entity.ActivitySku;
import com.insightforge.persistence.entity.OrderItem;
import com.insightforge.persistence.entity.Product;
import com.insightforge.persistence.mapper.ActivityMapper;
import com.insightforge.persistence.mapper.ActivitySkuMapper;
import com.insightforge.persistence.mapper.OrderItemMapper;
import com.insightforge.persistence.mapper.ProductMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 商家维度大屏分析（Phase 19f）：全部按当前登录商家隔离——
 * 近 7 日销售趋势 / 类目销售占比 / 库存状态 / 活动效果。
 */
@RestController
@RequestMapping("/api/merchant")
public class MerchantDashboardController {

    private final OrderItemMapper orderItemMapper;
    private final ProductMapper productMapper;
    private final ActivitySkuMapper activitySkuMapper;
    private final ActivityMapper activityMapper;

    public MerchantDashboardController(OrderItemMapper orderItemMapper,
                                       ProductMapper productMapper,
                                       ActivitySkuMapper activitySkuMapper,
                                       ActivityMapper activityMapper) {
        this.orderItemMapper = orderItemMapper;
        this.productMapper = productMapper;
        this.activitySkuMapper = activitySkuMapper;
        this.activityMapper = activityMapper;
    }

    /** GET /api/merchant/dashboard/analytics 商家大屏分析数据（商家隔离） */
    @GetMapping("/dashboard/analytics")
    public ApiResult<Map<String, Object>> analytics() {
        long mid = AuthContextHolder.requireMerchantId();
        LocalDateTime since = LocalDate.now().minusDays(6).atStartOfDay();
        List<OrderItem> items = orderItemMapper.selectList(
                new QueryWrapper<OrderItem>().eq("merchant_id", mid).ge("created_at", since));

        // 近 7 日趋势：销量 / GMV（按日）
        Map<String, long[]> trend = new TreeMap<>();
        for (OrderItem it : items) {
            if (it.getCreatedAt() == null) {
                continue;
            }
            String date = it.getCreatedAt().toLocalDate().toString();
            long[] arr = trend.computeIfAbsent(date, k -> new long[2]);
            long qty = it.getQty() == null ? 0 : it.getQty();
            arr[0] += qty;
            arr[1] += gmv(it, qty);
        }
        List<Map<String, Object>> trendList = new ArrayList<>();
        for (Map.Entry<String, long[]> e : trend.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", e.getKey());
            row.put("qty", e.getValue()[0]);
            row.put("gmv", e.getValue()[1]);
            trendList.add(row);
        }

        // 类目销售占比
        Map<Long, String> cat = productMapper.selectList(
                        new QueryWrapper<Product>().eq("merchant_id", mid)).stream()
                .collect(Collectors.toMap(Product::getId,
                        p -> p.getCategory() == null ? "未分类" : p.getCategory(), (a, b) -> a));
        Map<String, Long> catGmv = new LinkedHashMap<>();
        for (OrderItem it : items) {
            String c = cat.getOrDefault(it.getProductId(), "未分类");
            long qty = it.getQty() == null ? 0 : it.getQty();
            catGmv.merge(c, gmv(it, qty), Long::sum);
        }
        List<Map<String, Object>> categoryList = catGmv.entrySet().stream()
                .map(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", e.getKey());
                    row.put("gmv", e.getValue());
                    return row;
                }).toList();

        // 库存状态
        List<Product> products = productMapper.selectList(
                new QueryWrapper<Product>().eq("merchant_id", mid));
        long onSale = products.stream().filter(p -> "ON_SALE".equals(p.getStatus())).count();
        long lowStock = products.stream()
                .filter(p -> p.getStock() != null && p.getStock() < 100).count();
        Map<String, Object> stock = new LinkedHashMap<>();
        stock.put("onSale", onSale);
        stock.put("offShelf", products.size() - onSale);
        stock.put("lowStock", lowStock);

        // 活动效果
        List<ActivitySku> skus = activitySkuMapper.selectList(
                new QueryWrapper<ActivitySku>().eq("merchant_id", mid));
        List<Map<String, Object>> activityList = new ArrayList<>();
        for (ActivitySku s : skus) {
            Activity a = s.getActivityId() == null ? null : activityMapper.selectById(s.getActivityId());
            Product p = s.getSkuId() == null ? null : productMapper.selectById(s.getSkuId());
            BigDecimal market = p == null ? null : p.getMarketPrice();
            double discount = market != null && market.signum() > 0 && s.getSeckillPrice() != null
                    ? s.getSeckillPrice().doubleValue() / market.doubleValue() : 1.0;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("activity", a == null ? "活动#" + s.getActivityId() : a.getName());
            row.put("product", p == null ? "商品#" + s.getSkuId() : p.getName());
            row.put("seckillPrice", s.getSeckillPrice());
            row.put("marketPrice", market);
            row.put("discount", Math.round(discount * 100) / 100.0);
            row.put("sold", s.getSold() == null ? 0 : s.getSold());
            row.put("stock", s.getStock() == null ? 0 : s.getStock());
            activityList.add(row);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("trend", trendList);
        data.put("category", categoryList);
        data.put("stock", stock);
        data.put("activities", activityList);
        return ApiResult.ok(data);
    }

    private long gmv(OrderItem it, long qty) {
        return (long) ((it.getPrice() == null ? BigDecimal.ZERO : it.getPrice()).doubleValue() * qty);
    }
}
