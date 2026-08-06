package com.insightforge.seckill.core.redis;

/**
 * Redis Lua 扣减的返回结果枚举（对应 seckill_deduct.lua 的三种返回值）。
 *
 * 把脚本里的数字 1 / 0 / -1 翻译成人话，业务代码里就能直接 switch，
 * 不用在四处写魔法数字，也方便后续加新的返回类型（比如 -2 表示限流拒绝）。
 */
public enum DeductResult {

    /** 扣减成功（脚本返回 1） */
    SUCCESS,

    /** 库存不足，已售罄（脚本返回 0） */
    SOLD_OUT,

    /** 库存 key 不存在（脚本返回 -1）：说明还没预热，应回退到 DB 兜底 */
    NOT_PREHEATED,

    /** 未知错误（脚本返回 null 或异常），保守起见也走 DB 兜底 */
    ERROR
}
