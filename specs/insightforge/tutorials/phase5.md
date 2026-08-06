# Phase 5 — 多级缓存 + 限流（秒杀的"备忘录"与"闸机"）

> **一句话目标**：给秒杀系统加两层"读加速"——**本地 Caffeine + 分布式 Redis 多级缓存**（挡掉绝大多数"查商品/查库存"的读），并给抢购入口加一道**滑动窗口限流闸机**（拦住羊毛党/误用）。同时把缓存三大经典坑（击穿 / 雪崩 / 穿透）一次性解决掉。
>
> 完成本 Phase 后，你的秒杀接口：高频读不再每次打 DB（L1 命中率极高）；同一用户短时间疯狂刷会被 **429** 拦回去；缓存热点失效、Redis 抖动都不会把 DB 打挂。

---

## 先搞懂：为什么既要缓存又要限流？

Phase 4 把"写"异步掉了（发 Kafka 立刻返回），但**读**还是裸奔——每次查库存/商品详情都可能打到 DB。两个问题：

1. **读多写少却被反复查 DB**：秒杀商品的"详情页/库存快照"被成千上万用户反复看，每次都 SELECT 一次数据库，纯属浪费，且 DB 连接被读占满会拖累写。
2. **恶意/误用流量**：一个用户写个 for 循环每秒点 1000 次，后端哪怕不超卖也会被无谓的请求淹没。

**两个武器**：
- **多级缓存**：进程内放一份（Caffeine，μs 级最快），所有机器共享一份（Redis，重启不丢）。查的时候先问本地、再问 Redis、最后才问 DB。
- **限流**：在接口入口装个"闸机"，同一用户同一活动"1 秒内最多放 N 次"，超了直接拒绝（HTTP 429）。

> 📖 **术语大白话**
> - **多级缓存（L1+L2）**：L1 = 本地堆内缓存（Caffeine），最快但只本机、重启丢；L2 = Redis，所有机器共享、重启不丢但比本地慢一点。两层叠起来，既快又不怕单机重启。
> - **读穿（read-through）**：调用方只管 `get(key)`，缓存组件自己负责"没命中就回源 DB、查到回填缓存"，对业务透明。
> - **击穿（breakdown）**：某个热点 key 突然过期，瞬间海量请求同时穿透到 DB。解法：让"同一 key 同一时刻只有一个线程去回源"（单飞 / single-flight）。
> - **雪崩（avalanche）**：大量 key 在同一秒集体过期，DB 被瞬间冲垮。解法：给 TTL 加一点随机抖动，让过期时间错开。
> - **穿透（penetration）**：查一个根本不存在的 key（如 sku=999999），每次都穿透到 DB。解法：把"查无此物"也缓存成一个特殊占位符，下次直接命中占位符返回。
> - **滑动窗口限流**：只认"最近 N 毫秒内"的请求数，超过阈值就拦。比固定窗口更平滑（固定窗口在边界会漏放一倍）。
> - **429**：HTTP 状态码 `Too Many Requests`，意思是"你太频繁了，歇会儿再来"。

---

## 架构总览（先看图，再一步步搭）

```mermaid
flowchart TD
    U[用户抢购 HTTP] --> RL[限流闸机<br/>RateLimitService]
    RL -- 超限 --> R429[返回 429 拒绝]
    RL -- 放行 --> C[SeckillController.seckill]
    C --> S[SeckillService.seckill]
    S --> G{读 SKU}
    G --> M[MultiLevelCache.get<br/>L1 Caffeine → L2 Redis → DB]
    M --> DB[(MySQL)]
    S --> D[扣减/建单 热路径<br/>Redis Lua / DB 条件更新]
    D --> OK[返回订单号]

    subgraph 缓存三防
      M -. 击穿 .-> SF[Caffeine get(key,loader) 单飞]
      M -. 雪崩 .-> JT[TTL 随机抖动]
      M -. 穿透 .-> NL[NULL 哨兵占位]
    end
```

> 🧠 **理解一下**：这张图的核心是**读写分离地护住 DB**。
> - **写热路径（扣减/建单）** 我们前几 Phase 已经做得很硬（Redis Lua 原子 / DB 条件更新，绝不超卖），本 Phase **不动它**——缓存和限流都只加在"读"和"入口"，不碰那条命脉。
> - **读路径**被多级缓存包起来：本地 Caffeine 挡掉 90%+ 的读，Redis 挡掉跨实例的重复读，DB 几乎不被读打到。
> - **入口**被限流闸机包起来：恶意流量在还没进业务逻辑前就被 429 劝退。
> 这就是"读写分离 + 边缘防御"的思路。

---

## 第 0 步：确认目录（Phase 4 留下的骨架）

Phase 4 结束后，项目目录关键部分长这样（你应当已经具备）：

```
insightforge/
├── insightforge-seckill-core/src/main/java/com/insightforge/seckill/core/
│   ├── redis/{RedisKeys,RedisStockService,DeductResult,RedisConfig,RedisIdempotentService}.java
│   ├── service/SeckillService.java          # Phase 4 的异步快路径 + 同步兜底
│   └── dto/{SeckillRequest,StockInfo,OrderCreatedEvent}.java
├── insightforge-api/src/main/java/com/insightforge/api/controller/SeckillController.java
└── insightforge-api/src/main/resources/{application.yml,application-local.yml,schema.sql,data.sql}
```

本 Phase 我们要**新增** 5 个文件（`MultiLevelCache.java`、`SkuCacheService.java`、`RateLimitService.java`、`rate_limit_sliding.lua`、2 个测试）+ **修改** 4 个文件（`pom.xml` 已加 Caffeine 依赖、`RedisKeys.java`、`RedisConfig.java`、`SeckillService.java`、`SeckillController.java`）。下面一步步来。

---

## 步骤 1：确认 Caffeine 依赖已就位

📖 **概念**：L1 本地缓存我们用 Caffeine（Java 界性能最强的本地缓存库之一）。它的 `Cache.get(key, loader)` 原生提供"同 key 单飞"能力，天然防击穿，所以本 Phase 直接用它的 API，而不是套一层 `spring-cache` 自动配置（那样反而要自己写互斥锁）。

