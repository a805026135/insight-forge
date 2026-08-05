package com.insightforge.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * InsightForge 启动类（Phase 0）。
 * 扫描整个 com.insightforge 基包，装载 common / persistence / seckill-core / api 各模块 Bean。
 */
@SpringBootApplication(scanBasePackages = "com.insightforge")
public class InsightForgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(InsightForgeApplication.class, args);
    }
}
