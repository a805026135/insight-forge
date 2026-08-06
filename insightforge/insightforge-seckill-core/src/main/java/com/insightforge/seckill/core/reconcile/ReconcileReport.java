package com.insightforge.seckill.core.reconcile;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 对账报告（Phase 6）。
 *
 * 大白话：对账就是「事后盘点，确认账对得上」。本系统关心两类账：
 *   1) 上线前：MySQL 库存 和 Redis 库存 是不是一致（双写不能偏）。
 *   2) 结算时：最终剩余库存 是不是等于 初始库存 - 成功下单数（绝不能超卖 / 少卖）。
 *
 * 报告里放若干 {@link ReconcileItem}，整体 passed = 所有明细项都 ok。
 * 任何一项不 ok，passed 立刻变 false，活动就不能上线 / 必须人工介入。
 */
@Data
public class ReconcileReport {

    /** 整体是否通过（初始 true；只要有任一明细项 ok=false，就被置为 false）。 */
    private boolean passed = true;

    /** 触发对账时活动的状态（便于在报告里一眼看到「是在哪个阶段做的对账」）。 */
    private String activityStatus;

    /** 明细项列表。 */
    private List<ReconcileItem> items = new ArrayList<>();

    /** 追加一条明细；若该项不通过，整体 passed 立刻标记为 false。 */
    public void add(ReconcileItem item) {
        items.add(item);
        if (!item.isOk()) {
            passed = false;
        }
    }
}
