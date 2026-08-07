# 选做3 · Drools 规则引擎深入

> 进阶选做章节。跳过不影响主线理解。前置：读过 Phase 9 主线（Drools 在线执行 DSL）。

## 📖 是什么

Phase 9 的 **DSL → Drools 翻译与在线执行**的完整实现链路：

- `DslToDroolsTranslator`：把扁平 `GuardrailRule(id, metric, operator, threshold, action, params)` 翻译成 `.drl` 规则文本。
- `DroolsRuleCompiler`：**编译门禁**（Fail-Close）——DSL 编译不过就阻断上线，绝不放裸规则进热路径。
- `RuleEngineService`：消费 `SalesTick` 事实，跑 Drools PHREAK 推理，输出标准化 `ControlInstruction`（REPRICE/REALLOCATE/ALERT + 风险分级）。
- `DroolsProbe`：最小探针，验证 KieBase 可建。

## 🧠 为什么是选做

Drools 是「工业级规则引擎」的**面试官认得的关键词**——保留它作为卖点是对的。但**同一句"确定性数据面执行护栏、零 LLM 热路径"用一个 30 行的 Java 规则循环也能讲**，复杂度只有 1/10。本选做深入翻译器与 KieBase，主要服务于两类读者：

1. 想在面试里讲「我懂 Drools / PHREAK」的人；
2. 纠结「要不要上 Drools」、想看清楚翻译层多厚的人。

## 关键文件（`insightforge-rule-engine/`）

```
DslToDroolsTranslator.java    DSL → .drl 文本
DroolsRuleCompiler.java       KieBase 编译门禁（Fail-Close）
RuleEngineService.java        在线执行（消费 SalesTick）
DroolsProbe.java              最小探针
DslToDroolsTranslatorTest / DroolsRuleCompilerTest / DroolsProbeTest
```

## 核心入口

```java
String drl = translator.translate(policy);      // GuardrailPolicy → .drl
boolean ok = compiler.compile(drl);             // Fail-Close：不过则阻断上线
List<ControlInstruction> instrs = ruleEngine.evaluate(salesTick);  // 在线跑
```

## 如何跑

```bash
# rule-engine 模块零 Redis/Kafka 依赖，可独立跑：
mvn -pl insightforge-rule-engine test
# 含 DroolsProbeTest(1) / TranslatorTest(3) / CompilerTest(2)
```

## ⚠️ 想换自研引擎？

若觉得 Drools 太重，可换成手搓规则引擎——**只动翻译层**，不动 DSL 与执行契约：

- 实现 `PolicySource` + 一个 `List<Rule>` 的 `predicate → action` 循环即可；
- `GuardrailPolicy` / `GuardrailRule` / `ControlInstruction` 全部复用；
- 主线卖点（确定性、零 LLM、Fail-Close）一字不变。

## 与主线的关系

主线 Phase 9 只讲"Drools 在线执行 DSL、零 LLM 热路径"这句话。本选做把"DRL 怎么来、KieBase 怎么建"讲透。**`ControlInstructionExecutor` 输出指令、写回库存的契约不变**——换不换 Drools 都不影响数据面行为。

## ⚠️ 诚实边界

- Drools 纯 Java 库（父 POM `drools.version=9.44.0.Final`），`rule-engine` 模块零 Redis/Kafka 依赖即可跑（沙箱已实测）。
- 启动时 Drools 会打一条 `Unable to build KieBaseModel:defaultKieBase` 的 ERROR 级日志——是 Drools 已知噪音（重建 KieBase 时的冗余输出），所有 Drools 测试均通过，不影响功能。
