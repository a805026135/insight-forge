package com.insightforge.seckill.core.redis;

/**
 * Redis Key 命名规范（与 spec §13.1 对齐）。
 * 集中管理 key 拼装，避免散落各处拼错；后续 Phase 的 Lua 扣减 / 分段 / pending 均复用此处。
 */
public final class RedisKeys {

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

    /** 细粒度锁：lock:a:{aid}:s:{sid}（用户 10ms / 规则引擎 0ms，见 spec §3.1） */
    public static String lock(long activityId, long skuId) {
        return "lock:a:" + activityId + ":s:" + skuId;
    }

    /** 订单幂等：order:idemp:{hash}（SETNX 去重，exactly-once） */
    public static String orderIdempotent(String hash) {
        return "order:idemp:" + hash;
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
