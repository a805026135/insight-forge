package com.insightforge.agent.core;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * Agent Core 测试专用 Spring 上下文。
 * - 扫描自研运行时包（com.insightforge.agent.core）装载所有 @Component Bean；
 * - 扫描持久层 Mapper（com.insightforge.persistence.mapper）装载 agent_trace/agent_span/orders 等 Mapper；
 * - 启用自动配置：H2 数据源 + MyBatis-Plus + SQL 初始化（schema-agent.sql / data-agent.sql）。
 * 数据源与 SQL 初始化在 application-test.yml 中指定（独立 H2 内存库）。
 *
 * 为何不需要显式排除 WebFlux 自动配置？
 * spring-ai 的 spring-ai-retry 会传递引入 spring-webflux，使 @EnableAutoConfiguration 把本模块误判为
 * 响应式 Web 应用，进而装配 WebSocketHandlerAdapter / HttpHandler 等需要 jakarta.websocket 或 webHandler 的
 * Bean，在缺类时直接让上下文加载失败。agent-core 是纯库模块，测试不需要任何 Web 服务器，因此在
 * application-test.yml 中设置 spring.main.web-application-type=none，从根上关闭 Web 上下文装配——
 * 比逐个排除自动配置类更稳健；spring-webflux 的 jar 仍留在 classpath 上，不影响生产期
 * deepseek.enabled=true 时 OpenAiApi（DeepSeekModelClient）正常加载。
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan("com.insightforge.agent.core")
@MapperScan("com.insightforge.persistence.mapper")
public class AgentCoreTestConfig {
}
