package com.insightforge.api.controller;

import com.insightforge.common.result.ApiResult;
import com.insightforge.seckill.core.state.IllegalStateTransitionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理（Phase 14.1）。
 *
 * 之前非法参数/非法状态迁转会直接抛 Spring 默认 500；这里统一翻译成 ApiResult 业务码：
 * 参数问题 400、状态机非法迁转 409、其余 500。前端按 code!=0 展示 message 即可。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ApiResult<Void> badRequest(IllegalArgumentException e) {
        log.warn("参数/业务校验失败: {}", e.getMessage());
        return ApiResult.fail(400, e.getMessage());
    }

    /**
     * 静态资源/未知路径不存在：必须返回真正的 404（不能吞成 200），
     * 否则前端与监控（actuator 探测）会误判。Spring 6.1 对未匹配路径抛
     * NoResourceFoundException，这里显式翻译为 404。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResult<Void>> notFound(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResult.fail(404, "资源不存在: " + e.getResourcePath()));
    }

    @ExceptionHandler(IllegalStateTransitionException.class)
    public ApiResult<Void> conflict(IllegalStateTransitionException e) {
        log.warn("非法状态迁转: {}", e.getMessage());
        return ApiResult.fail(409, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ApiResult<Void> serverError(Exception e) {
        log.error("未处理异常", e);
        return ApiResult.fail(500, "系统繁忙，请稍后再试");
    }
}
