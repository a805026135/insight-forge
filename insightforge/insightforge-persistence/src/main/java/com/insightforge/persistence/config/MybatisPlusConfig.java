package com.insightforge.persistence.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置（Phase 0 基础配置）。
 * 数据源由 spring.datasource 自动装配（见 api 模块 application.yml）。
 *
 * 说明：本 Phase 还没有任何分页查询，故此处只注册 MybatisPlusInterceptor 占位。
 * 后续 Phase 真正做分页列表（如活动/订单分页）时，再在此处 addInnerInterceptor
 * 一个分页拦截器（注意：mybatis-plus 3.5.9 的 extension 包已不含
 * PaginationInnerInterceptor，需按当时版本补对应依赖或使用替代方案）。
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        return new MybatisPlusInterceptor();
    }
}
