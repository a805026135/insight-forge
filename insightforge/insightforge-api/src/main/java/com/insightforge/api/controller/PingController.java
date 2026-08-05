package com.insightforge.api.controller;

import com.insightforge.common.result.ApiResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查 / 连通性探测（Phase 0）。
 * 真正的业务接口（/api/seckill 等）在后续 Phase 逐步加入。
 */
@RestController
public class PingController {

    @GetMapping("/api/ping")
    public ApiResult<String> ping() {
        return ApiResult.ok("pong");
    }
}
