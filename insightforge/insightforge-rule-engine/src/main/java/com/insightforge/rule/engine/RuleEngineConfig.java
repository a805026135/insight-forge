package com.insightforge.rule.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insightforge.persistence.mapper.GuardrailPolicyMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * 规则引擎生产装配（数据面）。
 *
 * 扫描本包所有 @Component/@Service（RuleEngineService / DslToDroolsTranslator /
 * DroolsRuleCompiler / ControlInstructionExecutor / LocalLockService / StockSegmentService /
 * GuardrailMetrics / ShadowEngine / BacktestHarness），并显式提供 PolicySource（从
 * guardrail_policy 表读 DSL 的 DbPolicySource）。
 *
 * 本地/测试环境改用 RuleEngineTestConfig（用 InMemoryPolicySource 替换），无需数据库即可跑。
 */
@Configuration
@ComponentScan("com.insightforge.rule.engine")
public class RuleEngineConfig {

    @Bean
    public PolicySource dbPolicySource(GuardrailPolicyMapper mapper, ObjectMapper objectMapper) {
        return new DbPolicySource(mapper, objectMapper);
    }
}
