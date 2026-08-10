package com.insightforge.seckill.core.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightforge.persistence.entity.Orders;
import com.insightforge.persistence.mapper.OrdersMapper;
import com.insightforge.seckill.core.dto.OrderCreatedEvent;
import com.insightforge.seckill.core.dto.SeckillRequest;
import com.insightforge.seckill.core.redis.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 订单补偿巡检（Phase 14.5：堵住「Redis 已扣减但订单没落库」的最后一公里）。
 *
 * 背景：异步路径先扣 Redis、再经 Kafka 落库。若消费者异常（进程挂/消息坏），
 * Redis 扣了但订单永远不来，且幂等键 TTL（10 分钟）过期后用户重试还会再扣一次。
 *
 * 方案：把订单载荷写进幂等键 value（见 {@link RedisIdempotentService#tryAcquire(String, String)}），
 * 本任务每 30s 扫一遍 `order:idemp:*`：凡是「键存在但 DB 无对应订单」的，用载荷重建订单
 * （幂等建单 + 扣 DB 库存，唯一约束天然防并发重放）。补单后窗口从「消费者故障时长」收敛到 ≤30s。
 *
 * 诚实边界：生产建议用 SCAN 替代 KEYS（量小时 KEYS 足够）；演示环境每 30s 全量扫开销可忽略。
 */
@Component
public class OrderCompensationTask {

    private static final Logger log = LoggerFactory.getLogger(OrderCompensationTask.class);

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final OrdersMapper ordersMapper;
    private final OrderCreationService orderCreationService;
    private final ObjectMapper objectMapper;

    public OrderCompensationTask(ObjectProvider<StringRedisTemplate> redisProvider,
                                 OrdersMapper ordersMapper,
                                 OrderCreationService orderCreationService,
                                 ObjectMapper objectMapper) {
        this.redisProvider = redisProvider;
        this.ordersMapper = ordersMapper;
        this.orderCreationService = orderCreationService;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${insightforge.compensation.interval-ms:30000}")
    public void sweep() {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return; // local 无 Redis：主路径是同步建单，无需补偿
        }
        Set<String> keys;
        try {
            keys = redis.keys(RedisKeys.ORDER_IDEMPOTENT_PREFIX + "*");
        } catch (Exception e) {
            log.warn("补偿巡检扫描失败（跳过本轮）", e);
            return;
        }
        if (keys == null || keys.isEmpty()) {
            return;
        }

        int compensated = 0;
        for (String key : keys) {
            String orderId = key.substring(RedisKeys.ORDER_IDEMPOTENT_PREFIX.length());
            Long existing = ordersMapper.selectCount(new QueryWrapper<Orders>().eq("order_id", orderId));
            if (existing != null && existing > 0) {
                continue; // 订单已落库，无需补偿
            }
            String payload = redis.opsForValue().get(key);
            if (payload == null) {
                continue;
            }
            try {
                OrderCreatedEvent evt = objectMapper.readValue(payload, OrderCreatedEvent.class);
                SeckillRequest req = new SeckillRequest();
                req.setUserId(evt.getUserId());
                req.setActivityId(evt.getActivityId());
                req.setSkuId(evt.getSkuId());
                req.setQty(evt.getQty());
                if (orderCreationService.createOrderIdempotent(req, evt.getOrderId(), evt.getPrice())) {
                    compensated++;
                    log.warn("补偿补单成功（原异步落库丢失）：orderId={}", orderId);
                }
            } catch (Exception e) {
                log.warn("补偿补单失败 orderId={}", orderId, e);
            }
        }
        if (compensated > 0) {
            log.warn("补偿巡检完成：本轮补单 {} 笔", compensated);
        }
    }
}