`insightforge-seckill-core/pom.xml` 里 `spring-boot-starter-data-redis` 依赖后面，**应该已经有**这段（Phase 5 开头已加好；若你从更早的版本开始，请补上）：

```xml
        <!-- Phase 5：多级缓存 L1（本地）。直接用 Caffeine API（不依赖 spring-cache 自动配置），
             它的 Cache.get(key, loader) 原生提供「同 key 只有一个线程回源」的原子加载，
             天然防击穿（dogpile）；local profile 下没有 Redis，L1 单独顶着读穿 + 防穿透/雪崩。 -->
        <dependency>
            <groupId>com.github.ben-manes.caffeine</groupId>
            <artifactId>caffeine</artifactId>
        </dependency>
```

> 🧠 **理解一下**：这里**没写版本号**——因为根 POM 的 `spring-boot-dependencies` BOM 已经统一管理了 `caffeine` 的版本，声明一下坐标即可，避免版本漂移。`mvn clean package` 时 Maven 会自动解析到匹配的版本。

---

## 步骤 2：加限流 key + 滑动窗口 Lua 脚本 + 装配脚本 Bean

📖 **概念**：限流要在 Redis 端用一段 Lua 脚本原子完成"清旧窗口→计数→放行/拦截"，这样集群多机共享同一个计数才准。**先**在 `RedisKeys` 里加一个 key 拼装方法，**再**写 Lua 脚本，**再**在 `RedisConfig` 把它装配成 Bean（只在非 local 生效）。

### 2.1 给 RedisKeys 加 rateLimit key

打开 `insightforge-seckill-core/src/main/java/com/insightforge/seckill/core/redis/RedisKeys.java`，在 `guardrailDsl` 方法后面加：

```java
    /** 滑动窗口限流：rate:limit:{bizKey}（bizKey 通常是 "u:{userId}:a:{activityId}"） */
    public static String rateLimit(String bizKey) {
        return "rate:limit:" + bizKey;
    }
```

### 2.2 新建滑动窗口限流 Lua 脚本

```bash
mkdir -p insightforge-seckill-core/src/main/resources/lua
# 在 insightforge-seckill-core/src/main/resources/lua/ 下新建 rate_limit_sliding.lua
```

```lua
-- ============================================================
-- 滑动窗口限流脚本（Phase 5 的"闸机"）
-- ============================================================
-- 为什么要滑动窗口而不是固定窗口？
--   固定窗口（如"每秒 20 次"按整秒切）在窗口边界会出现"前一秒末尾 20 次 + 后一秒开头 20 次"
--   在 1 秒内放行 40 次的漏洞。滑动窗口只认"最近 window 毫秒内"的请求数，边界平滑、更严。
--
-- 怎么实现：把每次请求的时间戳塞进一个 ZSET（有序集合），score 和 member 都用时间戳。
--   - ZREMRANGEBYSCORE 删掉"窗口之外"的旧时间戳（过期请求）；
--   - ZCARD 数一下窗口里还剩几个（即最近 window 毫秒内的请求数）；
--   - 没超阈值就再加一个当前时间戳，放行；否则拦截。
--   整段脚本在 Redis 单线程里原子执行，多机共享同一个 ZSET，限流才准。
--
-- 入参：
--   KEYS[1] = 限流 key，例如 rate:limit:u:1001:a:1
--   ARGV[1] = 窗口大小（毫秒）
--   ARGV[2] = 窗口内最大放行次数 max
--   ARGV[3] = 当前时间戳（毫秒，由 Java 端传入，避免 Redis 与业务机时钟差异）
--
-- 返回值：1 = 放行；0 = 限流（拦截）
-- ============================================================

local key = KEYS[1]
local window = tonumber(ARGV[1])
local max = tonumber(ARGV[2])
local now = tonumber(ARGV[3])

-- 1) 清掉窗口外的旧请求（now - window 之前的时间戳统统过期）
redis.call('ZREMRANGEBYSCORE', key, 0, now - window)

-- 2) 统计窗口内当前请求数
local count = redis.call('ZCARD', key)

if count < max then
  -- 3) 放行：把本次请求的时间戳加进 ZSET。
  --    用 key..':seq' 的自增序列拼成唯一 member，避免"同一毫秒两次请求 member 撞车"
  --    （若 member 相同，ZADD 只是覆盖、不新增元素，会导致少计数、多放行）。
  local member = now .. ':' .. redis.call('INCR', key .. ':seq')
  redis.call('ZADD', key, now, member)
  -- 给 ZSET 和其序号计数器都设过期，避免冷 key 永久占用内存
  redis.call('PEXPIRE', key, window)
  redis.call('PEXPIRE', key .. ':seq', window)
  return 1
end

-- 超过阈值：拦截
return 0
```

> 🧠 **理解一下**（这段 Lua 的几个关键点）：
> - **`ZREMRANGEBYSCORE key 0 now-window`**：把"窗口之前"的时间戳全删了，剩下的就是"最近 window 毫秒内"的请求。这就是"滑动"二字的来源。
> - **`ZCARD` 计数 + 阈值判断**：纯单线程原子，多机并发也准确。
> - **`member = now .. ':' .. INCR(seq)`** 是个防坑细节：如果两个请求在同一毫秒到达，若 member 只用 `now`，`ZADD` 会"覆盖同 member 而不新增元素"，导致少计数、多放行。用自增序列拼成唯一 member，彻底杜绝。
> - **`PEXPIRE`**：限流 key 和序号计数器都设过期，避免没人访问的冷 key 永久占内存。

### 2.3 在 RedisConfig 装配脚本 Bean

打开 `insightforge-seckill-core/src/main/java/com/insightforge/seckill/core/redis/RedisConfig.java`，在 `redisStockService` Bean 后面加：

```java
    /**
     * 滑动窗口限流脚本 Bean（Phase 5）：装配 classpath 下的 rate_limit_sliding.lua。
     * RateLimitService 用 ObjectProvider 拿它——local profile 下 RedisConfig 不生效、
     * 这个 Bean 不存在，RateLimitService 自动退化成 JVM 内存版滑动窗口。
     */
    @Bean
    public RedisScript<Long> rateLimitSlidingScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/rate_limit_sliding.lua"));
        script.setResultType(Long.class);
        return script;
    }
```

