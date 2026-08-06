package com.insightforge.seckill.core.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

/**
 * 多级缓存通用组件（Phase 5）：L1 本地 Caffeine + L2 分布式 Redis，读穿（read-through）+ 三防。
 *
 * ====================================================================
 * 大白话：秒杀系统里"查商品 / 查库存快照"这种读请求特别多，每次都打数据库会很慢也很贵。
 * 我们加两层"备忘录"：
 *   - L1：进程内的 Caffeine（最快，但只在本机，重启即丢）。
 *   - L2：Redis（所有机器共享，重启不丢，但比本地慢一点）。
 * 查的时候先翻 L1，没有再翻 L2，再没有才去数据库；查到的顺手补回这两层。
 *
 * 还要顺手解决缓存三大经典坑：
 *   - 击穿（breakdown）：某个热点 key 突然失效，海量请求同时打到 DB。
 *       Caffeine 的 get(key, loader) 保证"同一个 key 同一时刻只有一个线程去回源"，
 *       其他人等它结果，天然防击穿（也叫 single-flight / 单飞）。
 *   - 穿透（penetration）：查一个根本不存在的 key（如 sku=999999），每次都穿透到 DB。
 *       我们把"查无此物"也缓存成一个特殊占位符（NULL 哨兵），下次直接命中占位符返回 null，
 *       不再打 DB。
 *   - 雪崩（avalanche）：大量 key 在同一秒同时过期，DB 被瞬间冲垮。
 *       给 TTL 加一点随机抖动（jitter），让过期时间错开，不会集体失效。
 * ====================================================================
 *
 * 注意：本类是普通 Java 类（不是 Spring Bean），由 SkuCacheService 这个 @Service 持有并装配依赖。
 * 这样既能用 Spring 注入的 RedisTemplate / ObjectMapper，又不用把泛型塞进 Bean 定义里。
 */
public class MultiLevelCache<K, V> {

    private static final Logger log = LoggerFactory.getLogger(MultiLevelCache.class);

    /** L2 里"查无此物"的固定占位符（Redis 不能存 null，用特殊字符串代替）。 */
    private static final String NULL_SENTINEL = "__NULL__";

    /** L1 里与 NULL_SENTINEL 对应的"对象形态"哨兵（Caffeine 不允许直接缓存 null，用这个非空对象占位）。 */
    private static final Object NULL_SENTINEL_OBJ = new Object();

    /** L1：Caffeine。value 统一用 Object 装，便于塞 NULL 哨兵对象（Caffeine 不允许直接缓存 null）。 */
    private final Cache<K, Object> l1;

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final ObjectMapper objectMapper;
    private final String prefix;
    private final Duration baseTtl;
    private final Duration jitter;
    private final Class<V> valueType;

    /** L1/L2 都未命中时的"回源"函数（通常是查数据库），在 get() 时才用到。 */
    private final Function<K, V> loader;

    /**
     * @param loader        回源函数（如 mapper::selectById）
     * @param redisProvider Spring 注入的 RedisTemplate 提供者；local profile 下拿不到（为 null），自动退化为只用 L1
     * @param objectMapper  JSON 序列化/反序列化（L2 存对象用）
     * @param prefix        L2 的 key 前缀，避免和其他业务 key 撞名
     * @param baseTtl       基础过期时间
     * @param jitter        随机抖动上限（实际 TTL = baseTtl + [0, jitter)）
     * @param valueType     值的类型（L2 反序列化 JSON 需要知道具体类）
     */
    public MultiLevelCache(Function<K, V> loader,
                           ObjectProvider<StringRedisTemplate> redisProvider,
                           ObjectMapper objectMapper,
                           String prefix,
                           Duration baseTtl,
                           Duration jitter,
                           Class<V> valueType) {
        // L1 容量上限 1 万条；写入后最多保留 (baseTtl+jitter) 过期——既防击穿也防脏数据长期滞留。
        this.l1 = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(baseTtl.plus(jitter))
                .build();
        this.loader = loader;
        this.redisProvider = redisProvider;
        this.objectMapper = objectMapper;
        this.prefix = prefix;
        this.baseTtl = baseTtl;
        this.jitter = jitter;
        this.valueType = valueType;
    }

    /**
     * 读穿式读取：L1 → L2 → 回源，命中后回填。
     * 同一个 key 的并发回源由 Caffeine 的 get(key, mapping) 串行化（防击穿）。
     */
    public V get(K key) {
        // Caffeine 的 mapping 函数保证：同一 key 并发时只有一个线程执行 loadAndFill，其余线程阻塞等结果。
        Object cached = l1.get(key, this::loadAndFill);
        if (cached == NULL_SENTINEL_OBJ) {
            return null; // 命中"查无此物"占位符
        }
        @SuppressWarnings("unchecked")
        V value = (V) cached;
        return value;
    }

    /**
     * 真正去 L2 / 数据库取数并回填的逻辑（在 Caffeine 的 mapping 函数里执行，天然单飞）。
     */
    private Object loadAndFill(K key) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        String redisKey = prefix + key;

        // 1) 先问 L2（如果有 Redis）
        if (redis != null) {
            String json = redis.opsForValue().get(redisKey);
            if (json != null) {
                if (NULL_SENTINEL.equals(json)) {
                    return NULL_SENTINEL_OBJ; // 命中"查无此物"占位符 → 返回 null（防穿透）
                }
                try {
                    return objectMapper.readValue(json, valueType);
                } catch (Exception e) {
                    // 反序列化失败：当作没命中，继续回源（不缓存坏数据）
                    log.warn("L2 反序列化失败，回源：key={}, err={}", redisKey, e.getMessage());
                }
            }
        }

        // 2) 回源（通常是查 DB）
        V loaded = loader.apply(key);

        // 3) 回填 L2（带随机 TTL 防雪崩；null 也缓存成占位符防穿透）
        if (redis != null) {
            long jitterMs = jitter.toMillis() <= 0 ? 0 : ThreadLocalRandom.current().nextLong(0, jitter.toMillis());
            Duration ttl = baseTtl.plusMillis(jitterMs);
            String toStore = (loaded == null) ? NULL_SENTINEL : serialize(loaded);
            if (toStore != null) {
                redis.opsForValue().set(redisKey, toStore, ttl);
            }
        }
        // 返回给 Caffeine：null 用哨兵对象占位，非 null 直接返回（Caffeine 不接受 null）
        return (loaded == null) ? NULL_SENTINEL_OBJ : loaded;
    }

    /** 把值序列化成 JSON 字符串；失败返回 null（调用方据此放弃写 L2，不影响主流程）。 */
    private String serialize(V value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("L2 序列化失败，放弃缓存：{}", e.getMessage());
            return null;
        }
    }

    /** 主动失效（数据变更后调用，保证缓存与 DB 最终一致）。同时清 L1 和 L2。 */
    public void evict(K key) {
        l1.invalidate(key);
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis != null) {
            redis.delete(prefix + key);
        }
    }
}
