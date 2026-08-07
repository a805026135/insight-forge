package com.insightforge.rule.engine;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 价表（内存态实现，默认 @Profile("!integration")）。生产/集成环境由 {@link RedisPriceStore} 替换。
 */
@Component
@Profile("!integration")
public class InMemoryPriceStore implements PriceStore {

    private final ConcurrentHashMap<String, Double> table = new ConcurrentHashMap<>();

    private static String key(long activityId, long skuId) {
        return activityId + ":" + skuId;
    }

    @Override
    public void setPrice(long activityId, long skuId, double price) {
        table.put(key(activityId, skuId), price);
    }

    @Override
    public Double getPrice(long activityId, long skuId) {
        return table.get(key(activityId, skuId));
    }
}