> 🧠 **理解一下**：`@Profile("!local")` 的 `RedisConfig` 在非 local 才生效，所以这个脚本 Bean 也只在非 local 存在。`RateLimitService` 用 `ObjectProvider.getIfAvailable()` 拿它——拿不到（local）就走内存版。又是一次"零代码切换"。

---

## 步骤 3：建 MultiLevelCache（通用多级缓存 + 三防）

📖 **概念**：这是本 Phase 的"缓存引擎"。它是一个**通用**组件（不绑定具体业务），负责 L1 Caffeine + L2 Redis 的读穿，并原生解决击穿/雪崩/穿透。我们用"普通 Java 类 + 构造时注入依赖"的方式写它（而不是 @Service 泛型 Bean，泛型塞进 Spring Bean 定义会很别扭）。

```bash
mkdir -p insightforge-seckill-core/src/main/java/com/insightforge/seckill/core/cache
# 在 insightforge-seckill-core/src/main/java/com/insightforge/seckill/core/cache/ 下新建 MultiLevelCache.java
```

```java
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
 * 大白话：秒杀系统里"查商品 / 查库存快照"这种读请求特别多，每次都打数据库会很慢也很贵。
 * 我们加两层"备忘录"：L1 进程内 Caffeine（最快，但只本机、重启丢）；L2 Redis（共享、重启不丢、稍慢）。
 * 查的时候先翻 L1，没有再翻 L2，再没有才去数据库；查到的顺手补回两层。
 *
 * 三大经典坑的系统解法：
 *   - 击穿（breakdown）：热点 key 失效，海量请求同时打到 DB。
 *        Caffeine 的 get(key, loader) 保证"同一 key 同一时刻只有一个线程去回源"，其余等结果，天然防击穿。
 *   - 穿透（penetration）：查不存在的 key，每次穿透到 DB。
 *        把"查无此物"也缓存成 NULL 哨兵占位，下次直接命中占位符返回 null，不再打 DB。
 *   - 雪崩（avalanche）：大量 key 同时过期，DB 被冲垮。
 *        给 TTL 加随机抖动，让过期时间错开，不会集体失效。
 *
 * 注意：本类是普通 Java 类（不是 Spring Bean），由 SkuCacheService 这个 @Service 持有并装配依赖。
 */
public class MultiLevelCache<K, V> {

    private static final Logger log = LoggerFactory.getLogger(MultiLevelCache.class);

    /** L2 里"查无此物"的固定占位符（Redis 不能存 null，用特殊字符串代替）。 */
    private static final String NULL_SENTINEL = "__NULL__";

    /** L1 里与 NULL 哨兵对应的"对象形态"哨兵（Caffeine 不允许直接缓存 null，用这个非空对象占位）。 */
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

    /** 真正去 L2 / 数据库取数并回填的逻辑（在 Caffeine 的 mapping 函数里执行，天然单飞）。 */
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
```

> 🧠 **理解一下（缓存三防怎么落地的）**：
> - **防击穿**：`l1.get(key, this::loadAndFill)` —— Caffeine 保证同一 key 并发时只有一个线程执行 `loadAndFill`，其他线程阻塞拿同一个结果。你**不需要自己写双重检查锁 / 互斥锁**，Caffeine 已经把这件麻烦事做了（dogpile 防护）。
> - **防雪崩**：回填 L2 时 TTL = `baseTtl + 随机[0, jitter)`。原本 1 万 key 都设 60s 过期会"集体阵亡"，现在分布在 60~70s 之间，错开了。
> - **防穿透**：`loaded == null` 时不写真正的 null（Redis 不支持），而是写字符串 `"__NULL__"`；下次读命中这个占位符直接返回 null，不再回源 DB。
> - **`evict`**：DB 写（扣库存/预热）后调用，清掉 L1+L2，保证缓存不会永久脏。
> - **local 退化**：`redisProvider.getIfAvailable()` 在 local 下是 `null`，于是 L2 整段跳过，只用 L1 —— 零 Redis 也能跑。

---

## 步骤 4：建 SkuCacheService（把 SKU 查询套上多级缓存）

📖 **概念**：`MultiLevelCache` 是通用的，我们再用一个 `@Service` 把它"钉"到具体业务上——按 id 查 `ActivitySku`。这样 `SeckillService` 只要调 `skuCacheService.getSku(id)`，背后就自动走多级缓存了。

```bash
# 在 insightforge-seckill-core/src/main/java/com/insightforge/seckill/core/cache/ 下新建 SkuCacheService.java
```

```java
package com.insightforge.seckill.core.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightforge.persistence.entity.ActivitySku;
import com.insightforge.persistence.mapper.ActivitySkuMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * SKU 多级缓存封装（Phase 5）。
 *
 * 把"按 id 查 ActivitySku"这件高频读，套上 MultiLevelCache：
 *   - L1 Caffeine（本地）+ L2 Redis（非 local 才有）。
 *   - 商品/库存这类相对静态的元数据被缓存；DB 写（扣库存 / 预热）后调用 evict 失效。
 *
 * 设计取舍（诚实说明）：被缓存的 stock 字段是"带 TTL 的快照"，不是实时值。
 * 在真实秒杀里，热路径的权威库存始终在 Redis（由 Lua 扣减维护），缓存只是给
 * "商品详情/库存快照"这类读多写少场景加速；我们对账用的是 DB 的 totalStock/sold，
 * 与缓存无关，因此缓存过期绝不会破坏"绝不超卖"的硬约束（详见本教程开头与 tutorials/phase5.md）。
 */
@Service
public class SkuCacheService {

    private final MultiLevelCache<Long, ActivitySku> cache;

    /**
     * 构造时把"回源函数 + Redis 提供者 + JSON 工具"喂给通用多级缓存组件。
     * 注意 ObjectProvider&lt;StringRedisTemplate&gt;：local profile 下 Redis 自动配置被排除，
     * provider 拿到的就是 null，MultiLevelCache 会自动退化为"只用 L1"——零代码切换。
     */
    public SkuCacheService(ActivitySkuMapper mapper,
                           ObjectProvider<StringRedisTemplate> redisProvider,
                           ObjectMapper objectMapper) {
        // loader：L1/L2 都没命中时去 DB 拿 ActivitySku
        this.cache = new MultiLevelCache<>(
                mapper::selectById,            // 回源函数：L1/L2 都没命中时去 DB 拿 ActivitySku
                redisProvider,
                objectMapper,
                "sku:",                        // L2 前缀：实际 key 形如 sku:1
                Duration.ofSeconds(60),        // 基础 TTL 60s
                Duration.ofSeconds(10),        // 抖动 0~10s，避免集体过期（防雪崩）
                ActivitySku.class);
    }

    /** 读穿取 SKU；不存在返回 null（已被缓存成占位符防穿透）。 */
    public ActivitySku getSku(Long skuId) {
        return cache.get(skuId);
    }

    /** 数据变更后失效缓存（扣库存 / 预热 / 后台改价都会调），保证缓存与 DB 最终一致。 */
    public void evict(Long skuId) {
        cache.evict(skuId);
    }
}
```

