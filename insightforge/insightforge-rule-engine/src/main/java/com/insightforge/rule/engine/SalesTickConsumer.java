package com.insightforge.rule.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 销售心跳消费者（集成缝：sales.tick → 规则引擎 → 控盘执行）。
 *
 * 大白话：秒杀每卖出一笔，seckill-core 就往 sales.tick 丢一条心跳（只带 activityId/skuId/qty/ts）。
 * 本消费者在这里「听」：听到后，去<b>同一份 Redis 库存键</b>读当前剩余，拼成 Drools 事实(SalesTick)，
 * 交给 {@link RuleEngineService} 跑 DSL 出工单，再交给 {@link ControlInstructionExecutor} 在
 * <b>同一把锁</b>下写回同一份库存/价表。
 *
 * 这就把「Agent 控盘与用户抢购争用同一库存、同一把锁」从原型变真实：
 * 用户走 Redis+Lua 扣减真实 stock 键，AI 调拨走 Redisson 锁同一把 lock 键、写回同一 stock 键，
 * 两者在 Redis 里真正相遇、互相可见。
 *
 * 仅 @Profile("integration") 生效：沙箱/单测默认 !integration，无 Kafka 也零依赖。
 * 任何一条消息解析/处理异常都被吞掉（打日志 + 跳过），绝不让一条坏心跳阻塞消费、更不阻塞用户抢购。
 */
@Component
@Profile("integration")
public class SalesTickConsumer {

    private static final Logger log = LoggerFactory.getLogger(SalesTickConsumer.class);
    /** 必须与 seckill-core KafkaTopicConfig.SALES_TICK 逐字符一致（"sales.tick"）。 */
    static final String TOPIC = "sales.tick";

    private final StringRedisTemplate redis;
    private final RuleEngineService ruleEngineService;
    private final ControlInstructionExecutor executor;
    private final ObjectMapper objectMapper;

    public SalesTickConsumer(StringRedisTemplate redis,
                              RuleEngineService ruleEngineService,
                              ControlInstructionExecutor executor,
                              ObjectMapper objectMapper) {
        this.redis = redis;
        this.ruleEngineService = ruleEngineService;
        this.executor = executor;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = TOPIC, groupId = "rule-engine")
    public void onTick(ConsumerRecord<String, String> record) {
        try {
            SalesTickEventPayload p = objectMapper.readValue(record.value(), SalesTickEventPayload.class);
            if (p.getActivityId() == null || p.getSkuId() == null) {
                log.warn("[sales.tick] 丢弃缺字段的心跳: {}", record.value());
                return;
            }
            long activityId = p.getActivityId();
            long skuId = p.getSkuId();
            // 读「同一份 Redis 库存键」——这是与用户抢购共享的真实剩余库存
            int remaining = readLong(RuleEngineRedisKeys.stock(activityId, skuId), 0);
            int total = readLong(RuleEngineRedisKeys.totalStock(activityId, skuId), remaining);
            double ratio = total > 0 ? (double) remaining / total : 1.0;

            // 构造 Drools 只读事实。说明：其余观控信号（售速/价差/转化/售罄ETA）需要指标聚合器，
            // 属 M7+ 后续增强；此处给出能真理丰度推导的 remaining/total/ratio，其余置 0 并显式标注，绝不虚报。
            SalesTick tick = new SalesTick(activityId, skuId, ratio, 0.0, 0.0, 0.0,
                    remaining, total, 0, p.getTs() == null ? System.currentTimeMillis() : p.getTs());

            List<ControlInstruction> instructions = ruleEngineService.evaluate(activityId, tick);
            if (!instructions.isEmpty()) {
                List<ControlInstructionExecutor.ExecutionResult> results = executor.executeAll(instructions);
                log.info("[sales.tick] a={} s={} 剩{}/{} 命中{}条工单 -> {}",
                        activityId, skuId, remaining, total, instructions.size(), summarize(results));
            }
        } catch (Exception e) {
            // 一条坏心跳绝不阻塞消费线程，更不阻塞用户抢购
            log.error("[sales.tick] 处理失败(已跳过): {}", record.value(), e);
        }
    }

    private int readLong(String key, int def) {
        String v = redis.opsForValue().get(key);
        return v == null ? def : Integer.parseInt(v);
    }

    private String summarize(List<ControlInstructionExecutor.ExecutionResult> rs) {
        StringBuilder sb = new StringBuilder();
        for (ControlInstructionExecutor.ExecutionResult r : rs) {
            sb.append(r.action()).append('=').append(r.applied() ? "ok" : "skip").append(';');
        }
        return sb.toString();
    }
}
