package com.insightforge.seckill.core.redis;

/** Redis 可恢复库存预留结果。 */
public enum ReservationResult {
    SUCCESS,
    DUPLICATE,
    SOLD_OUT,
    NOT_PREHEATED,
    ERROR,
    OVERLOADED
}
