package com.insightforge.seckill.core.redis;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Redis 库存服务（Phase 3 的主路径）。
 *
 * 它只做两件事，但都做得很"原子"：
 * 1) 预热 preheat()：把 DB 里的剩余库存刷进 Redis（秒杀开始前必须做，否则 key 不存在）。
 *    这一步对应项目里 Agent 的"库存预热"任务——上线前由 Agent/运维把库存铺到 Redis。
 * 2) 扣减 deduct()：执行 seckill_deduct.lua，靠 Redis 单线程 + Lua 原子，
 *    保证"读→判→扣"不被并发打断，绝不会超卖。
 *
 * 注意：本类**不**加 @Service 注解，而是只由 RedisConfig（@Profile("!local")）以 @Bean 方式装配。
 * 这样在 local profile（沙箱零依赖冒烟）下 RedisConfig 不生效、本类也不会被组件扫描到，
 * SeckillService 检测到它不存在，自动走 Phase 2 的 DB 条件更新兜底——一份代码两种跑法。
 */
public class RedisStockService {

    private final StringRedisTemplate redis;
    private final RedisScript<Long> deductScript;
    private final RedisScript<Long> reserveScript;

    private static final long RESERVATION_TTL_SECONDS = 24 * 60 * 60;
    private static final long MAX_PENDING_RESERVATIONS = 10_000;
    private static final long FIRST_COMPENSATION_DELAY_MS = 5_000;

    public RedisStockService(StringRedisTemplate redis, RedisScript<Long> deductScript) {
        this(redis, deductScript, null);
    }

    public RedisStockService(StringRedisTemplate redis,
                             RedisScript<Long> deductScript,
                             RedisScript<Long> reserveScript) {
        this.redis = redis;
        this.deductScript = deductScript;
        this.reserveScript = reserveScript;
    }

    /**
     * 原子创建可恢复库存预留。重复请求、扣减、恢复载荷与补偿索引不会再出现中间故障窗口。
     */
    public ReservationResult reserve(long activityId, long skuId, int qty,
                                     String orderId, String payload) {
        if (reserveScript == null) {
            return ReservationResult.ERROR;
        }
        List<String> keys = List.of(RedisKeys.stock(activityId, skuId),
                RedisKeys.orderIdempotent(orderId), RedisKeys.ORDER_RESERVATION_PENDING);
        long deadline = System.currentTimeMillis() + FIRST_COMPENSATION_DELAY_MS;
        Long result;
        try {
            result = redis.execute(reserveScript, keys, String.valueOf(qty), payload,
                    String.valueOf(RESERVATION_TTL_SECONDS), String.valueOf(deadline), orderId,
                    String.valueOf(MAX_PENDING_RESERVATIONS));
        } catch (RuntimeException e) {
            return ReservationResult.ERROR;
        }
        if (result == null) {
            return ReservationResult.ERROR;
        }
        return switch (result.intValue()) {
            case 2 -> ReservationResult.DUPLICATE;
            case 1 -> ReservationResult.SUCCESS;
            case 0 -> ReservationResult.SOLD_OUT;
            case -1 -> ReservationResult.NOT_PREHEATED;
            default -> ReservationResult.ERROR;
            case -3 -> ReservationResult.OVERLOADED;
        };
    }

    /** 按截止时间分批领取待收敛预留；复杂度 O(logN+M)，替代生产危险的 KEYS 全量扫描。 */
    public List<String> dueReservations(long nowEpochMs, int batchSize) {
        Set<String> ids = redis.opsForZSet().rangeByScore(
                RedisKeys.ORDER_RESERVATION_PENDING, 0, nowEpochMs, 0, batchSize);
        return ids == null ? List.of() : new ArrayList<>(ids);
    }

    public String reservationPayload(String orderId) {
        return redis.opsForValue().get(RedisKeys.orderIdempotent(orderId));
    }

    /** DB 已收敛后仅移出待补偿索引；保留幂等凭证至 TTL，继续阻止重复购买。 */
    public void acknowledgeReservation(String orderId) {
        redis.opsForZSet().remove(RedisKeys.ORDER_RESERVATION_PENDING, orderId);
    }

    /** 本轮失败时后移截止时间，避免坏记录形成无间隔热循环。 */
    public void rescheduleReservation(String orderId, long nextEpochMs) {
        redis.opsForZSet().add(RedisKeys.ORDER_RESERVATION_PENDING, orderId, nextEpochMs);
    }

    /**
     * 库存预热：把 DB 剩余库存写入 Redis，作为秒杀期间的"权威库存"。
     * key 形如 stock:a:{activityId}:s:{skuId}（见 RedisKeys）。
     */
    public void preheat(long activityId, long skuId, int stock) {
        redis.opsForValue().set(RedisKeys.stock(activityId, skuId), String.valueOf(stock));
        // 集成缝：同步写入「总库存」键，供规则引擎消费者算库存水位比例（离线预热，非用户热路径）
        redis.opsForValue().set(RedisKeys.totalStock(activityId, skuId), String.valueOf(stock));
    }

    /**
     * 原子扣减（秒杀热路径的核心调用）。
     *
     * redis.execute(script, keys, args) 会把这个脚本发到 Redis 单线程执行，
     * 返回的数字被 Spring 自动转成 Long，我们再把 Long 翻译成 DeductResult。
     *
     * @return SUCCESS 扣成功 / SOLD_OUT 售罄 / NOT_PREHEATED 没预热 / ERROR 异常
     */
    public DeductResult deduct(long activityId, long skuId, int qty) {
        String key = RedisKeys.stock(activityId, skuId);
        // KEYS 列表（脚本里用 KEYS[1] 取），ARGV 是参数（这里传 qty）
        List<String> keys = Collections.singletonList(key);
        Long result = redis.execute(deductScript, keys, String.valueOf(qty));
        if (result == null) {
            return DeductResult.ERROR;
        }
        return switch (result.intValue()) {
            case 1 -> DeductResult.SUCCESS;
            case 0 -> DeductResult.SOLD_OUT;
            default -> DeductResult.NOT_PREHEATED; // -1 或意外值
        };
    }

    /** 读取 Redis 里的剩余库存（演示/验证用）。没预热返回 -1。 */
    public int getStock(long activityId, long skuId) {
        String v = redis.opsForValue().get(RedisKeys.stock(activityId, skuId));
        return v == null ? -1 : Integer.parseInt(v);
    }

    /**
     * 库存回补（Phase 14.1：取消订单时调用）。
     * 仅在库存键已存在时 INCRBY（未预热的活动不凭空造库存键），
     * 保证 LIVE 期间 Redis 权威库存与 DB 同步回补，结算对账不漂移。
     */
    public void increaseStockIfExists(long activityId, long skuId, int qty) {
        String key = RedisKeys.stock(activityId, skuId);
        if (Boolean.TRUE.equals(redis.hasKey(key))) {
            redis.opsForValue().increment(key, qty);
        }
    }

    /** 读取 Redis 里的实时价（REPRICE 写回键）；未调价返回 null，调用方回退 DB 秒杀价。 */
    public BigDecimal readPrice(long activityId, long skuId) {
        String v = redis.opsForValue().get(RedisKeys.price(activityId, skuId));
        return v == null ? null : new BigDecimal(v);
    }
}