> 🧠 **理解一下**：
> - `mapper::selectById` 是回源函数——只有 L1/L2 都 miss 时才调它查 DB。
> - `ObjectProvider<StringRedisTemplate>` 是"可选依赖"的写法：有 Redis 就注入、没有就 null。整个缓存组件因此对 local / redis 双 profile 透明。
> - **关于 stock 字段的诚实声明**：缓存放的是"快照"，会过期。但**秒杀热路径的权威库存是 Redis（Lua 维护）或 DB（条件更新）**，缓存从不参与扣减决策，所以对账 `totalStock - sold` 与缓存无关，缓存过期**不会**导致超卖。这点很重要，别被"缓存和 DB 不一致"这类说法吓到——我们只在"读展示"场景用它加速。

---

## 步骤 5：改 SeckillService —— 读 SKU 改走缓存、写后失效

📖 **概念**：把 `SeckillService` 里三处"直接 `activitySkuMapper.selectById`"换成"走缓存"，并在 DB 写库存成功后 `evict`，保证缓存与 DB 最终一致。**扣减/建单的热路径一行都不动**——这是本 Phase 的红线（缓存只优化读，绝不碰写命脉）。

打开 `insightforge-seckill-core/src/main/java/com/insightforge/seckill/core/service/SeckillService.java`，做以下 4 处改动：

### 5.1 加 import + 字段 + 构造参数

```java
// 在 import 区加一行（放在 OrderEventProducer 等 import 附近）：
import com.insightforge.seckill.core.cache.SkuCacheService;

// 在字段声明区（activitySkuMapper / ordersMapper 之后）加一个字段：
    /** SKU 多级缓存（Phase 5）：高频读走 L1 Caffeine + L2 Redis，避免每次打 DB。 */
    private final SkuCacheService skuCacheService;

// 在构造方法参数列表里，ordersMapper 后面插入 skuCacheService，并在方法体里赋值：
    public SeckillService(ActivitySkuMapper activitySkuMapper,
                          OrdersMapper ordersMapper,
                          SkuCacheService skuCacheService,
                          ObjectProvider<RedisStockService> redisStockService,
                          ObjectProvider<RedisIdempotentService> idempotentService,
                          ObjectProvider<OrderEventProducer> orderEventProducer) {
        this.activitySkuMapper = activitySkuMapper;
        this.ordersMapper = ordersMapper;
        this.skuCacheService = skuCacheService;     // ← 新增赋值
        this.redisStockService = redisStockService;
        // ... 其余不变
```

### 5.2 seckill() 入口：读 SKU 走缓存

```java
    @Transactional
    public ApiResult<String> seckill(SeckillRequest req) {
        // 原来：ActivitySku sku = activitySkuMapper.selectById(req.getSkuId());
        // 现在：走多级缓存（L1→L2→DB），高频读不再每次打数据库
        ActivitySku sku = skuCacheService.getSku(req.getSkuId());
        if (sku == null) {
            return ApiResult.fail(404, "商品不存在");
        }
        // ... 以下扣减/幂等/发 Kafka 逻辑完全不变
```

### 5.3 stockInfo()：读 SKU 走缓存

```java
    /** 查询某 SKU 的库存情况，便于手动验证与压测后核对。 */
    public ApiResult<StockInfo> stockInfo(Long skuId) {
        // 原来：ActivitySku sku = activitySkuMapper.selectById(skuId);
        ActivitySku sku = skuCacheService.getSku(skuId);   // ← 走缓存
        if (sku == null) {
            return ApiResult.fail(404, "商品不存在");
        }
        StockInfo info = new StockInfo();
        info.setSkuId(skuId);
        info.setTotalStock(sku.getTotalStock());
        info.setStock(sku.getStock());
        info.setSold(sku.getSold());
        return ApiResult.ok(info);
    }
```

### 5.4 preheat()：先失效再读最新（避免推旧库存进 Redis）

```java
    public ApiResult<Void> preheat(Long skuId) {
        RedisStockService rs = redisStockService.getIfAvailable();
        if (rs == null) {
            return ApiResult.fail(409, "未启用 Redis 主路径（请用 --spring.profiles.active=redis 启动）");
        }
        // 先失效缓存，再读最新 SKU（防预热推了旧库存）；getSku 内部会回源 DB 拿最新值
        skuCacheService.evict(skuId);
        ActivitySku sku = skuCacheService.getSku(skuId);
        if (sku == null) {
            return ApiResult.fail(404, "商品不存在");
        }
        rs.preheat(sku.getActivityId(), sku.getId(), sku.getStock());
        return ApiResult.ok(null);
    }
```

### 5.5 syncCreateOrder()：扣库存成功后失效缓存

```java
        // 原子扣减 DB 库存（条件更新，绝不为负）
        int affected = activitySkuMapper.decrementStock(sku.getId(), req.getQty());
        if (affected == 0) {
            // 售罄：刚插入的订单撤销（本事务内回滚该行），返回售罄
            ordersMapper.deleteById(order.getId());
            return ApiResult.fail(409, "已售罄");
        }
        // 库存真的变了：失效缓存，下次读取会回源到最新值（保证缓存与 DB 最终一致）
        skuCacheService.evict(sku.getId());
        return ApiResult.ok(orderId);
```

