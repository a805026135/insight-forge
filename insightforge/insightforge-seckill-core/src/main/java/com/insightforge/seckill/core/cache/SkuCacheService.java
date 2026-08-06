package com.insightforge.seckill.core.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightforge.persistence.entity.ActivitySku;
import com.insightforge.persistence.mapper.ActivitySkuMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * SKU 多级缓存封装（Phase 5）。
 *
 * 把"按 id 查 ActivitySku"这件高频读，套上 MultiLevelCache：
 *   - L1 Caffeine（本地）+ L2 Redis（非 local 才有）。
 *   - 商品/库存这类相对静态的元数据被缓存；DB 写（扣库存 / 预热）后调用 evict 失效。
 *
 * 设计取舍（诚实说明）：被缓存的 stock 字段是"带 TTL 的快照"，不是实时值。
 * 在真实秒杀里，热路径的权威库存始终在 Redis（由 Lua 扣减维护），缓存只是给
 * "商品详情/库存快照"这类读多写少场景加速；我们对账用的是 DB 的 totalStock/sold，
 * 与缓存无关，因此缓存过期绝不会破坏"绝不超卖"的硬约束（详见 tutorials/phase5.md）。
 */
@Service
public class SkuCacheService {

    private final MultiLevelCache<Long, ActivitySku> cache;

    /**
     * 构造时把"回源函数 + Redis 提供者 + JSON 工具"喂给通用多级缓存组件。
     * 注意 ObjectProvider&lt;StringRedisTemplate&gt;：local profile 下 Redis 自动配置被排除，
     * provider 拿到的就是 null，MultiLevelCache 会自动退化为"只用 L1"——零代码切换。
     */
    public SkuCacheService(ActivitySkuMapper mapper,
                           ObjectProvider<StringRedisTemplate> redisProvider,
                           ObjectMapper objectMapper) {
        this.cache = new MultiLevelCache<>(
                mapper::selectById,            // 回源函数：L1/L2 都没命中时去 DB 拿 ActivitySku
                redisProvider,
                objectMapper,
                "sku:",                        // L2 前缀：实际 key 形如 sku:1
                Duration.ofSeconds(60),        // 基础 TTL 60s
                Duration.ofSeconds(10),        // 抖动 0~10s，避免集体过期（防雪崩）
                ActivitySku.class);
    }

    /** 读穿取 SKU；不存在返回 null（已被缓存成占位符防穿透）。 */
    public ActivitySku getSku(Long skuId) {
        return cache.get(skuId);
    }

    /** 数据变更后失效缓存（扣库存 / 预热 / 后台改价都会调），保证缓存与 DB 最终一致。 */
    public void evict(Long skuId) {
        cache.evict(skuId);
    }
}
