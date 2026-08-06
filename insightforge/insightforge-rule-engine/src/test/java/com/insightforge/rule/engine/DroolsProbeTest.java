package com.insightforge.rule.engine;

import org.junit.jupiter.api.Test;
import org.kie.api.KieServices;
import org.kie.api.builder.KieBuilder;
import org.kie.api.builder.KieFileSystem;
import org.kie.api.builder.Message;
import org.kie.api.builder.Results;
import org.kie.api.io.ResourceType;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Drools 9 在 Java 21 沙箱的可行性探针（Phase 9 前置验证）。
 * 不依赖 Spring，仅验证：DRL 文本 → KieBuilder 编译 → 插入事实 → fireAllRules → RHS 生效。
 * 若此测试通过，说明 Drools 可作为本项目的护栏规则执行引擎；否则降级为纯 Java 执行器。
 */
class DroolsProbeTest {

    @Test
    void droolsCanCompileDrlAndFireRulesOnJava21() {
        String drl = """
            package com.insightforge.probe
            import com.insightforge.rule.engine.ProbeTick

            rule "low_stock_alert"
            when
                $t : ProbeTick( stockRatio < 0.2 )
            then
                $t.setFlag("LOW_STOCK");
            end
            """;

        KieServices ks = KieServices.Factory.get();
        KieFileSystem kfs = ks.newKieFileSystem();
        kfs.write("src/main/resources/rules/probe.drl", ks.getResources().newByteArrayResource(drl.getBytes()).setResourceType(ResourceType.DRL));

        KieBuilder kb = ks.newKieBuilder(kfs);
        kb.buildAll();
        Results results = kb.getResults();
        assertFalse(results.hasMessages(Message.Level.ERROR),
                () -> "DRL 编译报错: " + results.getMessages(Message.Level.ERROR));

        KieContainer container = ks.newKieContainer(kb.getKieModule().getReleaseId());
        KieSession session = container.newKieSession();
        try {
            ProbeTick tick = new ProbeTick(0.1);
            session.insert(tick);
            int fired = session.fireAllRules();
            assertEquals(1, fired, "应触发 1 条规则");
            assertEquals("LOW_STOCK", tick.getFlag(), "RHS 应改写 flag");
        } finally {
            if (session != null) {
                session.dispose();
            }
        }
    }
}
