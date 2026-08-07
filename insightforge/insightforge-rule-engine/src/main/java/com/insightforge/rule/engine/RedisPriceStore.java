package com.insightforge.rule.engine;

import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 价表（Redis 实现，@Profile("integration")）。
 * REPRICE 写回真实 Redis 价表键 price:a:{aid}:s:{skid}（与秒杀同一份库存底座）。
 */
@Component
@Profile("integration")
public class RedisPriceStore implements PriceStore {

    private final StringRedisTemplate redis;

    public RedisPriceStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void setPrice(long activityId, long skuId, double price) {
        redis.opsForValue().set(RuleEngineRedisKeys.price(activityId, skuId), String.valueOf(price));
    }

    @Override
    public Double getPrice(long activityId, long skuId) {
        String v = redis.opsForValue().get(RuleEngineRedisKeys.price(activityId, skuId));
        return v == null ? null : Double.parseDouble(v);
    }
}