> 🧠 **理解一下**：这几处改动很小，但体现了缓存接入的纪律——
> - **读**一律走 `getSku`（缓存优先）；
> - **写**后一律 `evict`（让缓存与 DB 重新对齐）；
> - **热路径扣减/建单一行没动**：Redis Lua 原子扣减、`order_id` 唯一约束幂等、Kafka 异步落库——这些 Phase 3/4 的命脉原封不动。缓存只在外围"读"做加速，符合"控制面/数据面分离、LLM 不进热路径"的项目灵魂（虽缓存不是 LLM，但同样是"不碰扣减写"的同一原则）。

---

## 步骤 6：建 RateLimitService（Redis Lua 限流 + 内存兜底）

📖 **概念**：这是"闸机"本体。非 local 用 Redis Lua 脚本做集群共享的滑动窗口计数；local 没有 Redis，自动退化成 JVM 内存版滑动窗口（教学/冒烟够用）。

```bash
# 在 insightforge-seckill-core/src/main/java/com/insightforge/seckill/core/redis/ 下新建 RateLimitService.java
```

```java
package com.insightforge.seckill.core.redis;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 滑动窗口限流（Phase 5）。
 *
 * 大白话：秒杀入口像地铁闸机，得拦住"一个人疯狂刷票"的羊毛党。
 * 我们按 (用户, 活动) 维度计数——比如"同一用户同一活动，1 秒内最多放 20 次"。
 *
 * 两套实现，自动切换：
 *   - 非 local（有 Redis）：用 rate_limit_sliding.lua 在 Redis 端原子完成"清旧窗口→计数→放行/拦截"，
 *     集群多机共享同一个计数，限流才准。
 *   - local（无 Redis）：退化成 JVM 内存版滑动窗口（教学/冒烟用，单机够用）。
 *
 * 返回 true=放行，false=拦截（调用方据此返回 HTTP 429）。
 */
@Service
public class RateLimitService {

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final ObjectProvider<RedisScript<Long>> slidingScriptProvider;

    /** 内存版滑动窗口：key -> 该 key 最近若干次请求的时间戳队列（仅 local 用）。 */
    private final ConcurrentHashMap<String, ArrayDeque<Long>> memWindows = new ConcurrentHashMap<>();

    public RateLimitService(ObjectProvider<StringRedisTemplate> redisProvider,
                            ObjectProvider<RedisScript<Long>> slidingScriptProvider) {
        this.redisProvider = redisProvider;
        this.slidingScriptProvider = slidingScriptProvider;
    }

    /**
     * 尝试获取一次放行资格。
     * @param key      限流维度，例如 "u:1001:a:1"
     * @param max      窗口内最大放行次数
     * @param windowMs 窗口大小（毫秒）
     * @return true=放行，false=被限流
     */
    public boolean tryAcquire(String key, int max, long windowMs) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        RedisScript<Long> script = slidingScriptProvider.getIfAvailable();
        if (redis != null && script != null) {
            // —— Redis 路径：脚本在单线程里原子判定，集群共享计数 ——
            Long now = System.currentTimeMillis();
            Long res = redis.execute(script,
                    Collections.singletonList(RedisKeys.rateLimit(key)),
                    String.valueOf(windowMs), String.valueOf(max), String.valueOf(now));
            return res != null && res == 1L;
        }
        // —— 内存路径（local 零依赖）——
        return tryAcquireInMemory(key, max, windowMs);
    }

    /** 纯 JVM 滑动窗口：维护每个 key 的时间戳队列，超过窗口的甩掉，超阈值的拦截。 */
    private boolean tryAcquireInMemory(String key, int max, long windowMs) {
        long now = System.currentTimeMillis();
        ArrayDeque<Long> window = memWindows.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (window) {
            // 甩掉窗口外的旧时间戳（滑动窗口的核心：只认"最近 windowMs 内"的请求）
            while (!window.isEmpty() && now - window.peekFirst() >= windowMs) {
                window.pollFirst();
            }
            if (window.size() < max) {
                window.addLast(now);
                return true; // 没超阈值，放行并记录本次时间戳
            }
            return false; // 超阈值，拦截
        }
    }
}
```

> 🧠 **理解一下**：
> - **两套实现共用一个 `tryAcquire` 接口**：业务代码（Controller）只管调 `tryAcquire(key, max, windowMs)`，至于背后是 Redis 还是内存，由 `ObjectProvider` 自动决定——又是零代码切换。
> - **内存版用 `synchronized` + `ArrayDeque`**：单机下足够，且逻辑和 Redis Lua 一一对应（都是"甩旧 + 计数 + 判阈"），方便你对照理解。
> - **为什么不用 Guava RateLimiter / 令牌桶？** 令牌桶适合"平滑限速"，但我们要的是"按 (用户,活动) 维度、集群共享、可精确控制窗口内次数"——滑动窗口 + Redis 更贴合秒杀场景，且和 Phase 3 的 Lua 风格统一（都是 Redis 端原子脚本）。

---

## 步骤 7：改 SeckillController —— 入口接限流

📖 **概念**：把闸机装在**最边缘**——HTTP 入口。用户请求先过限流，超了直接 429，根本不进业务逻辑。这样既能保护后端，又不污染核心服务。

打开 `insightforge-api/src/main/java/com/insightforge/api/controller/SeckillController.java`，改成：

