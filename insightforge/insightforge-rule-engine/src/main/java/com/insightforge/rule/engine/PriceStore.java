package com.insightforge.rule.engine;

/**
 * 价表抽象（REPRICE 指令写回的价格）。
 *
 * 默认（@Profile("!integration")）：{@code InMemoryPriceStore} 内存态；
 * 集成（@Profile("integration")）：{@code RedisPriceStore} 写回真实 Redis 价表键
 * price:a:{aid}:s:{skid}（与秒杀同一份库存底座，前端大屏可直接读）。
 */
public interface PriceStore {

    /** 写价格（activityId + skuId 定位，与库存键同源）。 */
    void setPrice(long activityId, long skuId, double price);

    /** 读价格，未设置返回 null。 */
    Double getPrice(long activityId, long skuId);
}
