package com.insightforge.api;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.insightforge.persistence.entity.ActivitySku;
import com.insightforge.persistence.mapper.ActivitySkuMapper;
import com.insightforge.persistence.mapper.OrdersMapper;
import com.insightforge.seckill.core.cache.SkuCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Phase 5 多级缓存验证（local 零依赖，只用 L1 Caffeine；L2 Redis 在非 local 才生效）。
 *
 * 验证三件事：
 * 1) 读穿：第一次 getSku 回源 DB，之后命中缓存（一致性）。
 * 2) 防穿透：查不存在的 SKU 返回 null 且不抛异常（被缓存成占位符）。
 * 3) 失效：DB 写后 evict，下次回源拿到最新值（缓存与 DB 最终一致）。
 */
@SpringBootTest
@ActiveProfiles("local")
class SeckillCacheTest {

    @Autowired
    private SkuCacheService skuCacheService;
    @Autowired
    private ActivitySkuMapper activitySkuMapper;
    @Autowired
    private OrdersMapper ordersMapper;

    /** 每个测试前把 sku=1 复位成 100、清空订单、并清缓存，保证独立可重复。 */
    @BeforeEach
    void reset() {
        ActivitySku sku = activitySkuMapper.selectById(1L);
        sku.setStock(100);
        sku.setSold(0);
        activitySkuMapper.updateById(sku);
        ordersMapper.delete(new QueryWrapper<>());
        skuCacheService.evict(1L);
    }

    @Test
    void getSku_reads_through_and_caches() {
        ActivitySku sku = skuCacheService.getSku(1L);
        // data.sql 种子：activity_sku.id=1 初始库存 100
        assertEquals(100, sku.getStock(), "读穿应命中 DB 种子数据（库存 100）");
        assertEquals(1L, sku.getActivityId());
        // 再读一次：命中 L1 缓存，结果应一致（不抛错、不串值）
        ActivitySku again = skuCacheService.getSku(1L);
        assertEquals(sku.getStock(), again.getStock(), "两次读取应一致（缓存命中）");
    }

    @Test
    void getSku_missing_key_returns_null_without_throwing() {
        // 不存在的 SKU：第一次回源 DB 得到 null，并被缓存成占位符（防穿透）
        ActivitySku first = skuCacheService.getSku(999999L);
        assertNull(first, "不存在的 SKU 应返回 null");
        // 第二次命中"查无此物"占位符，同样返回 null，且不会抛异常 / 不再打 DB
        ActivitySku second = skuCacheService.getSku(999999L);
        assertNull(second, "缓存占位符命中后仍返回 null（防穿透生效）");
    }

    @Test
    void evict_after_write_keeps_cache_consistent() {
        // 模拟"库存被改"：直接改 DB
        ActivitySku sku = activitySkuMapper.selectById(1L);
        sku.setStock(50);
        activitySkuMapper.updateById(sku);

        // 失效后回源，应拿到最新库存 50（证明 evict 让缓存与 DB 重新对齐）
        skuCacheService.evict(1L);
        ActivitySku reloaded = skuCacheService.getSku(1L);
        assertEquals(50, reloaded.getStock(), "失效后回源应拿到最新库存 50");
    }
}