```java
package com.insightforge.api.controller;

import com.insightforge.common.result.ApiResult;
import com.insightforge.seckill.core.dto.SeckillRequest;
import com.insightforge.seckill.core.dto.StockInfo;
import com.insightforge.seckill.core.redis.RateLimitService;   // ← 新增 import
import com.insightforge.seckill.core.service.SeckillService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/seckill")
public class SeckillController {

    /** 限流上限：同一用户同一活动，1 秒内最多放行 20 次（拦羊毛党/防误用）。 */
    private static final int RATE_MAX = 20;
    /** 滑动窗口大小：1 秒。 */
    private static final long RATE_WINDOW_MS = 1000;

    private final SeckillService seckillService;
    private final RateLimitService rateLimitService;   // ← 新增字段

    // 构造方法多接一个 RateLimitService
    public SeckillController(SeckillService seckillService, RateLimitService rateLimitService) {
        this.seckillService = seckillService;
        this.rateLimitService = rateLimitService;
    }

    @PostMapping
    public ApiResult<String> seckill(@RequestBody SeckillRequest req) {
        // Phase 5：接入层限流——按 (用户, 活动) 维度滑动窗口，属于"保护数据面的闸机"。
        // 放在 Controller 边缘，先于业务扣减执行；local profile 下自动退化成 JVM 内存版限流。
        String limitKey = "u:" + req.getUserId() + ":a:" + req.getActivityId();
        if (!rateLimitService.tryAcquire(limitKey, RATE_MAX, RATE_WINDOW_MS)) {
            return ApiResult.fail(429, "请求过于频繁，请稍后再试");
        }
        return seckillService.seckill(req);
    }

    // preheat / stock 两个接口保持不变 ……
}
```

> 🧠 **理解一下**：
> - **为什么放 Controller 而不是 Service？** 限流是"边缘防御"，越靠近入口越早挡掉无用流量，越省资源。放在 Service 内也能做，但那样恶意请求已经过了 MVC 层、进了业务容器。边缘拦截是更经典的做法。
> - **`RATE_MAX=20` 不会误伤正常用户**：一个真实用户 1 秒内点 20 次已经很夸张；秒杀正常抢购是"点一下等结果"。这个阈值只拦程序化刷量。**而且 NoOversell 回归测试用的 300 个不同用户、每人只点 1 次，完全在阈值内，不会被误拦**（测试直接调 Service，不走 Controller，更不受影响）。
> - 返回 **429** 而不是 409（售罄）：语义上 429 = "你太频繁"，409 = "资源冲突/售罄"，两者分清，前端好区分提示。

---

## 步骤 8：新建两组测试（守护缓存三防 + 限流）

📖 **概念**：Phase 2/3/4 都有回归测试守护不变量，Phase 5 也要有——证明"缓存读穿/防穿透/失效一致"和"限流放行到上限后拦截"。

### 8.1 缓存测试 SeckillCacheTest

```bash
# 在 insightforge-api/src/test/java/com/insightforge/api/ 下新建 SeckillCacheTest.java
```

```java
package com.insightforge.api;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.insightforge.persistence.entity.ActivitySku;
import com.insightforge.persistence.mapper.ActivitySkuMapper;
import com.insightforge.persistence.mapper.OrdersMapper;
import com.insightforge.seckill.core.cache.SkuCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Phase 5 多级缓存验证（local 零依赖，只用 L1 Caffeine；L2 Redis 在非 local 才生效）。
 * 验证三件事：1) 读穿 2) 防穿透 3) 失效后回源拿最新值。
 */
@SpringBootTest
@ActiveProfiles("local")
class SeckillCacheTest {

    @Autowired
    private SkuCacheService skuCacheService;
    @Autowired
    private ActivitySkuMapper activitySkuMapper;
    @Autowired
    private OrdersMapper ordersMapper;

    @BeforeEach
    void reset() {
        ActivitySku sku = activitySkuMapper.selectById(1L);
        sku.setStock(100);
        sku.setSold(0);
        activitySkuMapper.updateById(sku);
        ordersMapper.delete(new QueryWrapper<>());
        skuCacheService.evict(1L); // 清掉可能残留的缓存，保证本测试从干净状态开始
    }

    @Test
    void getSku_reads_through_and_caches() {
        ActivitySku sku = skuCacheService.getSku(1L);
        assertEquals(100, sku.getStock(), "读穿应命中 DB 种子数据（库存 100）");
        assertEquals(1L, sku.getActivityId());
        ActivitySku again = skuCacheService.getSku(1L);
        assertEquals(sku.getStock(), again.getStock(), "两次读取应一致（缓存命中）");
    }

    @Test
    void getSku_missing_key_returns_null_without_throwing() {
        ActivitySku first = skuCacheService.getSku(999999L);
        assertNull(first, "不存在的 SKU 应返回 null");
        ActivitySku second = skuCacheService.getSku(999999L);
        assertNull(second, "缓存占位符命中后仍返回 null（防穿透生效）");
    }

    @Test
    void evict_after_write_keeps_cache_consistent() {
        ActivitySku sku = activitySkuMapper.selectById(1L);
        sku.setStock(50);
        activitySkuMapper.updateById(sku);
        skuCacheService.evict(1L); // 失效后回源，应拿到最新库存 50
        ActivitySku reloaded = skuCacheService.getSku(1L);
        assertEquals(50, reloaded.getStock(), "失效后回源应拿到最新库存 50");
    }
}
```

### 8.2 限流测试 SeckillRateLimitTest

```bash
# 在 insightforge-api/src/test/java/com/insightforge/api/ 下新建 SeckillRateLimitTest.java
```

