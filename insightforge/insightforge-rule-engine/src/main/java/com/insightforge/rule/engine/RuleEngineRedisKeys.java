package com.insightforge.rule.engine;

/**
 * 规则引擎侧 Redis Key 约定（必须与 seckill-core 的 {@code RedisKeys} 字符串格式逐字符一致！）。
 *
 * 这是「Agent 控盘与用户抢购争用同一份库存」的硬契约：规则引擎写回的库存键、锁键，
 * 必须和秒杀热路径用 {@code RedisKeys} 拼出的 key 完全相同，否则两边各写各的、永不相交。
 * 这里刻意不复用 seckill-core 的类（保持模块零耦合），但格式对齐，并在注释里写明来源。
 */
public final class RuleEngineRedisKeys {

    private RuleEngineRedisKeys() {
    }

    /** 主库存：stock:a:{aid}:s:{sid}（与 RedisKeys.stock 一致） */
    public static String stock(long activityId, long skuId) {
        return "stock:a:" + activityId + ":s:" + skuId;
    }

    /** 两阶段预留：pending_stock:a:{aid}:s:{sid}（与 RedisKeys.pendingStock 一致） */
    public static String pendingStock(long activityId, long skuId) {
        return "pending_stock:a:" + activityId + ":s:" + skuId;
    }

    /** 总库存：total_stock:a:{aid}:s:{sid}（与 RedisKeys.totalStock 一致，预热时由 seckill 写入） */
    public static String totalStock(long activityId, long skuId) {
        return "total_stock:a:" + activityId + ":s:" + skuId;
    }

    /** 细粒度锁：lock:a:{aid}:s:{sid}（与 RedisKeys.lock 一致——修复原 ControlInstructionExecutor 漏 lock: 前缀的错配） */
    public static String lock(long activityId, long skuId) {
        return "lock:a:" + activityId + ":s:" + skuId;
    }

    /** 价表：price:a:{aid}:s:{sid}（REPRICE 写回的真实价表，替代原内存价表） */
    public static String price(long activityId, long skuId) {
        return "price:a:" + activityId + ":s:" + skuId;
    }
}
