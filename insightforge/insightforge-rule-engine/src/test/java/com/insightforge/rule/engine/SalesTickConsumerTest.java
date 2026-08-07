package com.insightforge.rule.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 验证销售心跳消费者：听到 sales.tick 后，去读「同一份」Redis 库存键，
 * 把剩余/总库存喂给规则引擎，并吞掉坏消息（绝不阻塞用户抢购）。
 */
class SalesTickConsumerTest {

    @Test
    void onTickReadsSameSharedStockKeyAndDrivesEngine() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get("stock:a:1:s:1")).thenReturn("50");
        when(ops.get("total_stock:a:1:s:1")).thenReturn("100");

        RuleEngineService engine = mock(RuleEngineService.class);
        when(engine.evaluate(eq(1L), any(SalesTick.class))).thenReturn(List.of());

        ControlInstructionExecutor executor = mock(ControlInstructionExecutor.class);
        when(executor.executeAll(any())).thenReturn(List.of());

        SalesTickConsumer consumer = new SalesTickConsumer(redis, engine, executor, new ObjectMapper());

        String json = "{\"activityId\":1,\"skuId\":1,\"qty\":3,\"ts\":1700000000000}";
        consumer.onTick(new ConsumerRecord<>("sales.tick", 0, 0L, "1:1", json));

        // 消费者读了与秒杀「同一份」Redis 库存键
        verify(ops).get(eq("stock:a:1:s:1"));
        verify(ops).get(eq("total_stock:a:1:s:1"));
        // 把 剩余50/总100（ratio 0.5）喂给了规则引擎事实
        verify(engine).evaluate(eq(1L), argThat(t -> t.getRemainingStock() == 50 && t.getTotalStock() == 100));
    }

    @Test
    void onTickSwallowsBadJsonWithoutThrowing() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RuleEngineService engine = mock(RuleEngineService.class);
        ControlInstructionExecutor executor = mock(ControlInstructionExecutor.class);

        SalesTickConsumer consumer = new SalesTickConsumer(redis, engine, executor, new ObjectMapper());
        // 一条坏心跳绝不能抛异常、阻塞消费线程
        consumer.onTick(new ConsumerRecord<>("sales.tick", 0, 0L, "x", "not-json"));
        verify(engine, never()).evaluate(anyLong(), any());
        verify(executor, never()).executeAll(any());
    }
}
