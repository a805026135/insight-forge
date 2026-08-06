package com.insightforge.api.controller;

import com.insightforge.common.result.ApiResult;
import com.insightforge.seckill.core.dto.SeckillRequest;
import com.insightforge.seckill.core.dto.StockInfo;
import com.insightforge.seckill.core.service.SeckillService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 秒杀接入层（Phase 3，Redis + Lua 主路径 + DB 兜底）。
 *
 * POST /api/seckill               用户抢购（优先 Redis Lua 原子扣减，无 Redis 走 DB 兜底）
 * POST /api/seckill/admin/preheat/{skuId}  库存预热：把 DB 库存刷进 Redis（秒杀前必调一次）
 * GET  /api/seckill/stock/{skuId} 查看某 SKU 剩余/已售/初始库存（便于验证与压测后核对）
 */
@RestController
@RequestMapping("/api/seckill")
public class SeckillController {

    private final SeckillService seckillService;

    public SeckillController(SeckillService seckillService) {
        this.seckillService = seckillService;
    }

    @PostMapping
    public ApiResult<String> seckill(@RequestBody SeckillRequest req) {
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
