package com.insightforge.seckill.core.service;

import com.insightforge.common.result.ApiResult;
import com.insightforge.persistence.entity.Activity;
import com.insightforge.persistence.mapper.ActivityMapper;
import com.insightforge.seckill.core.reconcile.ReconcileItem;
import com.insightforge.seckill.core.reconcile.ReconcileReport;
import com.insightforge.seckill.core.reconcile.ReconcileService;
import com.insightforge.seckill.core.state.ActivityStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 活动状态机服务（Phase 6）。
 *
 * 大白话：活动不是想上线就上线、想关就关。每一步操作都先过「状态机校验」，
 * 把乱迁转拦在门外；并且把「对账」和「状态迁转」绑死——上线前必须对账通过，
 * 结算时必须跑结算对账。状态正确 + 账对得上，活动才允许往前走。
 *
 * 并发保护：这些是管理面操作（上线 / 结算 / 关闭），用 @Transactional + MyBatis-Plus
 * 的 updateById 即可（单条 UPDATE 原子）。它们不在用户抢购热路径上，不需要分布式锁。
 * （spec 路线图里提到的 Redisson 分布式锁，实际防超卖由 Phase 3 的 Redis+Lua 原子扣减 +
 *  Phase 2 的 DB 条件更新兜底层承载，本项目未单独引入 Redisson 锁 Bean——如实说明，不虚报。）
 */
@Service
public class ActivityService {

    private final ActivityMapper activityMapper;
    private final ReconcileService reconcileService;

    public ActivityService(ActivityMapper activityMapper, ReconcileService reconcileService) {
        this.activityMapper = activityMapper;
        this.reconcileService = reconcileService;
    }

    /** 取活动，不存在抛异常（业务层尽早 fail-fast）。 */
    private Activity require(long activityId) {
        Activity a = activityMapper.selectById(activityId);
        if (a == null) {
            throw new IllegalArgumentException("活动不存在：id=" + activityId);
        }
        return a;
    }

    /**
     * DRAFT → ARMED：武装上线。两步都过才写状态：
     *   1) 状态机校验：DRAFT→ARMED 合法（已是 LIVE 之类直接抛异常）；
     *   2) 上线前对账闸门：MySQL↔Redis 库存对账不过，绝不写 ARMED（宁可阻断不上线）。
     */
    @Transactional
    public ApiResult<ReconcileReport> arm(long activityId) {
        Activity a = require(activityId);
        ActivityStatus next = ActivityStatus.from(a.getStatus()).transitionTo(ActivityStatus.ARMED);

        ReconcileReport report = reconcileService.reconcileStockConsistency(activityId);
        if (!report.isPassed()) {
            // 对账失败：保留原状态（不写 ARMED），返回 409 让调用方看到差异明细
            return ApiResult.fail(409, "库存对账未通过，禁止上线（ARMED）");
        }
        a.setStatus(next.name());
        activityMapper.updateById(a);
        return ApiResult.ok(report);
    }

    /** ARMED → LIVE：开抢。 */
    @Transactional
    public ApiResult<Void> goLive(long activityId) {
        Activity a = require(activityId);
        ActivityStatus next = ActivityStatus.from(a.getStatus()).transitionTo(ActivityStatus.LIVE);
        a.setStatus(next.name());
        activityMapper.updateById(a);
        return ApiResult.ok(null);
    }

    /**
     * LIVE → SETTLING：结算。跑「最终库存 = 初始 - 成功单」对账，把差异暴露出来。
     * 即便有差异也进入 SETTLING（差异需要人工 / Agent 复盘，而不是静默丢弃结算），
     * 报告里的 passed 字段告诉调用方有没有对不平。
     */
    @Transactional
    public ApiResult<ReconcileReport> settle(long activityId) {
        Activity a = require(activityId);
        ActivityStatus.from(a.getStatus()).transitionTo(ActivityStatus.SETTLING); // 校验合法迁转

        ReconcileReport report = reconcileService.reconcileFinalStock(activityId);
        a.setStatus(ActivityStatus.SETTLING.name());
        activityMapper.updateById(a);
        return ApiResult.ok(report);
    }

    /** SETTLING → CLOSED：关闭归档（终态）。 */
    @Transactional
    public ApiResult<Void> close(long activityId) {
        Activity a = require(activityId);
        ActivityStatus next = ActivityStatus.from(a.getStatus()).transitionTo(ActivityStatus.CLOSED);
        a.setStatus(next.name());
        activityMapper.updateById(a);
        return ApiResult.ok(null);
    }

    /** 一键查看两类对账（不改状态），方便实时巡检 / 演示。 */
    public ApiResult<ReconcileReport> reconcile(long activityId) {
        Activity a = require(activityId);
        ReconcileReport consistency = reconcileService.reconcileStockConsistency(activityId);
        ReconcileReport finalStock = reconcileService.reconcileFinalStock(activityId);

        ReconcileReport merged = new ReconcileReport();
        merged.setActivityStatus(a.getStatus());
        merged.getItems().add(new ReconcileItem("== 上线前：MySQL↔Redis 库存一致性 ==", true, ""));
        merged.getItems().addAll(consistency.getItems());
        merged.getItems().add(new ReconcileItem("== 结算：最终库存 = 初始 - 成功单 ==", true, ""));
        merged.getItems().addAll(finalStock.getItems());
        // 整体 passed 只由真正的明细项决定（两个分隔项仅用于阅读，不参与判定）
        merged.setPassed(merged.getItems().stream()
                .filter(i -> !i.getDimension().startsWith("=="))
                .allMatch(ReconcileItem::isOk));
        return ApiResult.ok(merged);
    }
}
