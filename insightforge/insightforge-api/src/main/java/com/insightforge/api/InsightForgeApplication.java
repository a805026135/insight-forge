package com.insightforge.api;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * InsightForge 启动类（Phase 0）。
 * 扫描整个 com.insightforge 基包，装载 common / persistence / seckill-core / api 各模块 Bean。
 * Phase 1 起用 @MapperScan 让 MyBatis-Plus 扫描持久层 Mapper 接口（com.insightforge.persistence.mapper）。
 */
@SpringBootApplication(scanBasePackages = "com.insightforge")
@MapperScan("com.insightforge.persistence.mapper")
@EnableScheduling  // Phase 14.5：订单补偿巡检等定时任务
public class InsightForgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(InsightForgeApplication.class, args);
    }
}
