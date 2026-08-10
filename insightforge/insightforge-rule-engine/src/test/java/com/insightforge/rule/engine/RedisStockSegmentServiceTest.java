package com.insightforge.rule.engine;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.insightforge.persistence.entity.AuditLog;
import com.insightforge.persistence.mapper.ActivitySkuMapper;
import com.insightforge.persistence.mapper.AuditLogMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 验证 Redis 分段库存服务：REALLOCATE 写回的是「与秒杀热路径逐字符一致」的同一份库存键。
 * 这是「Agent 控盘与用户抢购争用同一库存」落到代码层的硬证明。
 */
class RedisStockSegmentServiceTest {

    @Test
    void confirmPendingWritesBackSameStockKey() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.increment(eq("stock:a:1:s:1"), eq(10L))).thenReturn(110L);
        when(ops.increment(eq("pending_stock:a:1:s:1"), eq(-10L))).thenReturn(0L);
        ActivitySkuMapper skuMapper = mock(ActivitySkuMapper.class);
        AuditLogMapper auditMapper = mock(AuditLogMapper.class);

        RedisStockSegmentService svc = new RedisStockSegmentService(redis, skuMapper, auditMapper);
        svc.confirmPending(1, 1, 10);

        // 关键断言：调拨确认后写回的正是 stock:a:{aid}:s:{skid} —— 用户下一次 Lua 扣减会看到更多库存
        verify(ops).increment(eq("stock:a:1:s:1"), eq(10L));
        verify(ops).increment(eq("pending_stock:a:1:s:1"), eq(-10L));
        // Phase 14.1：确认入账必须同步 DB 初始/剩余库存 + 审计落库（否则结算对账把 AI 调拨误判为漂移）
        verify(ops).increment(eq("total_stock:a:1:s:1"), eq(10L));
        verify(skuMapper).increaseTotalStock(1L, 10);
        verify(auditMapper).insert(any(AuditLog.class));
    }

    @Test
    void allocateNewSegmentOnlyTouchesPendingNotStock() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        // segcount 用「单参」increment（自增 +1）；pending 用「双参」increment(amount)
        when(ops.increment(eq("stock:a:1:s:1:segcount"))).thenReturn(1L);
        when(ops.increment(eq("pending_stock:a:1:s:1"), eq(20L))).thenReturn(20L);

        RedisStockSegmentService svc = new RedisStockSegmentService(redis,
                mock(ActivitySkuMapper.class), mock(AuditLogMapper.class));
        StockSegmentService.AllocResult r = svc.allocateNewSegment(1, 1, 20);
        assertEquals(1, r.newSegment());
        assertEquals(20, r.amount());
        // 调拨第一阶段只动 pending + segcount，绝不碰真实 stock 键（pending 期间用户碰不到，防超卖）
        verify(ops, never()).increment(eq("stock:a:1:s:1"), anyLong());
    }
}