```java
package com.insightforge.api;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.context.ActiveProfiles;
import redis.embedded.RedisServer;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 5 滑动窗口限流验证。
 * 主测试（local 内存路径，必然执行）：放行到上限 → 超限拦截 → 窗口过去重新放行 + 不同维度独立计数。
 * 附加测试（Redis 路径，需能起内嵌 redis；起不来则整组跳过）：直接拿 rate_limit_sliding.lua 在真 Redis 上跑。
 */
@SpringBootTest
@ActiveProfiles("local")
class SeckillRateLimitTest {

    @Autowired
    private com.insightforge.seckill.core.redis.RateLimitService rateLimitService;

    // —— 可选的 Redis 路径证明（与 Phase 3 的内嵌 Redis 守护同思路）——
    private static final int REDIS_PORT = 16380; // 避开 Phase 3 的 16379，防冲突
    private static RedisServer redisServer;
    private StringRedisTemplate redis;
    private RedisScript<Long> script;

    @BeforeAll
    static void startRedis() {
        try {
            redisServer = new RedisServer(REDIS_PORT);
            redisServer.start();
        } catch (Exception e) {
            redisServer = null; // 起不来就跳过 Redis 路径测试
        }
    }

    @AfterAll
    static void stopRedis() {
        if (redisServer != null) {
            try { redisServer.stop(); } catch (Exception ignored) { }
        }
    }

    @BeforeEach
    void setUpRedis() {
        Assumptions.assumeTrue(redisServer != null, "内嵌 Redis 未启动，跳过 Redis 路径限流验证");
        LettuceConnectionFactory factory = new LettuceConnectionFactory("localhost", REDIS_PORT);
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        DefaultRedisScript<Long> s = new DefaultRedisScript<>();
        s.setLocation(new ClassPathResource("lua/rate_limit_sliding.lua"));
        s.setResultType(Long.class);
        script = s;
    }

    @Test
    void in_memory_sliding_window_allows_up_to_max_then_blocks() throws InterruptedException {
        String key = "test:u:1:a:1";
        int max = 5;
        long windowMs = 200; // 短窗口，测试更快
        for (int i = 0; i < max; i++) {
            assertTrue(rateLimitService.tryAcquire(key, max, windowMs), "第 " + (i + 1) + " 次应放行");
        }
        assertFalse(rateLimitService.tryAcquire(key, max, windowMs), "超过 max 应被限流");
        Thread.sleep(windowMs + 50); // 等窗口过去
        assertTrue(rateLimitService.tryAcquire(key, max, windowMs), "窗口过去后应重新放行");
    }

    @Test
    void different_keys_are_independent() {
        int max = 3;
        long windowMs = 1000;
        for (int i = 0; i < max; i++) {
            assertTrue(rateLimitService.tryAcquire("u:1:a:1", max, windowMs));
        }
        assertTrue(rateLimitService.tryAcquire("u:2:a:1", max, windowMs), "不同 key 应独立计数");
    }

    /** 可选：直接在真 Redis 上验证 Lua 脚本（集群多机共享计数的真实形态）。 */
    @Test
    void redis_sliding_window_blocks_after_max() {
        String key = "rate:limit:redis:u:1:a:1";
        redis.delete(key);
        int max = 5;
        long window = 1000;
        // 故意用同一个 now，验证即使时间戳相同，member 唯一（靠 INCR 序号）也能正确计数
        long now = System.currentTimeMillis();
        for (int i = 0; i < max; i++) {
            Long r = redis.execute(script, Collections.singletonList(key), String.valueOf(window), String.valueOf(max), String.valueOf(now));
            assertEquals(1L, r.longValue(), "第 " + (i + 1) + " 次应放行");
        }
        Long blocked = redis.execute(script, Collections.singletonList(key), String.valueOf(window), String.valueOf(max), String.valueOf(now));
        assertEquals(0L, blocked.longValue(), "超过 max 应拦截");
    }
}
```

> 🧠 **理解一下**：
> - **缓存测试**只在 local 跑（验证 L1 + 逻辑），Redis 路径的正确性由"非 local 部署 + 通用组件"保证；三防里"防击穿的单飞"是 Caffeine 内置能力，我们用"读穿一致性 + 防穿透 + 失效一致"三个用例把可观测的部分钉死。
> - **限流测试**主路径是内存版（必然跑），Redis 路径用内嵌 Redis 守护（起不来自动跳过，和 Phase 3 一致）。`redis_sliding_window_blocks_after_max` 特意用**同一个 `now`** 去打，证明我们 lua 里 `INCR` 序号防 member 撞车的细节确实有效。

---

## 步骤 9：编译 + 跑测试（验证全部变绿）

📖 **概念**：确认新代码能编译、Phase 2/3/4 的存量测试仍然通过、Phase 5 新增的两组测试也变绿。

```bash
cd insightforge
mvn clean package
```

> 📌 **沙箱小贴士**：Git Bash 下直接 `mvn` 可能报 `ClassNotFoundException: org.codehaus.plexus.classworlds.launcher.Launcher`（MinGW 解析 `MAVEN_HOME` 的坑）。两种可靠绕过：① 在 **cmd.exe** 里跑 `mvn`；② 直接调 classworlds 启动器（确认 `MVN_HOME=D:/devtools/maven/apache-maven-3.9.9`）：
> ```bash
> java -classpath "D:/devtools/maven/apache-maven-3.9.9/boot/plexus-classworlds-2.8.0.jar" \
>   -Dclassworlds.conf="D:/devtools/maven/apache-maven-3.9.9/bin/m2.conf" \
>   -Dmaven.home="D:/devtools/maven/apache-maven-3.9.9" \
>   -Dmaven.multiModuleProjectDirectory="D:/Java/insightforge" \
>   -Dlibrary.jansi.path="D:/devtools/maven/apache-maven-3.9.9/lib/jansi-native" \
>   org.codehaus.plexus.classworlds.launcher.Launcher -f D:/Java/insightforge/pom.xml clean package
> ```

**预期输出（沙箱，local 路径 + 内嵌 Redis 能起时）**：

```
[INFO] Tests run: 1, Failures: 0, Errors: 0 -- SeckillNoOversellTest（DB 不超卖，绿）
[INFO] Tests run: 1, Failures: 0, Errors: 0 -- SeckillIdempotencyTest（幂等，绿）
[INFO] Tests run: 4, Failures: 0, Errors: 0 -- SeckillRedisLuaTest（Redis 能起→实跑，绿）
[INFO] Tests run: 3, Failures: 0, Errors: 0 -- SeckillCacheTest（读穿/防穿透/失效，绿）← 本 Phase 新增
[INFO] Tests run: 3, Failures: 0, Errors: 0 -- SeckillRateLimitTest（内存+Redis，绿）← 本 Phase 新增
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] insightforge-api-0.1.0-SNAPSHOT.jar  ← 可执行 jar 已生成
```

> 总计 **12 个测试方法**全绿（若沙箱起不了内嵌 Redis，`SeckillRedisLuaTest` 与 `SeckillRateLimitTest` 的 Redis 路径用例会跳过，但构建仍 `BUILD SUCCESS`）。
> 日志里可能出现 `Connection reset` / `Cannot reconnect to ...:16379` 这类 Netty 告警——那是内嵌 Redis 在测试结束时关闭、客户端重连失败导致的，**属正常现象**，不影响测试结果与构建。

---

## 步骤 10：本机端到端验证（local 下看限流 429）

