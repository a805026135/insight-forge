package com.insightforge.rule.engine;

import org.kie.api.KieServices;
import org.kie.api.builder.KieBuilder;
import org.kie.api.builder.KieFileSystem;
import org.kie.api.builder.KieModule;
import org.kie.api.builder.Message;
import org.kie.api.builder.Results;
import org.kie.api.io.ResourceType;
import org.kie.api.runtime.KieContainer;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Drools 规则编译器（Fail-Close 门禁的执行体）。
 *
 * 大白话：把翻译好的 DRL 文本真正「编译」成 Drools 可执行的容器（KieContainer）。
 * 编译有任何 ERROR → 抛 {@link DslCompileException}，DSL 一律不得上线
 * （宁可这次不出策略，也绝不裸奔）。
 * 编译成功的 KieContainer 按「活动:版本」缓存，DSL 不变就不重复编译（性能 + 热更新靠 invalidate）。
 */
@Component
public class DroolsRuleCompiler {

    private static final KieServices KS = KieServices.Factory.get();
    private final ConcurrentHashMap<String, KieContainer> cache = new ConcurrentHashMap<>();

    /** 编译一份 DRL（不缓存，用于 Fail-Close 校验 / 测试）。 */
    public KieContainer compile(String drl) {
        KieFileSystem kfs = KS.newKieFileSystem();
        kfs.write("src/main/resources/rules/generated.drl",
                KS.getResources().newByteArrayResource(drl.getBytes(StandardCharsets.UTF_8))
                        .setResourceType(ResourceType.DRL));
        KieBuilder kb = KS.newKieBuilder(kfs);
        kb.buildAll();
        Results results = kb.getResults();
        if (results.hasMessages(Message.Level.ERROR)) {
            throw new DslCompileException("DRL 编译失败: " + results.getMessages(Message.Level.ERROR));
        }
        KieModule module = kb.getKieModule();
        return KS.newKieContainer(module.getReleaseId());
    }

    /** 编译并缓存（按 key，如 "activityId:version"）。 */
    public KieContainer compileOrCached(String key, String drl) {
        return cache.computeIfAbsent(key, k -> compile(drl));
    }

    /** DSL 版本更新后，让旧容器失效（下次重新编译，实现热更新）。 */
    public void invalidate(String key) {
        KieContainer old = cache.remove(key);
        if (old != null) {
            old.dispose();
        }
    }

    public int cachedCount() {
        return cache.size();
    }
}
