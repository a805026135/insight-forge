package com.insightforge.seckill.core.reconcile;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 对账明细项（Phase 6）。
 *
 * 一次对账（{@link ReconcileReport}）往往检查多个维度（每个 SKU 的库存一致性、
 * 每个 SKU 的结算公式……），每个维度就是一条 ReconcileItem：
 * - dimension ：维度名，如 "mysql-redis:1"（活动 1 的 MySQL↔Redis 比对）、"final-stock:1"（结算公式）。
 * - ok        ：这一项是否通过。
 * - detail    ：人类可读的明细，方便排查（如 "db_stock=100, redis_stock=100"）。
 */
@Data
@AllArgsConstructor
public class ReconcileItem {

    /** 维度名 */
    private String dimension;

    /** 是否通过 */
    private boolean ok;

    /** 明细描述 */
    private String detail;
}