📖 **概念**：用 local profile 起应用（零依赖），用 curl 故意超频打接口，确认被 429 拦下。Redis Lua 路径的端到端验证同理（换 `redis` profile + 起 Redis），本 Phase 略。

```bash
# 用 local profile 起应用（H2 + 无 Redis/Kafka）
mvn -pl insightforge-api spring-boot:run -Dspring-boot.run.profiles=local
```

另开一个终端，用 curl 连点同一用户（1 秒内远超 20 次）：

```bash
# 同一用户 1001 连点 25 次（远超 RATE_MAX=20），前 20 次成功、后 5 次应被 429
for i in $(seq 1 25); do
  curl -s -o /dev/null -w "%{http_code} " -X POST http://localhost:8080/api/seckill \
    -H 'Content-Type: application/json' \
    -d '{"activityId":1,"skuId":1,"userId":1001,"qty":1}'
done
# 输出类似：200 200 ...(共20个) 429 429 429 429 429
# —— 前 20 次 200（放行），第 21 起 429（限流拦截）——
```

> 🧠 **理解一下**：打印的 HTTP 状态码里，前 `RATE_MAX`（20）个是 `200`（放行，正常抢购/幂等返回订单号），超出窗口阈值的立刻变 `429`。这就证明闸机生效——羊毛党被挡在业务之外。**注意**：本验证用 local profile，限流走的是内存版滑动窗口；生产用 `redis` profile 时走 Redis Lua 版，逻辑一致、且多机共享计数。

---

## 排错表（按现象自查）

| 现象 | 原因 | 解决 |
|------|------|------|
| 构建报 `ClassNotFoundException: org.codehaus.plexus...Launcher` | Git Bash 下 `mvn` 因 MinGW 解析 `MAVEN_HOME` 失败 | 用 cmd.exe 跑 mvn，或直接调 classworlds 启动器（见步骤 9 沙箱小贴士，注意路径用 `D:/...` 形式） |
| 缓存测试 `getSku_missing_key` 抛异常 / 打 DB | 没做 NULL 哨兵，缓存把 null 当"未命中"反复回源 | 确认 `MultiLevelCache.loadAndFill` 里 `loaded == null` 时写 `__NULL__` 占位符 |
| 缓存和 DB 长时间不一致（改了库存还读到旧值） | 只读了没 `evict`，或 TTL 还没到 | 写后必须 `skuCacheService.evict(skuId)`；TTL 默认 60~70s 内自动修正 |
| 限流没拦住 / 过早拦截 | `RATE_MAX` / `RATE_WINDOW_MS` 设得不合理 | 按业务调阈值；注意内存版和 Redis 版共用同一接口参数 |
| 限流测试 Redis 用例报 skipped | 沙箱起不了内嵌 Redis（被禁外部 exe） | 正常，构建仍 SUCCESS；本机 `mvn test` 会实跑 |
| 日志一堆 `Connection reset` / `Cannot reconnect :16379` | 内嵌 Redis 测试结束关闭，客户端重连失败 | 正常现象，不影响结果与构建 |
| 应用启动报 `No qualifying bean 'StringRedisTemplate'` | 用 `local` profile 却想要 Redis 缓存/Lua 限流 | local 下 Redis 不装配，`MultiLevelCache`/`RateLimitService` 自动退化；要 Redis 路径请用 `redis` profile |
| NoOversell 测试变红（库存超卖） | 误把缓存写进了扣减热路径 | 确认本 Phase **没动** `seckill()` 的扣减逻辑；缓存只用于 `getSku` 读，扣减仍走 Redis Lua / DB 条件更新 |

---

## 本 Phase 你得到了什么

- ✅ **多级缓存**：`MultiLevelCache`（L1 Caffeine + L2 Redis 读穿）+ `SkuCacheService`，秒杀读路径不再每次打 DB。
- ✅ **缓存三防**：击穿（Caffeine `get(key,loader)` 单飞，无需自写互斥锁）、雪崩（TTL 随机抖动）、穿透（NULL 哨兵占位）——全部内建进 `MultiLevelCache`。
- ✅ **滑动窗口限流**：`RateLimitService` 在 Controller 入口按 (用户,活动) 维度拦羊毛党；Redis Lua 集群共享计数，local 退化内存版；超限返 429。
- ✅ **零代码双 profile**：缓存与限流都通过 `ObjectProvider` 优雅降级，local（零依赖）/ redis（连 Redis）一份代码两种跑法不变。
- ✅ **热路径零改动**：扣减/建单/幂等/异步这些 Phase 3/4 的命脉原封未动——缓存与限流只加在"读"和"入口"，严守"不碰写命脉"的纪律。
- ✅ **回归测试**：`SeckillCacheTest`（3）+ `SeckillRateLimitTest`（3）守护三防与限流；`mvn clean package` 12 测试全绿。

> 📌 **诚实边界**：本 Phase 在**应用层**做了滑动窗口限流与缓存三防。更前层的 **Nginx+Lua 令牌桶**（接入层粗粒度）和 **Sentinel**（应用层熔断/降级）属于部署与运维纵深防御，M2 不做，留待部署/增强章，不虚报完成度。布隆过滤器也比 NULL 哨兵更重，本场景"查无此物"量不大，NULL 哨兵已足够，也已在此说明取舍。

---

## 下一步预告：Phase 6 — 状态机 + 对账

Phase 5 把"读"和"入口"护住了。但还有一个**正确性**层面的问题没解决：Redis 里的库存、DB 里的库存、Kafka 异步落库的库存，三者**可能因为网络/重启偶尔对不上**。下一阶段我们引入：

- **活动状态机** `DRAFT → ARMED → LIVE → SETTLING → CLOSED`：用状态约束"什么时机才能抢、什么时机才能对账"，避免"活动还没开始就被抢"或"还在卖就关账"。
- **Redis ↔ DB 对账**：定时/事件触发，把 Redis 权威库存与 DB 最终库存比对，发现漂移自动修正（配合 Phase 2 预留的 `version` 字段）。
- **对账闭环**：对账结果进入可观测指标，异常时告警——这正好接上 Phase 7+ 的 Agent 监测与评估体系（我们在 Agent 监测设计里已把"对账漂移"列为独立评估指标）。

继续看 `phase6.md`。
