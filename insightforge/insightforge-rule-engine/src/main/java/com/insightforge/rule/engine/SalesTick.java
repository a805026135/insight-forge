package com.insightforge.rule.engine;

/**
 * 销售心跳事实（Sales Tick）——插入 Drools 的「事实（fact）」。
 *
 * 大白话：每隔一小段时间，系统把「这个 SKU 现在卖得怎样」拍成一张快照喂给规则引擎：
 * 库存还剩多少比例、卖得多快、跟成本差多少、转化率高不高、大概多久售罄。
 * 规则引擎就靠着这些数字，决定要不要调价 / 调拨 / 告警。
 * 注意：所有字段都是只读快照，Drools 只读取、绝不修改 tick 本身。
 */
public class SalesTick {

    private final long activityId;
    private final long skuId;
    private final double stockRatio;       // 库存水位比例 = 剩余 / 总（低于阈值→该补货/调拨）
    private final double salesVelocity;    // 销售速度（件/分钟，骤降→可能要告警）
    private final double priceGap;         // 当前价与成本/竞品的 Gap（过大→该调价）
    private final double conversionRate;   // 转化率（过低→该换选品/文案）
    private final int remainingStock;      // 剩余库存（件）
    private final int totalStock;          // 总库存（件）
    private final int ttlSeconds;          // 预计售罄剩余秒（预测性调拨用：<5min 预调拨）
    private final long ts;                 // 快照时间戳（ms）

    public SalesTick(long activityId, long skuId, double stockRatio, double salesVelocity,
                     double priceGap, double conversionRate, int remainingStock, int totalStock,
                     int ttlSeconds, long ts) {
        this.activityId = activityId;
        this.skuId = skuId;
        this.stockRatio = stockRatio;
        this.salesVelocity = salesVelocity;
        this.priceGap = priceGap;
        this.conversionRate = conversionRate;
        this.remainingStock = remainingStock;
        this.totalStock = totalStock;
        this.ttlSeconds = ttlSeconds;
        this.ts = ts;
    }

    // getters（Drools 的 MVEL 通过 getter 访问字段；字段本身是 final，tick 是只读事实）
    public long getActivityId() {
        return activityId;
    }

    public long getSkuId() {
        return skuId;
    }

    public double getStockRatio() {
        return stockRatio;
    }

    public double getSalesVelocity() {
        return salesVelocity;
    }

    public double getPriceGap() {
        return priceGap;
    }

    public double getConversionRate() {
        return conversionRate;
    }

    public int getRemainingStock() {
        return remainingStock;
    }

    public int getTotalStock() {
        return totalStock;
    }

    public int getTtlSeconds() {
        return ttlSeconds;
    }

    public long getTs() {
        return ts;
    }
}
