package com.insightforge.seckill.core.redis;

/**
 * Redis Key 命名规范（与 spec §13.1 对齐）。
 * 集中管理 key 拼装，避免散落各处拼错；后续 Phase 的 Lua 扣减 / 分段 / pending 均复用此处。
 */
public final class RedisKeys {

    /** 订单幂等键前缀（补偿巡检按此扫描） */
    public static final String ORDER_IDEMPOTENT_PREFIX = "order:idemp:";

    /** 可恢复库存预留的超时索引：score=补偿截止时间，member=orderId。 */
    public static final String ORDER_RESERVATION_PENDING = "order:reservation:pending";

    private RedisKeys() {
    }

    /** 主库存：stock:a:{aid}:s:{sid} */
    public static String stock(long activityId, long skuId) {
        return "stock:a:" + activityId + ":s:" + skuId;
    }

    /** 分段库存：stock:a:{aid}:s:{sid}:seg:{n}（调拨只建新段 seg_{N+1}） */
    public static String stockSegment(long activityId, long skuId, int seg) {
        return stock(activityId, skuId) + ":seg:" + seg;
    }

    /** 两阶段预留：pending_stock:a:{aid}:s:{sid} */
    public static String pendingStock(long activityId, long skuId) {
        return "pending_stock:a:" + activityId + ":s:" + skuId;
    }

    /** 总库存（预热时写入，与剩余库存同一份底座；规则引擎消费者据此算库存水位比例）。 */
    public static String totalStock(long activityId, long skuId) {
        return "total_stock:a:" + activityId + ":s:" + skuId;
    }

    /** 实时价（REPRICE 写回，秒杀下单/展示读取；与 rule-engine RuleEngineRedisKeys.price 逐字符一致） */
    public static String price(long activityId, long skuId) {
        return "price:a:" + activityId + ":s:" + skuId;
    }

    /** 细粒度锁：lock:a:{aid}:s:{sid}（用户 10ms / 规则引擎 0ms，见 spec §3.1） */
    public static String lock(long activityId, long skuId) {
        return "lock:a:" + activityId + ":s:" + skuId;
    }

    /** 订单幂等：order:idemp:{hash}（SETNX 去重，exactly-once） */
    public static String orderIdempotent(String hash) {
        return ORDER_IDEMPOTENT_PREFIX + hash;
    }

    /** 当前生效 DSL：guardrail:dsl:{aid}（Redis 热更新） */
    public static String guardrailDsl(long activityId) {
        return "guardrail:dsl:" + activityId;
    }

    /** 滑动窗口限流：rate:limit:{bizKey}（bizKey 通常是 "u:{userId}:a:{activityId}"） */
    public static String rateLimit(String bizKey) {
        return "rate:limit:" + bizKey;
    }
}
