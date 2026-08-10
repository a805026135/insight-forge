package com.insightforge.api.controller;

import com.insightforge.common.result.ApiResult;
import com.insightforge.api.auth.AuthContextHolder;
import com.insightforge.seckill.core.dto.SeckillRequest;
import com.insightforge.seckill.core.dto.StockInfo;
import com.insightforge.seckill.core.redis.RateLimitService;
import com.insightforge.seckill.core.service.SeckillService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 秒杀接入层（Phase 3，Redis + Lua 主路径 + DB 兜底；Phase 5 加接入层限流）。
 *
 * POST /api/seckill               用户抢购（优先 Redis Lua 原子扣减，无 Redis 走 DB 兜底）
 * POST /api/seckill/admin/preheat/{skuId}  库存预热：把 DB 库存刷进 Redis（秒杀前必调一次）
 * GET  /api/seckill/stock/{skuId} 查看某 SKU 剩余/已售/初始库存（便于验证与压测后核对）
 */
@RestController
@RequestMapping("/api/seckill")
public class SeckillController {

    /** 限流上限：同一用户同一活动，1 秒内最多放行 20 次（拦羊毛党/防误用）。 */
    private static final int RATE_MAX = 20;
    /** 滑动窗口大小：1 秒。 */
    private static final long RATE_WINDOW_MS = 1000;

    private final SeckillService seckillService;
    private final RateLimitService rateLimitService;

    public SeckillController(SeckillService seckillService, RateLimitService rateLimitService) {
        this.seckillService = seckillService;
        this.rateLimitService = rateLimitService;
    }

    @PostMapping
    public ApiResult<String> seckill(@RequestBody SeckillRequest req) {
        // Phase 14.3：以登录身份为准，禁止伪造他人 userId（前端传来的 userId 被覆盖）
        req.setUserId(AuthContextHolder.requireUserId());
        // Phase 5：接入层限流——按 (用户, 活动) 维度滑动窗口，属于"保护数据面的闸机"。
        // 放在 Controller 边缘，先于业务扣减执行；local profile 下自动退化成 JVM 内存版限流。
        String limitKey = "u:" + req.getUserId() + ":a:" + req.getActivityId();
        if (!rateLimitService.tryAcquire(limitKey, RATE_MAX, RATE_WINDOW_MS)) {
            return ApiResult.fail(429, "请求过于频繁，请稍后再试");
        }
        return seckillService.seckill(req);
    }

    @PostMapping("/admin/preheat/{skuId}")
    public ApiResult<Void> preheat(@PathVariable("skuId") Long skuId) {
        return seckillService.preheat(skuId);
    }

    @GetMapping("/stock/{skuId}")
    public ApiResult<StockInfo> stock(@PathVariable("skuId") Long skuId) {
        return seckillService.stockInfo(skuId);
    }
}
