package com.insightforge.rule.engine;

import com.insightforge.agent.core.planning.Action;
import com.insightforge.agent.core.planning.GuardrailPolicy;
import com.insightforge.agent.core.planning.GuardrailRule;
import com.insightforge.agent.core.planning.Metric;
import com.insightforge.agent.core.planning.Operator;
import org.junit.jupiter.api.Test;
import org.kie.api.runtime.KieContainer;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 验证 Drools 编译器的 Fail-Close 门禁：合法 DRL 编译成功，非法 DRL 抛异常阻断上线。
 */
class DroolsRuleCompilerTest {

    private final DslToDroolsTranslator translator = new DslToDroolsTranslator();
    private final DroolsRuleCompiler compiler = new DroolsRuleCompiler();

    @Test
    void compilesValidDrlIntoContainer() {
        GuardrailRule r = new GuardrailRule("r1", Metric.STOCK_RATIO, Operator.LT,
                new BigDecimal("0.2"), Action.ALERT, Map.of("reason", "low"));
        GuardrailPolicy p = new GuardrailPolicy(1L, "v1", List.of(r));
        KieContainer c = compiler.compile(translator.translate(p));
        assertNotNull(c);
    }

    @Test
    void failsClosedOnInvalidDrl() {
        // 引用不存在的类型 → 编译 ERROR → 抛 DslCompileException（Fail-Close）
        String badDrl = "package x\nrule \"bad\"\nwhen\n  $t : NoSuchType( foo > 1 )\nthen\nend\n";
        assertThrows(DslCompileException.class, () -> compiler.compile(badDrl));
    }
}
