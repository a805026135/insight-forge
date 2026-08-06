package com.insightforge.api.controller;

import com.insightforge.common.result.ApiResult;
import com.insightforge.seckill.core.reconcile.ReconcileReport;
import com.insightforge.seckill.core.service.ActivityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 活动生命周期接入层（Phase 6）：状态机迁转 + 对账查看。
 *
 * POST /api/activity/{id}/arm        DRAFT → ARMED（前置 MySQL↔Redis 库存对账，不过不让上线）
 * POST /api/activity/{id}/live       ARMED → LIVE
 * POST /api/activity/{id}/settle     LIVE → SETTLING（结算对账：最终库存 = 初始 - 成功单）
 * POST /api/activity/{id}/close      SETTLING → CLOSED
 * GET  /api/activity/{id}/reconcile  查看两类对账（不改状态）
 */
@RestController
@RequestMapping("/api/activity")
public class ActivityController {

    private final ActivityService activityService;

    public ActivityController(ActivityService activityService) {
        this.activityService = activityService;
    }

    @PostMapping("/{id}/arm")
    public ApiResult<ReconcileReport> arm(@PathVariable long id) {
        return activityService.arm(id);
    }

    @PostMapping("/{id}/live")
    public ApiResult<Void> live(@PathVariable long id) {
        return activityService.goLive(id);
    }

    @PostMapping("/{id}/settle")
    public ApiResult<ReconcileReport> settle(@PathVariable long id) {
        return activityService.settle(id);
    }

    @PostMapping("/{id}/close")
    public ApiResult<Void> close(@PathVariable long id) {
        return activityService.close(id);
    }

    @GetMapping("/{id}/reconcile")
    public ApiResult<ReconcileReport> reconcile(@PathVariable long id) {
        return activityService.reconcile(id);
    }
}
