package com.insightforge.seckill.core.redis;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Redis 相关 Bean 的装配（Phase 3，仅"非 local" profile 生效）。
 *
 * 为什么用 @Profile("!local")？
 *   local profile 是"零依赖冒烟"模式：不装 Redis，Redisson/Redis 自动配置都被关掉，
 *   所以 StringRedisTemplate 这个 Bean 在 local 下根本不存在。如果 RedisStockService
 *   在 local 下也被创建，Spring 会因为拿不到 StringRedisTemplate 而启动失败。
 *   用 !local 把这一整套 Redis Bean 关在 local 之外，local 下 SeckillService 检测到
 *   RedisStockService 不存在，自动走 DB 兜底，互不干扰。
 *
 * 真正连 Redis 的地址在 application.yml（spring.data.redis.host=localhost:6379），
 * 由 docker-compose 起的 Redis 提供；本配置只负责"把脚本和库存服务装配成 Bean"。
 */
@Configuration
@Profile("!local")
public class RedisConfig {

    /**
     * 把 classpath 下的 seckill_deduct.lua 装配成 RedisScript<Long> Bean。
     * Spring 会在首次执行时把脚本 SHA 缓存起来，之后用 EVALSHA 调用，省去反复传脚本。
     */
    @Bean
    public RedisScript<Long> seckillDeductScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        // setLocation 让 Spring 从 classpath 读取 lua 脚本（Spring Data Redis 3.x 推荐写法）
        script.setLocation(new ClassPathResource("lua/seckill_deduct.lua"));
        script.setResultType(Long.class); // 脚本返回数字，反序列化成 Long
        return script;
    }

    /**
     * 库存服务 Bean：注入 Spring 自动配好的 StringRedisTemplate（来自 RedisAutoConfiguration）
     * 与我们刚定义的脚本 Bean。
     */
    @Bean
    public RedisStockService redisStockService(StringRedisTemplate stringRedisTemplate,
                                               @Qualifier("seckillDeductScript") RedisScript<Long> deductScript) {
        return new RedisStockService(stringRedisTemplate, deductScript);
    }

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
}
