package com.insightforge.rule.engine;

import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.insightforge.persistence.entity.AuditLog;
import com.insightforge.persistence.mapper.ActivitySkuMapper;
import com.insightforge.persistence.mapper.AuditLogMapper;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 分段库存服务（Redis 实现，@Profile("integration")）。
 *
 * 与 {@link InMemoryStockSegmentService} 语义一致，但操作的是<b>与秒杀热路径同一份</b> Redis 库存：
 * - 主库存键 stock:a:{aid}:s:{skid}（与 seckill-core RedisKeys.stock 逐字符一致）；
 * - 两阶段预留键 pending_stock:a:{aid}:s:{skid}。
 *
 * 关键集成点（让「Agent 控盘与用户抢购争用同一库存」成真）：
 * - {@code confirmPending} 用 INCRBY 把调拨量加回<b>真实 stock 键</b>——这一步之后，
 *   用户下一次 Redis Lua 扣减就会看到更多库存。AI 调拨真正改变了用户可抢的库存。
 * - 调拨期间先 INCRBY pending_stock（不碰 stock 键），确认才入账，保留「pending 期间用户碰不到」的防超卖铁律。
 *
 * 调用约定：本服务的每个写方法都由 {@link ControlInstructionExecutor} 在拿到 Redisson 锁
 * （lock:a:{aid}:s:{skid}，与秒杀同键）之后调用，因此方法内部无需再加锁，串行化由锁保证。
 */
@Component
@Profile("integration")
public class RedisStockSegmentService implements StockSegmentService {

    private final StringRedisTemplate redis;
    /** Phase 14.1：调拨确认必须同步 DB（total/stock）+ 审计，否则结算对账把 AI 调拨误判为漂移。 */
    private final ActivitySkuMapper activitySkuMapper;
    private final AuditLogMapper auditLogMapper;
    /** 记录本服务碰过的键，供 reset()（测试隔离）清理。 */
    private final Set<String> touched = ConcurrentHashMap.newKeySet();

    public RedisStockSegmentService(StringRedisTemplate redis,
                                    ActivitySkuMapper activitySkuMapper,
                                    AuditLogMapper auditLogMapper) {
        this.redis = redis;
        this.activitySkuMapper = activitySkuMapper;
        this.auditLogMapper = auditLogMapper;
    }

    private String stockKey(long activityId, long skuId) {
        return RuleEngineRedisKeys.stock(activityId, skuId);
    }

    private String pendingKey(long activityId, long skuId) {
        return RuleEngineRedisKeys.pendingStock(activityId, skuId);
    }

    private String segCountKey(long activityId, long skuId) {
        return RuleEngineRedisKeys.stock(activityId, skuId) + ":segcount";
    }

    private long getLong(String key, long def) {
        String v = redis.opsForValue().get(key);
        return v == null ? def : Long.parseLong(v);
    }

    @Override
    public AllocResult allocateNewSegment(long activityId, long skuId, int amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("调拨数量不可为负（违反『库存不可透支』护栏）");
        }
        // 只建新段 + 记 pending（不碰真实 stock 键，pending 期间用户碰不到）
        Long newSeg = redis.opsForValue().increment(segCountKey(activityId, skuId));
        Long pending = redis.opsForValue().increment(pendingKey(activityId, skuId), amount);
        touched.add(segCountKey(activityId, skuId));
        touched.add(pendingKey(activityId, skuId));
        touched.add(stockKey(activityId, skuId));
        return new AllocResult(newSeg == null ? 1 : newSeg.intValue(), amount);
    }

    @Override
    public void confirmPending(long activityId, long skuId, int amount) {
        // 确认调入：撤销 pending + 把调拨量加回真实 stock 键（AI 调拨真的增加用户可抢库存）
        redis.opsForValue().increment(pendingKey(activityId, skuId), -amount);
        redis.opsForValue().increment(stockKey(activityId, skuId), amount);
        redis.opsForValue().increment(RuleEngineRedisKeys.totalStock(activityId, skuId), amount);
        touched.add(RuleEngineRedisKeys.totalStock(activityId, skuId));

        // Phase 14.1：同步 DB 初始/剩余库存 + 审计落库 ——
        // 结算对账公式「total_stock − 成功单 = 剩余库存」由此把 AI 调拨纳入期望值；
        // audit_log 是「AI 改了库存」的持久化证据，复盘/面试展示都能追溯。
        if (amount != 0) {
            activitySkuMapper.increaseTotalStock(skuId, amount);
            AuditLog log = new AuditLog();
            log.setActivityId(activityId);
            log.setSkuId(skuId);
            log.setActionType("REALLOCATE");
            log.setAmount(amount);
            log.setDetail("规则引擎确认调拨 +" + amount + "（seg 入账，AI 控盘写回同一 stock 键）");
            log.setCreatedAt(LocalDateTime.now());
            auditLogMapper.insert(log);
        }
    }

    @Override
    public void releasePending(long activityId, long skuId, int amount) {
        // 补偿：撤销 pending + 移除段标记，库存回到调拨前（绝不超卖）
        redis.opsForValue().increment(pendingKey(activityId, skuId), -amount);
        redis.opsForValue().decrement(segCountKey(activityId, skuId));
    }

    @Override
    public int available(long activityId, long skuId) {
        long stock = getLong(stockKey(activityId, skuId), 0);
        long pending = getLong(pendingKey(activityId, skuId), 0);
        return (int) Math.max(0, stock - pending);
    }

    @Override
    public int segmentCount(long activityId, long skuId) {
        return (int) getLong(segCountKey(activityId, skuId), 0);
    }

    @Override
    public int total(long activityId, long skuId) {
        return (int) getLong(stockKey(activityId, skuId), 0);
    }

    @Override
    public void reset() {
        touched.forEach(redis::delete);
        touched.clear();
    }
}
