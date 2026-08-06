package com.insightforge.api.config;

import com.insightforge.api.observability.ObservabilityService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Configuration;

/**
 * 自定义业务指标注册（Phase 10，对应监测设计文档 L4 系统评估）。
 *
 * Actuator 自带 JVM/HTTP 指标，但面试 demo 要能证明「无超卖 + AI 控盘有效」，
 * 必须有一组**业务语义**的指标让 Prometheus 抓取、Grafana 画图。这里把
 * ObservabilityService 的计数方法注册成 Gauge——每次 Prometheus 来 scrape 时实时读库计算，
 * 教学库表小，开销可忽略；生产可换成异步缓存计数。
 *
 * 指标命名前缀 {@code insightforge_}，便于在 Grafana 里一眼定位本项目。
 */
@Configuration
public class MetricsConfig {

    public MetricsConfig(MeterRegistry registry, ObservabilityService observabilityService) {
        registry.gauge("insightforge_activities_total", observabilityService,
                ObservabilityService::countActivities);
        registry.gauge("insightforge_seckill_orders_total", observabilityService,
                ObservabilityService::countOrders);
        registry.gauge("insightforge_agent_traces_total", observabilityService,
                ObservabilityService::countTraces);
        registry.gauge("insightforge_agent_correct_failure_rate", observabilityService,
                ObservabilityService::correctFailureRate);
        registry.gauge("insightforge_guardrail_policies_total", observabilityService,
                ObservabilityService::countPolicies);
    }
}
